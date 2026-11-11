# 08. Custom Plugin Settings, BottomSheets & TV Compatibility

> **CRITICAL ARCHITECTURAL DIRECTIVE (OPT-IN ONLY)**:
> Plugin settings screens are **STRICTLY OPT-IN**. Never generate a `SettingsFragment.kt` or wire `openSettings` by default unless the developer explicitly asks for plugin settings. By default, CloudStream extensions must remain lean, fast, and configuration-free. Use this guide only when custom settings are requested.

---

## 1. Registering the Settings Hook (`openSettings`)

In your `Plugin.kt` entrypoint:

```kotlin
package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ExampleProvider())

        // Reliable ContextWrapper unwrapping for Android TV & mobile
        this.openSettings = { ctx ->
            var current: Context? = ctx
            var activity: AppCompatActivity? = null
            while (current is ContextWrapper) {
                if (current is AppCompatActivity) {
                    activity = current
                    break
                }
                current = current.baseContext
            }
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val sheet = ExampleSettingsFragment(this)
                sheet.show(activity.supportFragmentManager, "ExamplePluginSettings")
            }
        }
    }
}
```

---

## 2. Resolving Dynamic Resources at Runtime

Because CloudStream plugins are dynamically loaded `.cs3` (DEX) bytecode bundles, standard compile-time resource references like `R.layout.my_settings` or `R.id.save_button` do not exist.

### The Dynamic Identifier Recipe:
Always access resources through `plugin.resources.getIdentifier`:

```kotlin
private fun <T : View> View.findView(name: String): T {
    val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
    if (id == 0) throw IllegalArgumentException("View ID '$name' not found in plugin resources")
    return findViewById(id)
}

private fun getDrawable(name: String): Drawable? {
    val id = plugin.resources!!.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
    return if (id != 0) plugin.resources!!.getDrawable(id, null) else null
}
```

In `onCreateView`:
```kotlin
val layoutId = plugin.resources!!.getIdentifier("settings_layout", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
val layout = plugin.resources!!.getLayout(layoutId)
val view = inflater.inflate(layout, container, false)
```

---

## 3. Lightweight Alternative: Pure Code `AlertDialog` (Zero XML Needed)

If you do not want to bundle custom XML layouts and drawables, build the settings UI purely in Kotlin using native Android `AlertDialog`:

```kotlin
openSettings = { ctx ->
    var current: Context? = ctx
    var activity: AppCompatActivity? = null
    while (current is ContextWrapper) {
        if (current is AppCompatActivity) {
            activity = current
            break
        }
        current = current.baseContext
    }

    activity?.let { act ->
        val isSafeMode = com.lagradost.cloudstream3.CloudStreamApp.getKey<Boolean>("MY_PLUGIN_SAFE_MODE") ?: true

        val checkBox = android.widget.CheckBox(act).apply {
            text = "Family Safe Mode (Block Adult / NSFW Content)"
            isChecked = isSafeMode
            setPadding(32, 32, 32, 32)
        }

        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle("Plugin Settings")
            .setView(checkBox)
            .setPositiveButton("Save") { dialog, _ ->
                com.lagradost.cloudstream3.CloudStreamApp.setKey("MY_PLUGIN_SAFE_MODE", checkBox.isChecked)
                com.lagradost.cloudstream3.CommonActivity.showToast("Settings saved", android.widget.Toast.LENGTH_SHORT)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
```

---

## 4. Key Settings Screen Patterns

### Pattern A: Domain Mirror Switcher
Allow users whose ISPs block the default domain to pick an alternative mirror:
```kotlin
val mirrors = arrayOf("https://mirror1.example.com", "https://mirror2.example.com", "https://mirror3.example.com")
AlertDialog.Builder(context)
    .setTitle("Select Domain Mirror")
    .setItems(mirrors) { _, which ->
        val selected = mirrors[which]
        sharedPref.edit().putString("active_domain", selected).apply()
        Toast.makeText(context, "Domain updated to $selected", Toast.LENGTH_SHORT).show()
    }
    .show()
```

### Pattern B: Manual Cloudflare / Turnstile Refresh
Provide a button inside settings that pops a visible `WebView` dialog, allowing the user to solve a Cloudflare challenge manually if silent auto-solve fails, saving `cf_clearance` cookies into `CookieManager`.

---

## 5. Android TV D-Pad Remote Compatibility

On Android TV devices, users navigate using a directional remote (D-Pad), not a touchscreen. If views are not focusable, the user will be locked out of the modal.

### The TV Compatibility Rules:
1. **Always Set Views as Focusable**:
   ```kotlin
   fun View.makeTvCompatible() {
       this.isFocusable = true
       this.isFocusableInTouchMode = false // true touchMode blocks D-pad highlights on TV
       this.isClickable = true
   }
   ```
2. **Apply Visible Outline on Focus**:
   Ensure interactive cards and buttons have a focused state drawable (e.g. outline or brightness tint) so the TV cursor position is clearly visible to the user.
3. **Window Width Constraint**:
   On wide TV screens, limit dialog width so it doesn't stretch across the entire 4K television:
   ```kotlin
   override fun onStart() {
       super.onStart()
       dialog?.window?.apply {
           val dm = resources.displayMetrics
           val maxW = (500 * dm.density).toInt()
           val width = if (dm.widthPixels > maxW) maxW else (dm.widthPixels * 0.9f).toInt()
           setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
       }
   }
   ```

---

## 6. Clean App Reboot Trigger (`restartApp`)

When updating critical core settings (like active API hosts or clearing HTTP cookie caches), execute a clean app reboot:

```kotlin
fun restartApp(context: Context) {
    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(context.packageName)
    val component = intent?.component
    if (component != null) {
        val restartIntent = Intent.makeRestartActivityTask(component)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }
}
```
