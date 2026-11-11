package com.example.game

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout

object ExampleGameLauncher {

    @SuppressLint("SetJavaScriptEnabled")
    fun launch(activity: Activity, gameId: String, onExit: () -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        val webView = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        }

        val bridge = ExampleGameBridge(activity, dialog, onExit)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // Intercept Android TV D-Pad & Bluetooth Gamepad keys
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        webView.evaluateJavascript("if (typeof autoSave === 'function') autoSave();", null)
                        bridge.exitGame()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('up', true);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('down', true);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('left', true);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('right', true);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('a', true);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BUTTON_B -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('b', true);", null)
                        return@setOnKeyListener true
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('up', false);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('down', false);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('left', false);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('right', false);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('a', false);", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BUTTON_B -> {
                        webView.evaluateJavascript("if (typeof sendKey === 'function') sendKey('b', false);", null)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        // Minimal HTML shell containing the frame loop and bridge bindings
        val htmlShell = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; }
                    html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                    #canvas-container { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
                    canvas { aspect-ratio: 4/3; height: 100%; max-width: 100%; object-fit: contain; image-rendering: pixelated; }
                </style>
            </head>
            <body>
                <div id="canvas-container">
                    <canvas id="game-canvas" width="256" height="240"></canvas>
                </div>
                <script>
                    const GAME_ID = "$gameId";
                    function autoSave() {
                        if (window.AndroidBridge && window.AndroidBridge.saveGameState) {
                            window.AndroidBridge.saveGameState(GAME_ID, JSON.stringify({ state: "saved" }));
                        }
                    }
                    function sendKey(key, isPressed) {
                        console.log("Key: " + key + " Pressed: " + isPressed);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        container.addView(webView)
        dialog.setContentView(container)

        dialog.setOnDismissListener {
            onExit()
        }

        webView.loadDataWithBaseURL("https://game.runtime", htmlShell, "text/html", "UTF-8", null)
        dialog.show()
    }
}
