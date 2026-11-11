package com.example.manga

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout

object ExampleMangaReaderDialog {

    @SuppressLint("SetJavaScriptEnabled")
    fun launch(activity: Activity, title: String, pages: List<String>, onDismiss: () -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        val webView = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss()
                    return@setOnKeyListener true
                }
            }
            false
        }

        dialog.setOnDismissListener {
            onDismiss()
        }

        val pagesHtml = pages.mapIndexed { idx, url ->
            """
            <div class="page-container" id="page-${idx + 1}">
                <div class="page-num">${idx + 1} / ${pages.size}</div>
                <img src="$url" alt="Page ${idx + 1}" loading="lazy" />
            </div>
            """.trimIndent()
        }.joinToString("\n")

        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <title>$title</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    html, body { width: 100%; background: #0b0c10; color: #fff; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    #header {
                        position: fixed; top: 0; left: 0; right: 0; height: 48px;
                        background: rgba(11, 12, 16, 0.92); backdrop-filter: blur(8px);
                        display: flex; align-items: center; justify-content: space-between;
                        padding: 0 16px; z-index: 100; border-bottom: 1px solid rgba(255,255,255,0.1);
                    }
                    #title { font-size: 14px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 80%; }
                    #close-btn { background: rgba(255,255,255,0.15); border: none; color: #fff; padding: 6px 12px; border-radius: 6px; font-size: 12px; font-weight: 700; cursor: pointer; }
                    #reader { margin-top: 50px; display: flex; flex-direction: column; align-items: center; gap: 4px; padding-bottom: 60px; }
                    .page-container { position: relative; width: 100%; max-width: 900px; display: flex; flex-direction: column; align-items: center; }
                    .page-container img { width: 100%; height: auto; display: block; background: #14161d; min-height: 200px; }
                    .page-num { position: absolute; bottom: 8px; right: 12px; background: rgba(0,0,0,0.7); font-size: 11px; padding: 2px 8px; border-radius: 4px; color: #aaa; }
                </style>
            </head>
            <body>
                <div id="header">
                    <span id="title">$title</span>
                    <button id="close-btn" onclick="window.history.back()">CLOSE</button>
                </div>
                <div id="reader">
                    $pagesHtml
                </div>
            </body>
            </html>
        """.trimIndent()

        container.addView(webView)
        dialog.setContentView(container)

        webView.loadDataWithBaseURL("https://manga.reader", htmlContent, "text/html", "UTF-8", null)
        dialog.show()
    }
}
