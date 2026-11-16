package io.github.breezyslasher.watchparty

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * "Start for everyone on": the services for a title or episode, in a dialog
 * centered over the title page — no scrolling down to a list. Focus stays in
 * it; Back (or, on a phone, a tap outside) closes it and focus returns to
 * the episode or Watch button that opened it.
 */
class ServiceDialog(private val c: Activity, title: String, sub: String) {
    private val phone = Ui.isPhone(c)
    val dialog = Dialog(c, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    private val hint = Ui.text(c, "", 26, 400, Ui.FAINT, lineHeight = 1.4f).apply { visibility = View.GONE }
    private val list = Ui.column(c)

    /** The dialog's card, for screenshots. */
    val content: View

    init {
        val card = Ui.column(c).apply {
            background = Ui.rounded(c, Ui.SURFACE, 28, 2, Ui.LINE)
            if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 32))
            else setPadding(Ui.px(c, 56), Ui.px(c, 48), Ui.px(c, 56), Ui.px(c, 40))
        }
        card.addView(Ui.sectionLabel(c, "Start for everyone on"))
        card.addView(Ui.text(c, title, if (phone) 40 else 48, 800, spacingEm = -0.02f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(MATCH).apply { topMargin = Ui.px(c, 14) })
        if (sub.isNotBlank()) card.addView(Ui.oneLine(Ui.text(c, sub, 26, 500, Ui.TEXT_2)), lp(MATCH).apply { topMargin = Ui.px(c, 8) })
        card.addView(hint, lp(MATCH).apply { topMargin = Ui.px(c, 24) })

        // many services scroll inside the card; the frame clips rows scrolled out of it
        list.setPadding(Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12))
        val scroll = MaxHeightScroll(c, c.resources.displayMetrics.heightPixels * (if (phone) 55 else 50) / 100).apply {
            Ui.noClip(this)
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        card.addView(FrameLayout(c).apply { addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP)) },
            lp(MATCH).apply {
                topMargin = Ui.px(c, 12)
                leftMargin = -Ui.px(c, 12)
                rightMargin = -Ui.px(c, 12)
            })
        content = card

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.72f)
        }
    }

    val isShowing get() = dialog.isShowing

    fun show() {
        dialog.show()
        val width = if (phone) c.resources.displayMetrics.widthPixels - Ui.px(c, 64) else Ui.px(c, 960)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    fun hint(text: String, warn: Boolean = false) {
        hint.text = text
        hint.setTextColor(if (warn) Ui.WARN else Ui.FAINT)
        hint.visibility = View.VISIBLE
    }

    fun hideHint() {
        hint.visibility = View.GONE
    }

    /** The service cards; the first gets focus. */
    fun setServices(cards: List<View>) {
        list.removeAllViews()
        for (card in cards) list.addView(card, lp(MATCH, WRAP).apply { if (list.childCount > 0) topMargin = Ui.px(c, 12) })
        cards.firstOrNull()?.requestFocus()
    }
}

/** A ScrollView no taller than [maxHeight] px: short content wraps, long content scrolls. */
class MaxHeightScroll(context: android.content.Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}
