package dev.watchparty.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow

/**
 * A dropdown's list (Browse's status and order): the options in a small
 * pop-up just under the button that opened it, about as wide as the
 * options (not the screen), the current one highlighted and focused, so a
 * remote picks one with Up / Down and OK, and a phone with a tap. Back or a
 * tap outside closes it unchanged.
 */
class ChoiceDialog(
    private val c: Activity,
    options: List<Option>,
    private val onPick: (String) -> Unit,
) {
    /** [note]: e.g. how many titles; [chosen]: the current one. */
    data class Option(val key: String, val label: String, val note: String? = null, val chosen: Boolean = false)

    private val phone = Ui.isPhone(c)
    private var focus: View? = null

    /** The list's card, for screenshots. */
    val content: View

    val popup: PopupWindow

    init {
        val card = Ui.column(c).apply {
            background = Ui.rounded(c, Ui.SURFACE, 22, 2, Ui.LINE)
            val p = Ui.px(c, 14)
            setPadding(p, p, p, p)
        }
        for (o in options) {
            val row = Ui.listButton(c, o.label, o.note) {
                dismiss()
                if (!o.chosen) onPick(o.key)
            }
            row.setChosen(o.chosen)
            if (o.chosen || focus == null) focus = row
            card.addView(row, lp(MATCH, Ui.tap(c, 64)).apply { if (card.childCount > 0) topMargin = Ui.px(c, 6) })
        }
        val scroll = MaxHeightScroll(c, c.resources.displayMetrics.heightPixels * 60 / 100).apply {
            Ui.noClip(this)
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        content = card
        popup = PopupWindow(scroll, Ui.px(c, if (phone) 440 else 480), WRAP, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = Ui.pxf(c, 24)
        }
    }

    /** Opens under [anchor], left edges lined up (Android moves it up if there's no room below). */
    fun show(anchor: View) {
        last = this
        popup.showAsDropDown(anchor, 0, Ui.px(c, 10))
        focus?.post { focus?.requestFocus() }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    companion object {
        /** The last one opened (for screenshots). */
        var last: ChoiceDialog? = null
            private set
    }
}
