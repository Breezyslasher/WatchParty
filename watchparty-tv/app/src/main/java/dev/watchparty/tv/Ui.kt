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
import java.util.Locale
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    /** A round icon button ([sizePx] across; the icon [iconPx]): the room's
     *  transport (Room screen). Ring 4 text on focus, scale 1.08. */
    fun roundButton(c: Context, sizePx: Int, iconPx: Int, glyph: Glyph, fillColor: Int,
                    onClick: () -> Unit): Tile = Tile(c).apply {
        fill = fillColor
        radius = pxf(c, sizePx / 2)
        ring = TEXT
        ringWidth = pxf(c, 4)
        gapColor = BG
        gapWidth = pxf(c, 4)
        focusScale = 1.08f
        gravity = Gravity.CENTER
        addView(ImageView(c).apply { setImageDrawable(glyph) }, LinearLayout.LayoutParams(px(c, iconPx), px(c, iconPx)))
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

    /** Service cards: 112px, the service's logo (80px, radius 18; its initial until it loads), 34/700 name
     *  over a 22 muted note, ring 5 accent, scale 1.02. */
    fun serviceCard(c: Context, name: String, noteText: String, logo: String?, dimmed: Boolean, onClick: () -> Unit): Tile =
        Tile(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            fill = CHIP
            radius = pxf(c, 16)
            ring = ACCENT
            ringWidth = pxf(c, 5)
            focusScale = 1.02f
            setPadding(px(c, 20), px(c, 16), px(c, 32), px(c, 16))
            val badge = FrameLayout(c).apply {
                background = rounded(c, LINE, 18)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, pxf(c, 18))
                }
                clipToOutline = true
            }
            badge.addView(text(c, name.take(1).uppercase(Locale.getDefault()), 36, 800, MUTED).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            badge.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(badge, LinearLayout.LayoutParams(px(c, 80), px(c, 80)))
            if (logo != null) Posters.load(logo, img, px(c, 80))
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(oneLine(text(c, name, 34, 700)))
            texts.addView(text(c, noteText, 22, 400, MUTED).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 6) })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(c, 24) })
            minimumHeight = tap(c, 112)
            dim = if (dimmed) 0.55f else 1f
            setOnClickListener { onClick() }
        }

    /** Poster cards: 2:3 poster (radius 16), 26/700 title, 20 muted meta; ring 5 accent, scale 1.05. */
    fun posterCard(c: Context, title: String, meta: String, image: String?, onClick: () -> Unit): Tile =
        Tile(c).apply {
            orientation = LinearLayout.VERTICAL
            focusScale = 1.05f
            radius = pxf(c, 16)
            // the design had scale only; across a room that's hard to see, so the
            // accent ring too (drawn behind the card: the page-coloured fill keeps it a ring)
            fill = BG
            ring = ACCENT
            ringWidth = pxf(c, 5)
            setPadding(0, 0, 0, px(c, 12))
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

    /** Cast: round 150px photo (initials until it loads), 22/700 name, 20 muted character; ring 5 accent. */
    fun castCard(c: Context, name: String, character: String?, image: String?, onClick: () -> Unit): Tile =
        Tile(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            fill = BG               // the ring is drawn behind the card: a fill keeps it a ring
            radius = pxf(c, 20)
            ring = ACCENT
            ringWidth = pxf(c, 5)
            focusScale = 1.05f
            setPadding(px(c, 12), px(c, 14), px(c, 12), px(c, 14))
            val photo = FrameLayout(c).apply {
                background = oval(CHIP)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                clipToOutline = true
            }
            val initials = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
            photo.addView(text(c, initials, 40, 700, MUTED).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            photo.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(photo, LinearLayout.LayoutParams(px(c, 150), px(c, 150)))
            addView(text(c, name, 22, 700, lineHeight = 1.15f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 14) })
            if (!character.isNullOrBlank()) addView(text(c, character, 20, 400, MUTED, lineHeight = 1.15f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 4) })
            setOnClickListener { onClick() }
            Posters.load(YamtrackClient.posterUrl(image), img, px(c, 150))
        }

    /** Genre tags: not focusable, 2px line border, 22/600. */
    fun tag(c: Context, s: String): TextView = text(c, s, 22, 600, CHIP_TEXT).apply {
        background = rounded(c, 0, 24, 2, LINE)
        setPadding(px(c, 20), px(c, 8), px(c, 20), px(c, 8))
    }

    /** The source's rating (e.g. TMDB's): "7.8" over a muted "TMDB · 1.2k votes". */
    fun rating(c: Context, score: Double, source: String, votes: Int?): LinearLayout = row(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(c, CHIP, 12)
        setPadding(px(c, 18), px(c, 8), px(c, 18), px(c, 8))
        addView(text(c, String.format(Locale.US, "%.1f", score), 28, 800, ACCENT))
        val label = sourceName(source) + (if (votes != null && votes > 0) " · ${count(votes)} votes" else "")
        addView(text(c, label, 20, 600, MUTED), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(c, 12) })
    }

    fun sourceName(source: String) = when (source) {
        "tmdb" -> "TMDB"
        "mal" -> "MyAnimeList"
        "tvdb" -> "TVDB"
        "imdb" -> "IMDb"
        else -> source.uppercase(Locale.US)
    }

    /** 950 → "950", 12345 → "12.3k". */
    fun count(n: Int): String = if (n < 1000) "$n" else String.format(Locale.US, "%.1fk", n / 1000.0)

    /** A description that shows [lines] lines; OK (or a tap) shows all of it. Ring 4 text. */
    fun moreText(c: Context, s: String, sizePx: Int, lines: Int): Tile = Tile(c).apply {
        fill = BG
        radius = pxf(c, 12)
        ring = TEXT
        ringWidth = pxf(c, 4)
        setPadding(px(c, 16), px(c, 12), px(c, 16), px(c, 12))
        val t = text(c, s, sizePx, 400, TEXT_2, lineHeight = 1.45f).apply {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setOnClickListener { t.maxLines = if (t.maxLines == lines) Int.MAX_VALUE else lines }
    }

    /** "1999-03-31" → "31 Mar 1999" (as written in the TV's language). */
    fun date(s: String?): String? {
        if (s.isNullOrBlank()) return null
        return try {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s) ?: return s
            java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)
        } catch (e: java.text.ParseException) {
            s
        }
    }

    fun year(s: String?): String? = s?.takeIf { it.length >= 4 && it.substring(0, 4).all(Char::isDigit) }?.substring(0, 4)

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

