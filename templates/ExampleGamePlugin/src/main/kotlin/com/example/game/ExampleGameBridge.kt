package com.example.game

import android.app.Activity
import android.app.Dialog
import android.webkit.JavascriptInterface
import java.io.File

class ExampleGameBridge(
    private val activity: Activity,
    private val dialog: Dialog,
    private val onExit: () -> Unit
) {
    private val saveDir: File by lazy {
        File(activity.filesDir, "game_saves").apply { mkdirs() }
    }

    @JavascriptInterface
    fun hasGameState(gameId: String): Boolean {
        val saveFile = File(saveDir, "${gameId}.save")
        return saveFile.exists() && saveFile.length() > 0
    }

    @JavascriptInterface
    fun saveGameState(gameId: String, stateJson: String): Boolean {
        if (stateJson.isBlank()) return false
        val target = File(saveDir, "${gameId}.save")
        val tmp = File(saveDir, "${gameId}.save.tmp")
        val bak = File(saveDir, "${gameId}.save.bak")

        return try {
            tmp.writeText(stateJson, Charsets.UTF_8)
            if (target.exists()) {
                target.copyTo(bak, overwrite = true)
            }
            if (tmp.renameTo(target)) {
                bak.delete()
                true
            } else {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
                bak.delete()
                true
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    @JavascriptInterface
    fun loadGameState(gameId: String): String? {
        val target = File(saveDir, "${gameId}.save")
        val bak = File(saveDir, "${gameId}.save.bak")
        return when {
            target.exists() && target.length() > 0 -> {
                try { target.readText(Charsets.UTF_8) } catch (_: Throwable) { null }
            }
            bak.exists() && bak.length() > 0 -> {
                try { bak.readText(Charsets.UTF_8) } catch (_: Throwable) { null }
            }
            else -> null
        }
    }

    @JavascriptInterface
    fun exitGame() {
        activity.runOnUiThread {
            try {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                onExit()
            }
        }
    }
}
