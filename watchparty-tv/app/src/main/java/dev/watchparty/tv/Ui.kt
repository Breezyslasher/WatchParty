package dev.watchparty.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "Cinema" design system (Watch Party A - Cinema.dc.html): tokens, fonts
 * and the focusable building blocks. The design canvas is 1920×1080 px; an
 * Android TV at 1080p is 960×540 dp, so every design px is px/2 dp (or sp).
 */
object Ui {
    const val BG = 0xFF0B0C0E.toInt()
    const val RAIL = 0xFF111215.toInt()
    const val SURFACE = 0xFF15171B.toInt()
    const val CHIP = 0xFF1B1D21.toInt()
    const val LINE = 0xFF24272D.toInt()
    const val LINE_SOFT = 0xFF1F2126.toInt()
    const val TEXT = 0xFFF1EFE9.toInt()
    const val TEXT_2 = 0xFFA7A9AE.toInt()
    const val MUTED = 0xFF8B8E94.toInt()
    const val FAINT = 0xFF6C6F76.toInt()
    const val ACCENT = 0xFF6FA8FF.toInt()
    const val ON_ACCENT = 0xFF0B1220.toInt()
    const val WARN = 0xFFF3C47A.toInt()
    const val CHIP_TEXT = 0xFFD6D4CE.toInt()
    const val PLACEHOLDER = 0xFF5D6067.toInt()
    const val STRIPE_A = 0xFF1A1C20.toInt()
    const val STRIPE_B = 0xFF16181B.toInt()

    /** Below this width the rail becomes a top section (phones). */
    const val PHONE_MAX_DP = 840

    fun isPhone(c: Context) = c.resources.configuration.screenWidthDp < PHONE_MAX_DP

    /** Design px → device px (px/2 dp). */
    fun px(c: Context, designPx: Number): Int =
        (designPx.toFloat() / 2f * c.resources.displayMetrics.density + 0.5f).toInt()

    fun pxf(c: Context, designPx: Number): Float = designPx.toFloat() / 2f * c.resources.displayMetrics.density

    /** A tap target: the design height, but at least 48 dp on phones. */
    fun tap(c: Context, designPx: Number): Int {
        val h = px(c, designPx)
        return if (isPhone(c)) maxOf(h, (48 * c.resources.displayMetrics.density + 0.5f).toInt()) else h
    }

    // ── fonts ───────────────────────────────────────────────────────────

    private val faces = HashMap<String, Typeface>()