/**
 * A vertical page of sections (the children of its one child). When focus
 * moves into a section, the whole section scrolls into view if it fits, so
 * a row's label and the rows around it stay on screen — instead of the
 * minimum that just reveals the focused card.
 */
class PageScroll(context: Context) : ScrollView(context) {
    init {
        isVerticalScrollBarEnabled = false
        isFocusable = false
        Ui.noClip(this)
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val content = getChildAt(0) as? ViewGroup ?: return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val room = height - paddingTop - paddingBottom
        val margin = Ui.px(context, 40)
        for (i in 0 until content.childCount) {
            val s = content.getChildAt(i)
            if (s.visibility != View.VISIBLE) continue
            val top = content.top + s.top
            val bottom = content.top + s.bottom
            if (rect.centerY() < top || rect.centerY() > bottom) continue
            val whole = Rect(rect.left, minOf(rect.top, top - margin), rect.right, maxOf(rect.bottom, bottom + margin))
            if (whole.height() <= room) return super.computeScrollDeltaToGetChildRectOnScreen(whole)
            break
        }
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }
}

/**
 * The transport's icons, drawn (the app has no icon set): play, pause, stop,
 * a dot, and a circular arrow with a number in it for skipping back or
 * forward ("replay 10" / "forward 30").
 */
class Glyph(private val kind: Kind, color: Int, private val number: String? = null,
            private val typeface: Typeface? = null) : Drawable() {
    enum class Kind { PLAY, PAUSE, STOP, DOT, BACK, FORWARD }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = android.graphics.Path()

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        paint.style = Paint.Style.FILL
        path.reset()
        when (kind) {
            Kind.PLAY -> {
                // nudged right: a triangle's visual centre is left of its box's
                path.moveTo(cx - s * 0.26f, cy - s * 0.36f)
                path.lineTo(cx + s * 0.38f, cy)
                path.lineTo(cx - s * 0.26f, cy + s * 0.36f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Kind.PAUSE -> {
                val w = s * 0.2f
                val h = s * 0.68f
                val gap = s * 0.16f
                canvas.drawRoundRect(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2, w * 0.25f, w * 0.25f, paint)
                canvas.drawRoundRect(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2, w * 0.25f, w * 0.25f, paint)
            }
            Kind.STOP -> {
                val h = s * 0.62f
                canvas.drawRoundRect(cx - h / 2, cy - h / 2, cx + h / 2, cy + h / 2, h * 0.16f, h * 0.16f, paint)
            }
            Kind.DOT -> canvas.drawCircle(cx, cy, s * 0.3f, paint)
            Kind.BACK, Kind.FORWARD -> {
                canvas.save()
                if (kind == Kind.BACK) canvas.scale(-1f, 1f, cx, cy)
                // a clockwise arc ending at the top, the arrowhead there pointing on
                val r = s * 0.4f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.075f
                paint.strokeCap = Paint.Cap.ROUND
                val end = 285f
                val sweep = 290f
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, end - sweep, sweep, false, paint)
                val a = Math.toRadians(end.toDouble())
                val px = cx + r * Math.cos(a).toFloat()
                val py = cy + r * Math.sin(a).toFloat()
                val dx = -Math.sin(a).toFloat()          // the clockwise tangent
                val dy = Math.cos(a).toFloat()
                val h = s * 0.2f
                paint.style = Paint.Style.FILL
                path.moveTo(px + dx * h * 0.7f, py + dy * h * 0.7f)
                path.lineTo(px - dx * h * 0.3f - dy * h * 0.55f, py - dy * h * 0.3f + dx * h * 0.55f)
                path.lineTo(px - dx * h * 0.3f + dy * h * 0.55f, py - dy * h * 0.3f - dx * h * 0.55f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.restore()
                if (number != null) {
                    paint.textSize = s * 0.3f
                    paint.typeface = typeface
                    paint.textAlign = Paint.Align.CENTER
                    val m = paint.fontMetrics
                    canvas.drawText(number, cx, cy - (m.ascent + m.descent) / 2, paint)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
