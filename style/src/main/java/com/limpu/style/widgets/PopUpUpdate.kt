package com.limpu.style.widgets

import android.content.DialogInterface
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.limpu.style.databinding.DialogBottomUpdateBinding

/**
 * 圆角的文本框底部弹窗
 */
class PopUpUpdate : TransparentBottomSheetDialog<DialogBottomUpdateBinding>() {


    @StringRes
    var init_title: Int? = null
    private var init_text: CharSequence? = null
    var onActionListener: OnActionListener? = null
    private var actionHandled = false
    private var skipButtonVisible = true

    interface OnActionListener {
        fun onConfirm()
        fun onCancel()
        fun onSkip()
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = (dialog as? BottomSheetDialog)
            ?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val maxHeight = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = maxHeight }
        bottomSheet.requestLayout()
        BottomSheetBehavior.from(bottomSheet).apply {
            peekHeight = maxHeight
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun setTitle(@StringRes title: Int): PopUpUpdate {
        init_title = title
        return this
    }

    fun setDialogCancelable(cancelable: Boolean): PopUpUpdate {
        isCancelable = cancelable
        return this
    }

    fun setText(text: CharSequence?): PopUpUpdate {
        init_text = text
        return this
    }

    fun setShowSkipButton(show: Boolean): PopUpUpdate {
        skipButtonVisible = show
        return this
    }

    fun setOnActionListener(lis: OnActionListener): PopUpUpdate {
        this.onActionListener = lis
        return this
    }


    override fun initViews(v: View) {
        if (init_title != null) {
            binding.title.setText(init_title!!)
        }
        if (!TextUtils.isEmpty(init_text)) {
            binding.text.text = init_text
            binding.text.movementMethod = LinkMovementMethod.getInstance()
            binding.text.visibility = View.VISIBLE
        } else {
            binding.text.movementMethod = null
            binding.text.visibility = View.GONE
        }
        if (isCancelable) {
            binding.cancel.visibility = View.VISIBLE
        } else {
            binding.cancel.visibility = View.GONE
        }
        binding.skip.visibility = if (skipButtonVisible) View.VISIBLE else View.GONE
        binding.skip.setOnClickListener {
            actionHandled = true
            onActionListener?.onSkip()
            dismiss()
        }
        binding.cancel.setOnClickListener {
            actionHandled = true
            onActionListener?.onCancel()
            dismiss() }
        binding.confirm.setOnClickListener {

                actionHandled = true
                onActionListener?.onConfirm()

            dismiss()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        actionHandled = true
        onActionListener?.onCancel()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!actionHandled) {
            actionHandled = true
            onActionListener?.onCancel()
        }
        super.onDismiss(dialog)
    }

    override fun createViewBinding(inflater: LayoutInflater, container: ViewGroup?): DialogBottomUpdateBinding {
        return DialogBottomUpdateBinding.inflate(inflater, container, false)
    }
}