    /** Figtree at 400/500/600/700/800 (assets/fonts); the default face if missing. */
    fun font(c: Context, weight: Int): Typeface {
        val name = when {
            weight >= 800 -> "Figtree-ExtraBold"
            weight >= 700 -> "Figtree-Bold"
            weight >= 600 -> "Figtree-SemiBold"
            weight >= 500 -> "Figtree-Medium"
            else -> "Figtree-Regular"
        }
        return load(c, name) ?: if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun mono(c: Context): Typeface = load(c, "JetBrainsMono-Regular") ?: Typeface.MONOSPACE

    private fun load(c: Context, name: String): Typeface? {
        faces[name]?.let { return it }
        return try {
            Typeface.createFromAsset(c.assets, "fonts/$name.ttf").also { faces[name] = it }
        } catch (e: Exception) {
            null
        }
    }

    // ── text ────────────────────────────────────────────────────────────

    fun text(
        c: Context,
        s: CharSequence,
        sizePx: Number,
        weight: Int = 400,
        color: Int = TEXT,
        spacingEm: Float = 0f,
        caps: Boolean = false,
        lineHeight: Float = 0f,
        mono: Boolean = false,
    ): TextView = TextView(c).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizePx.toFloat() / 2f)
        typeface = if (mono) mono(c) else font(c, weight)
        if (spacingEm != 0f) letterSpacing = spacingEm
        if (caps) isAllCaps = true
        if (lineHeight > 0f) setLineSpacing(0f, lineHeight)
        includeFontPadding = false
    }

    /** "SECTION" labels: 22/600, uppercase, 0.12em, muted. */
    fun sectionLabel(c: Context, s: String) = text(c, s, 22, 600, MUTED, spacingEm = 0.12f, caps = true)

    fun oneLine(t: TextView) = t.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** The brand row: accent dot + "Watch Party". */
    fun brand(c: Context, dotPx: Int, sizePx: Int, gapPx: Int): LinearLayout = row(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(c).apply { background = oval(ACCENT) }, LinearLayout.LayoutParams(px(c, dotPx), px(c, dotPx)))
        addView(text(c, "Watch Party", sizePx, 700, spacingEm = -0.01f),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = px(c, gapPx) })
    }

    // ── layout helpers ──────────────────────────────────────────────────

    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; noClip(this) }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; noClip(this) }

    /** Focus rings and focus scale draw outside a view's bounds, like CSS
     *  box-shadow: containers must not clip them. */
    fun noClip(v: ViewGroup) {
        v.clipChildren = false
        v.clipToPadding = false
    }

    fun gap(c: Context, designPx: Int, vertical: Boolean = true): View = View(c).apply {
        layoutParams = if (vertical) LinearLayout.LayoutParams(1, px(c, designPx))
        else LinearLayout.LayoutParams(px(c, designPx), 1)
    }

    fun divider(c: Context, color: Int = LINE): View = View(c).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, px(c, 2) / 2))
    }

    fun Context.margins(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0,
                        w: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                        h: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            setMargins(px(this@margins, start), px(this@margins, top), px(this@margins, end), px(this@margins, bottom))
        }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun rounded(c: Context, color: Int, radiusPx: Int, strokePx: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = pxf(c, radiusPx)
            if (strokePx > 0) setStroke(maxOf(1, px(c, strokePx)), strokeColor)
        }

    /** Clips a view (and its children) to a rounded rectangle. */
    fun clipRounded(v: View, radius: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        v.clipToOutline = true
    }

    // ── buttons (values from the design file) ───────────────────────────

    private fun TextTile.style(c: Context, text: String, sizePx: Int, weight: Int, color: Int): TextTile {
        label.text = text
        label.setTextColor(color)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizePx / 2f)
        label.typeface = font(c, weight)
        return this
    }

    /** Accent button with the double focus ring: a [gapColor] gap, then a text-coloured ring. */
    fun primary(c: Context, text: String, heightPx: Int, padPx: Int, sizePx: Int, radiusPx: Int,
                gapColor: Int, ringPx: Int, scale: Float, onClick: () -> Unit): TextTile =
        TextTile(c).apply {
            style(c, text, sizePx, 700, ON_ACCENT)
            label.gravity = Gravity.CENTER
            label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            fill = ACCENT
            radius = pxf(c, radiusPx)
            ring = TEXT
            ringWidth = pxf(c, ringPx)
            this.gapColor = gapColor
            gapWidth = pxf(c, ringPx)
            focusScale = scale
            setPadding(px(c, padPx), 0, px(c, padPx), 0)
            minimumHeight = tap(c, heightPx)
            setOnClickListener { onClick() }
        }

    /** Rail navigation: 72px, left-aligned 28/600, ring 4 text, scale 1.03. */
    fun nav(c: Context, text: String, onClick: () -> Unit): TextTile = TextTile(c).apply {
        style(c, text, 28, 600, CHIP_TEXT)
        radius = pxf(c, 14)
        ring = TEXT
        ringWidth = pxf(c, 4)
        focusScale = 1.03f
        setPadding(px(c, 28), 0, px(c, 28), 0)
        setChosen(false)
        setOnClickListener { onClick() }
    }

    /** Browse tabs: 68px pills, 28/700, ring 4 text, scale 1.05. */
    fun tab(c: Context, text: String, onClick: () -> Unit): TextTile = TextTile(c).apply {
        style(c, text, 28, 700, CHIP_TEXT)
        label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        radius = pxf(c, 34)
        ring = TEXT
        ringWidth = pxf(c, 4)
        focusScale = 1.05f
        setPadding(px(c, 36), 0, px(c, 36), 0)
        setChosen(false)
        setOnClickListener { onClick() }
    }

    /** Filter chips: 52px, 2px line border that turns text on focus, 22/600, no scale. */
    fun chip(c: Context, text: String, onClick: () -> Unit): TextTile = TextTile(c).apply {
        style(c, text, 22, 600, CHIP_TEXT)
        label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        radius = pxf(c, 26)
        border = LINE
        borderFocus = TEXT
        borderWidth = pxf(c, 2)
        setPadding(px(c, 24), 0, px(c, 24), 0)
        setChosen(false)
        setOnClickListener { onClick() }
    }

    /** Season / episode rows: 68px, 26/600, optional trailing note at 20 (70%), ring 4 text. */
    fun listButton(c: Context, text: String, noteText: String?, onClick: () -> Unit): TextTile = TextTile(c).apply {
        style(c, text, 26, 600, CHIP_TEXT)
        radius = pxf(c, 12)
        ring = TEXT
        ringWidth = pxf(c, 4)
        setPadding(px(c, 24), 0, px(c, 24), 0)
        if (!noteText.isNullOrBlank()) {
            note.visibility = View.VISIBLE
            note.text = noteText
            note.alpha = 0.7f
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            note.typeface = font(c, 400)
            note.maxWidth = px(c, 220)
            (note.layoutParams as LinearLayout.LayoutParams).leftMargin = px(c, 16)
        }
        setChosen(false)
        setOnClickListener { onClick() }
    }

    /** Home tiles: 260px, surface, 44/700 title over a 26 muted line, ring 6 accent, scale 1.03. */
    fun homeTile(c: Context, title: String, sub: String, onClick: () -> Unit): Tile = Tile(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        fill = SURFACE
        radius = pxf(c, 24)
        ring = ACCENT
        ringWidth = pxf(c, 6)
        focusScale = 1.03f
        setPadding(px(c, 44), px(c, 44), px(c, 44), px(c, 44))
        addView(text(c, title, 44, 700))
        addView(text(c, sub, 26, 400, MUTED), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 12) })
        minimumHeight = px(c, 260)
        setOnClickListener { onClick() }
    }

    /** Service cards: 112px, surface, 34/700 name over a 22 muted note, ring 5 accent, scale 1.02. */
    fun serviceCard(c: Context, name: String, noteText: String, dimmed: Boolean, onClick: () -> Unit): Tile =
        Tile(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            fill = SURFACE
            radius = pxf(c, 16)
            ring = ACCENT
            ringWidth = pxf(c, 5)
            focusScale = 1.02f
            setPadding(px(c, 36), px(c, 8), px(c, 36), px(c, 8))
            addView(oneLine(text(c, name, 34, 700)))
            addView(text(c, noteText, 22, 400, MUTED).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 6) })
            minimumHeight = tap(c, 112)
            dim = if (dimmed) 0.55f else 1f
            setOnClickListener { onClick() }
        }

    /** Poster cards: 2:3 poster (radius 16), 26/700 title, 20 muted meta; scale 1.05 only. */
    fun posterCard(c: Context, title: String, meta: String, image: String?, onClick: () -> Unit): Tile =
        Tile(c).apply {
            orientation = LinearLayout.VERTICAL
            focusScale = 1.05f
            radius = pxf(c, 16)
            val poster = PosterView(c, pxf(c, 16))
            addView(poster, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            val t = text(c, title, 26, 700, lineHeight = 1.15f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 14) })
            addView(oneLine(text(c, meta, 20, 400, MUTED)), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 6) })
            tag = poster
            setOnClickListener { onClick() }
            post { Posters.load(YamtrackClient.posterUrl(image), poster.image, maxOf(poster.width, px(c, 200))) }
        }

    // ── inputs ──────────────────────────────────────────────────────────

    /** A text field: [fill] with a 2px border that turns accent when focused. */
    fun field(
        c: Context,
        hint: String,
        value: String,
        heightPx: Int,
        sizePx: Int,
        fill: Int,
        radiusPx: Int,
        padPx: Int,
        mono: Boolean = false,
        spacingEm: Float = 0f,
        caps: Boolean = false,
    ): EditText = EditText(c).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            (if (caps) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else 0)
        setTextColor(TEXT)
        setHintTextColor(PLACEHOLDER)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizePx / 2f)
        typeface = if (mono) mono(c) else font(c, 400)
        if (spacingEm != 0f) letterSpacing = spacingEm
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(c, padPx), 0, px(c, padPx), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(c, fill, radiusPx, 2, ACCENT))
            addState(intArrayOf(), rounded(c, fill, radiusPx, 2, LINE))
        }
        minimumHeight = tap(c, heightPx)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, tap(c, heightPx))
    }
}

