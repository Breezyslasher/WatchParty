package dev.watchparty.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout

/**
 * A dropdown's list (Browse's status and order): the options in a small
 * dialog, the current one highlighted and focused, so a remote picks one
 * with Up / Down and OK, and a phone with a tap. Back or a tap outside
 * closes it unchanged.
 */
class ChoiceDialog(
    private val c: Activity,
    title: String,
    options: List<Option>,
    private val onPick: (String) -> Unit,
) {
    /** [note]: e.g. how many titles; [chosen]: the current one. */
    data class Option(val key: String, val label: String, val note: String? = null, val chosen: Boolean = false)

    private val phone = Ui.isPhone(c)
    val dialog = Dialog(c, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)

    /** The dialog's card, for screenshots. */
    val content: View

    init {
        val card = Ui.column(c).apply {
            background = Ui.rounded(c, Ui.SURFACE, 28, 2, Ui.LINE)
            if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 36), Ui.px(c, 40), Ui.px(c, 32))
            else setPadding(Ui.px(c, 48), Ui.px(c, 40), Ui.px(c, 48), Ui.px(c, 36))
        }
        card.addView(Ui.sectionLabel(c, title))
        val list = Ui.column(c).apply { setPadding(Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12)) }
        var focus: View? = null
        for (o in options) {
            val row = Ui.listButton(c, o.label, o.note) {
                dismiss()
                if (!o.chosen) onPick(o.key)
            }
            row.setChosen(o.chosen)
            if (o.chosen || focus == null) focus = row
            list.addView(row, lp(MATCH, Ui.tap(c, 68)).apply { if (list.childCount > 0) topMargin = Ui.px(c, 10) })
        }
        val scroll = MaxHeightScroll(c, c.resources.displayMetrics.heightPixels * 60 / 100).apply {
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
        dialog.setOnShowListener { focus?.requestFocus() }
    }

    fun show() {
        dialog.show()
        val width = if (phone) c.resources.displayMetrics.widthPixels - Ui.px(c, 96) else Ui.px(c, 640)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}
