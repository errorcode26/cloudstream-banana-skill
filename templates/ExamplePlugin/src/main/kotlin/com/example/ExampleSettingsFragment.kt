package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Standard Settings & Configuration BottomSheet for CloudStream Plugins.
 * 
 * Demonstrates the official upstream resource resolution pattern:
 * Because plugin classes are loaded dynamically into the host app at runtime,
 * compile-time R.* references cannot be resolved directly. Use `plugin.resources?.getIdentifier`.
 */
class ExampleSettingsFragment(private val plugin: ExamplePlugin) : BottomSheetDialogFragment() {

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    private fun getStringResource(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = plugin.resources?.getIdentifier("fragment_settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (layoutId != null && layoutId != 0) {
            inflater.inflate(plugin.resources?.getLayout(layoutId), container, false)
        } else {
            // Dynamic programmatic fallback if XML layout is not bundled
            createFallbackLayout()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val titleView: TextView? = view.findViewByName("title_text")
        titleView?.text = getStringResource("settings_title") ?: "Plugin Settings"
    }

    private fun createFallbackLayout(): View {
        val context = requireContext()
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
        }

        val title = TextView(context).apply {
            text = "Plugin Configuration"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        val description = TextView(context).apply {
            text = "Active domain: https://example.com\nStatus: Connected"
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
        }
        layout.addView(description)

        val tmdbLabel = TextView(context).apply {
            text = "TMDB API Key (Optional):"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(tmdbLabel)

        val tmdbInput = android.widget.EditText(context).apply {
            hint = "Leave empty to use Cinemeta (Zero-auth)"
            setHintTextColor(android.graphics.Color.GRAY)
            setText(TmdbUtils.tmdbApiKey ?: "")
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#2A2A40"))
            setPadding(24, 16, 24, 16)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val key = s?.toString()?.trim()
                    TmdbUtils.tmdbApiKey = if (!key.isNullOrBlank()) key else null
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        layout.addView(tmdbInput)

        return layout
    }
}
