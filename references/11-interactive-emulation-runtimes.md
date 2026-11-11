# 11. Interactive Emulation, Retro Games & Canvas Runtimes

> **THE CLOUDSTREAM RUNTIME ENGINE**:
> Under `TvType.Others` (declared in `build.gradle.kts` for UI category filtering), CloudStream can host full-fledged interactive retro emulation runtimes (Game Boy Advance via `gbajs`, NES via `jsnes`, and DOS/Canvas games). In Kotlin `load()`, the provider returns `newMovieLoadResponse(..., TvType.CustomMedia)` to suppress ExoPlayer so the custom game launcher takes over. This guide documents the battle-tested engineering architecture required to run high-performance emulators inside an Android `WebView` without audio crackling, frame desyncs, or save corruption.

---

## 1. The Launcher & Fullscreen Immersion Architecture

When a user opens an emulation game title, `loadLinks` launches a fullscreen immersive `Dialog` hosting an accelerated `WebView`:

```kotlin
object GameLauncher {
    fun launch(context: Context, romBytes: ByteArray, bundleJs: String, htmlTemplate: String) {
        val activity = context as? Activity ?: return

        // 1. Unpack assets to filesDir (NEVER cacheDir which Android wipes)
        val gameDir = File(context.filesDir, "game_data").apply { mkdirs() }
        val romFile = File(gameDir, "rom.bin").apply { writeBytes(romBytes) }

        // 2. Build Fullscreen Immersion Dialog
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val webView = WebView(activity)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        // 3. Connect Native JavaScript Persistence Bridge
        webView.addJavascriptInterface(GameBridge(activity, dialog), "AndroidBridge")
        webView.loadUrl("file://${File(gameDir, "index.html").absolutePath}")

        dialog.setContentView(webView)
        dialog.show()
    }
}
```

---

## 2. The 7 Golden Rules of Android WebView Emulation

### Rule 1: APU Audio Register Shadowing (JSNES State Restoration)
* **The Bug**: JSNES `toJSON()` and `fromJSON()` serialize CPU, PPU, and Mapper, but completely omit `papu` (the audio synthesizer). Upon restoring state, `fromJSON()` calls `papu.reset()`, muting all 5 sound channels.
* **The Fix**: Intercept all `nes.papu.writeReg(addr, val)` calls into an `apuShadow` dictionary. Include `apuShadow` in the serialized state JSON, and replay all registers (finishing with channel enable `$4015` / `16405`) upon restore.

### Rule 2: Atomic Internal Flash Persistence (Zero Save Corruption)
* **The Bug**: If CloudStream is closed or killed while writing a 128KB battery save, the save file corrupts, losing the player's progress.
* **The Fix**: Write atomically via a 3-step file exchange:
  1. Write save data to `save.tmp`.
  2. Copy existing `save.sav` to `save.bak`.
  3. Atomically rename `save.tmp` to `save.sav`.

### Rule 3: 120Hz Hardware-Locked Delta Frame Accumulator
* **The Bug**: On modern 90Hz, 120Hz, or 144Hz smartphone displays, unmetered `requestAnimationFrame` runs emulators at 2x to 3x double speed.
* **The Fix**: Lock emulation cycles using a delta time accumulator:
  ```javascript
  const FRAME_DURATION_MS = 1000 / 60.0988;
  let lastTime = performance.now();
  let accumulator = 0;

  function loop(now) {
      const delta = now - lastTime;
      lastTime = now;
      accumulator += Math.min(delta, 100); // Clamp to avoid spiral of death

      while (accumulator >= FRAME_DURATION_MS) {
          emulator.runFrame();
          accumulator -= FRAME_DURATION_MS;
      }
      requestAnimationFrame(loop);
  }
  ```

### Rule 4: GBA DirectSound DMA & FIFO Priming (`gbajs`)
* **Zero Disconnects**: Never call `jsAudio.disconnect()` on mobile WebAudio. Calling `disconnect()` permanently terminates Chromium's background audio pump thread. Output zero-filled silence buffers when paused instead.
* **DirectSound Priming**: GBA games stream sound via DMA 1/2 to FIFO A/B triggered by Timer 0/1. On state restore, verify `dmaA = 1` and execute a 4-word burst via `serviceDma()` if FIFOs are empty.
* **EEPROM 0xFF Default**: Unwritten flash memory must return `0xFF` (not `0x00`). Returning `0x00` tricks games into reading invalid opcodes (`Illegal instruction`).

### Rule 5: Bulletproof 4-Layer Auto-Save on Close
Progress must be captured seamlessly across all 4 exit paths:
1. **Double Back Navigation**: First back press auto-saves and pauses; second press within 2.5s flushes save data and delays dialog dismissal by 250ms to let asynchronous disk I/O finish.
2. **App Lifecycle**: Listen to `visibilitychange` (`document.visibilityState === 'hidden'`), `pagehide`, and `blur` to immediately trigger `autoSave()`.
3. **In-HUD Exit Button**: Shows `SAVING PROGRESS` toast, triggers `autoSave()`, and delays `dismiss()` by 250ms.
4. **Heartbeat Safeguard**: Flushes battery EEPROM every 15s during active gameplay as an anti-crash safeguard.

### Rule 6: Per-Game Controls Isolation (Zero Guesswork)
* **Never Guess Button Roles**: Always verify the cartridge's authentic controls from official game manuals or speedrun wikis.
* Store layout coordinates using `${game}_controls_layout.json` in `localStorage` so button repositioning in one game never alters another game's layout.

### Rule 7: Physical Gamepad & Android TV D-Pad Interception
Intercept hardware Bluetooth gamepads and TV remote keys inside `Dialog.dispatchKeyEvent`:
```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val isDown = event.action == KeyEvent.ACTION_DOWN
    val keyCode = event.keyCode
    val mappedButton = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP     -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN   -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT   -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT  -> "RIGHT"
        KeyEvent.KEYCODE_BUTTON_A    -> "A"
        KeyEvent.KEYCODE_BUTTON_B    -> "B"
        KeyEvent.KEYCODE_BUTTON_L1   -> "L"
        KeyEvent.KEYCODE_BUTTON_R1   -> "R"
        KeyEvent.KEYCODE_BUTTON_START-> "START"
        KeyEvent.KEYCODE_BUTTON_SELECT-> "SELECT"
        else -> null
    }
    if (mappedButton != null) {
        webView.evaluateJavascript("window.handleGamepad('$mappedButton', $isDown);", null)
        return true
    }
    return super.dispatchKeyEvent(event)
}
```