/**
 * A focusable surface with the design's focus treatment: optional fill,
 * border (that can change colour on focus), a ring outside the bounds
 * (CSS `box-shadow: 0 0 0 Npx`), an optional gap ring for the double ring of
 * primary buttons, a focus scale animated over 150 ms, and a dimmed alpha
 * when not focused.
 */
open class Tile(context: Context) : LinearLayout(context) {
    var fill = 0
    var radius = 0f
    var border = 0
    var borderFocus = 0
    var borderWidth = 0f
    var ring = 0
    var ringWidth = 0f
    var gapColor = 0
    var gapWidth = 0f
    var focusScale = 1f
    var dim = 1f
        set(value) {
            field = value
            if (!isFocused) alpha = value
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
        Ui.noClip(this)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) focusScale else 1f
        animate().scaleX(s).scaleY(s).alpha(if (gainFocus) 1f else dim).setDuration(150).start()
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (isFocused && ringWidth > 0f) {
            val outer = gapWidth + ringWidth
            paint.style = Paint.Style.FILL
            paint.color = ring
            canvas.drawRoundRect(-outer, -outer, w + outer, h + outer, radius + outer, radius + outer, paint)
            if (gapWidth > 0f) {
                paint.color = gapColor
                canvas.drawRoundRect(-gapWidth, -gapWidth, w + gapWidth, h + gapWidth,
                    radius + gapWidth, radius + gapWidth, paint)
            }
        }
        if (fill != 0) {
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        }
        val b = if (isFocused && borderFocus != 0) borderFocus else border
        if (borderWidth > 0f && b != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = borderWidth
            paint.color = b
            val i = borderWidth / 2f
            canvas.drawRoundRect(i, i, w - i, h - i, radius - i, radius - i, paint)
        }
        super.draw(canvas)
    }
}

