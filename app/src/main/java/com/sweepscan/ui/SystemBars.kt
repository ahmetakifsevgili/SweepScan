package com.sweepscan.ui

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ draws every app edge to edge: content runs under the status bar and the navigation
 * bar unless the app pads it. These helpers keep controls out from under the system bars (with
 * 3-button navigation a button under the bar is a tap on Home instead).
 */
object SystemBars {

    /** Adds the system-bar insets to the view's own padding on the chosen sides. */
    fun pad(view: View, top: Boolean = false, bottom: Boolean = false, sides: Boolean = true) {
        val left = view.paddingLeft
        val topPad = view.paddingTop
        val right = view.paddingRight
        val bottomPad = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                left + if (sides) bars.left else 0,
                topPad + if (top) bars.top else 0,
                right + if (sides) bars.right else 0,
                bottomPad + if (bottom) bars.bottom else 0,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** Dark status/navigation icons on a light screen, light icons in night mode. */
    fun matchIconsToTheme(window: Window) {
        // The whole app is dark (since 0.5.0): light icons everywhere.
        val night = true
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}
