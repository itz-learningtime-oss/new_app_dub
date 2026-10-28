package com.voxstudio.app

import android.content.Context
import android.content.res.Configuration

/**
 * System-style colours for light and dark appearance, following the phone setting.
 * Grouped backgrounds, secondary surfaces, hairline separators and one sparing accent.
 * Call [update] before building views; activities and dialogs read the values afresh.
 */
object Palette {
    var dark = false; private set
    fun update(context: Context) {
        val prefs = context.getSharedPreferences("studio", Context.MODE_PRIVATE)
        val mode = prefs.getString("appearance", "light") ?: "light"
        dark = when (mode) {
            "dark" -> true
            "system" -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    }
    fun setAppearance(context: Context, mode: String) {
        context.getSharedPreferences("studio", Context.MODE_PRIVATE).edit().putString("appearance", mode).apply()
        update(context)
    }
    private fun pick(dark: Long, light: Long) = (if (this.dark) dark else light).toInt()
    /** Page background (grouped). */
    val bg get() = pick(0xFF000000, 0xFFF2F2F7)
    /** Grouped surfaces: cards, list groups, fields. */
    val panel get() = pick(0xFF1C1C1E, 0xFFFFFFFF)
    /** Fills for secondary buttons, search fields and segmented tracks. */
    val raised get() = pick(0xFF2C2C2E, 0xFFE9E9EE)
    val field get() = panel
    val line get() = pick(0xFF38383A, 0xFFD1D1D6)
    val ink get() = pick(0xFFFFFFFF, 0xFF000000)
    val muted get() = pick(0xFF98989F, 0xFF6C6C70)
    val faint get() = pick(0xFF636366, 0xFFAEAEB2)
    /** Accent for selection, progress and links; text-safe on both backgrounds. */
    val accent get() = pick(0xFFFF9F0A, 0xFFC25E00)
    val error get() = pick(0xFFFF453A, 0xFFD70015)
    val success get() = pick(0xFF30D158, 0xFF248A3D)
    val bar get() = pick(0xFF121214, 0xFFF9F9F9)
    val selectedSegment get() = pick(0xFF636366, 0xFFFFFFFF)
    val press get() = pick(0x33FFFFFF, 0x1F000000)
}