/** A Tile holding one label (with an optional trailing note), for every button style. */
class TextTile(context: Context) : Tile(context) {
    val label: TextView
    val note: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        label = TextView(context).apply { includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        note = TextView(context).apply {
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(note, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Selected = accent fill with on-accent text; otherwise chip fill. */
    fun setChosen(chosen: Boolean) {
        fill = if (chosen) Ui.ACCENT else Ui.CHIP
        val color = if (chosen) Ui.ON_ACCENT else Ui.CHIP_TEXT
        label.setTextColor(color)
        note.setTextColor(color)
        invalidate()
    }
}

/** Diagonal stripes (the design's placeholder for posters and backdrops). */
class StripesDrawable(private val a: Int, private val b: Int, private val stripe: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val r = bounds
        canvas.save()
        canvas.clipRect(r)
        paint.color = a
        canvas.drawRect(r, paint)
        paint.color = b
        paint.strokeWidth = stripe
        var x = -r.height().toFloat()
        while (x < r.width() + stripe) {
            // 135°: lines running from bottom-left to top-right
            canvas.drawLine(r.left + x, r.bottom.toFloat(), r.left + x + r.height(), r.top.toFloat(), paint)
            x += stripe * 2
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** A 2:3 poster with rounded corners, a 1px inner line, stripes until loaded. */
class PosterView(context: Context, private val radius: Float) : FrameLayout(context) {
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.LINE
        strokeWidth = maxOf(1f, context.resources.displayMetrics.density / 2f)
    }

    init {
        background = StripesDrawable(Ui.STRIPE_A, Ui.STRIPE_B, Ui.pxf(context, 12))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Ui.clipRounded(this, radius)
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = w * 3 / 2
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val i = paint.strokeWidth / 2f
        canvas.drawRoundRect(i, i, width - i, height - i, radius, radius, paint)
    }
}
