package com.voxstudio.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlin.math.roundToInt

/**
 * Pads a full-screen window's content by the status bar, navigation bar and display cutout, on top
 * of its own padding. Android 15+ draws apps that target API 35+ edge to edge, including full-screen
 * dialogs, so content would otherwise sit under the bars. Insets are not consumed, so a child (such
 * as a bottom bar) can take the edge this view skips.
 */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { view, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30)
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let { listOf(it.left, it.top, it.right, it.bottom) }
        else @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        view.setPadding(base[0] + l, base[1] + if (top) t else 0, base[2] + r, base[3] + if (bottom) b else 0)
        insets
    }
    requestApplyInsets()
}

/**
 * Small component kit shared by every screen and dialog: a system-style type scale, grouped inset
 * lists with hairline separators, monochrome primary buttons, a segmented control and icon buttons.
 * Colours come from [Palette], so light and dark appearance follow the phone setting.
 */
class Ui(val context: Context) {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    fun dpf(n: Float) = n * context.resources.displayMetrics.density
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    fun rounded(color: Int, radius: Int = 12, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius.toFloat()); if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }
    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    /** Press feedback confined to [content] (or the view bounds when content is null). */
    fun pressable(content: Drawable?, radius: Int = 0): Drawable =
        RippleDrawable(ColorStateList.valueOf(Palette.press), content, rounded(0xFFFFFFFF.toInt(), radius))

    // Type scale (sp): 34 large title, 22 title, 17 headline/body, 15 subhead, 13 footnote, 11 caption.
    fun text(value: CharSequence, size: Float = 17f, color: Int = Palette.ink, weight: Int = 0): TextView = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = when (weight) { 2 -> bold; 1 -> medium; else -> regular }
        setLineSpacing(dpf(1.5f), 1f); includeFontPadding = true
    }
    fun largeTitle(value: String) = text(value, 34f, weight = 2).apply { letterSpacing = -0.01f }
    fun title(value: String) = text(value, 22f, weight = 2)
    fun headline(value: String, color: Int = Palette.ink) = text(value, 17f, color, 1)
    fun body(value: String, color: Int = Palette.ink) = text(value, 17f, color)
    fun subhead(value: String, color: Int = Palette.muted) = text(value, 15f, color)
    fun footnote(value: String, color: Int = Palette.muted) = text(value, 13f, color)
    fun caption(value: String, color: Int = Palette.muted) = text(value, 11f, color, 1)

    fun gap(parent: LinearLayout, n: Int = 12) { parent.addView(Space(context), LinearLayout.LayoutParams(1, dp(n))) }
    fun column(parent: LinearLayout? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun row(parent: LinearLayout? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    /** Section header above a group, in small capitals like system settings lists. */
    fun sectionHeader(parent: LinearLayout, value: String, trailing: View? = null) {
        val header = row(parent).apply { setPadding(dp(16), dp(22), dp(16), dp(7)) }
        header.addView(text(value.uppercase(), 13f, Palette.muted).apply { letterSpacing = 0.02f }, LinearLayout.LayoutParams(0, -2, 1f))
        trailing?.let { header.addView(it) }
    }
    fun sectionFooter(parent: LinearLayout, value: String, color: Int = Palette.muted) {
        parent.addView(footnote(value, color).apply { setPadding(dp(16), dp(7), dp(16), 0) })
    }
    /** Rounded inset group; rows added with [groupRow] get separators automatically. */
    fun group(parent: LinearLayout): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(Palette.panel, 12); clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun separator(group: LinearLayout, inset: Int) {
        group.addView(View(context).apply { setBackgroundColor(Palette.line) },
            LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply { marginStart = dp(inset) })
    }
    fun groupRow(group: LinearLayout, title: CharSequence, subtitle: CharSequence? = null, value: String? = null,
                 leading: View? = null, trailing: View? = null, chevron: Boolean = false, titleColor: Int = Palette.ink,
                 description: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
        if (group.childCount > 0) separator(group, if (leading != null) 68 else 16)
        val line = row().apply { minimumHeight = dp(52); setPadding(dp(16), dp(10), dp(12), dp(10)) }
        leading?.let { line.addView(it, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }) }
        val words = column()
        words.addView(text(title, 17f, titleColor).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        subtitle?.let { words.addView(footnote(it.toString()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }) }
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        value?.let { line.addView(text(it, 17f, Palette.muted).apply { maxLines = 1; setPadding(dp(8), 0, dp(2), 0) }) }
        trailing?.let { line.addView(it) }
        if (chevron) line.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(Palette.faint); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        if (onClick != null) {
            line.background = pressable(null); line.isClickable = true; line.isFocusable = true
            line.contentDescription = description ?: listOfNotNull(title, subtitle, value).joinToString(", ")
            line.setOnClickListener { onClick() }
        }
        group.addView(line, LinearLayout.LayoutParams(-1, -2))
        return line
    }

    /** Primary: monochrome fill (white on black in dark mode). Secondary: grey fill. Plain: accent text. */
    fun button(value: String, style: String = "secondary", onClick: () -> Unit): Button = Button(context).apply {
        text = value; isAllCaps = false; textSize = 17f; typeface = medium; stateListAnimator = null
        minHeight = dp(50); minimumHeight = dp(50); minWidth = 0; minimumWidth = 0; setPadding(dp(18), dp(8), dp(18), dp(8))
        when (style) {
            "primary" -> { setTextColor(Palette.bg); background = pressable(rounded(Palette.ink, 14), 14) }
            "plain" -> { setTextColor(Palette.accent); background = pressable(null, 14) }
            "destructive" -> { setTextColor(Palette.error); background = pressable(rounded(Palette.raised, 14), 14) }
            else -> { setTextColor(Palette.ink); background = pressable(rounded(Palette.raised, 14), 14) }
        }
        setOnClickListener { onClick() }
    }
    fun setEnabled(view: View, enabled: Boolean) { view.isEnabled = enabled; view.alpha = if (enabled) 1f else .38f }
    fun iconButton(icon: Int, description: String, filled: Boolean = true, tint: Int = Palette.ink, size: Int = 44, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon); contentDescription = description; scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(tint)
        background = pressable(if (filled) oval(Palette.raised) else null, size / 2)
        minimumWidth = dp(size); minimumHeight = dp(size)
        setOnClickListener { onClick() }
    }
    fun check(visible: Boolean) = ImageView(context).apply {
        setImageResource(R.drawable.ic_check); imageTintList = ColorStateList.valueOf(Palette.accent)
        visibility = if (visible) View.VISIBLE else View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(6) }
    }
    /** Circular initial, tinted from the name so each voice is recognisable at a glance. */
    fun avatar(name: String, hue: Float? = null): TextView {
        val h = hue ?: ((name.hashCode() and 0x7fffffff) % 360).toFloat()
        val color = android.graphics.Color.HSVToColor(floatArrayOf(h, if (Palette.dark) .45f else .35f, if (Palette.dark) .55f else .85f))
        return text(name.take(1).uppercase(), 17f, if (Palette.dark) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt(), 1).apply {
            gravity = Gravity.CENTER; background = oval(color); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    /** Segmented control: equal segments on a grey track; the selected one is raised. */
    fun segmented(parent: LinearLayout, options: List<String>, selected: String, onSelect: (String) -> Unit) {
        val track = row(parent).apply { background = rounded(Palette.raised, 9); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        options.forEach { option ->
            val chosen = option == selected
            track.addView(text(option, 13f, Palette.ink, if (chosen) 1 else 0).apply {
                gravity = Gravity.CENTER; minHeight = dp(32); isSelected = chosen; isClickable = true; isFocusable = true
                background = if (chosen) rounded(Palette.selectedSegment, 7) else pressable(null, 7)
                contentDescription = "$option${if (chosen) ", selected" else ""}"
                setOnClickListener { if (!chosen) onSelect(option) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    fun progressBar(max: Int = 1000) = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        this.max = max; progressTintList = ColorStateList.valueOf(Palette.accent); progressBackgroundTintList = ColorStateList.valueOf(Palette.raised)
    }
    fun hairline() = View(context).apply { setBackgroundColor(Palette.line) }
    fun solid(color: Int) = ColorDrawable(color)
}
