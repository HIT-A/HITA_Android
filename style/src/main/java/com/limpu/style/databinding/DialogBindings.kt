package com.limpu.style.databinding

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.limpu.style.R
import com.limpu.style.widgets.MWheel3DView

class DialogBottomTextBinding constructor(
    private val rootView: LinearLayout,
    val title: TextView,
    val text: TextView,
    val cancel: CardView,
    val confirm: CardView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomTextBinding {
            return DialogUiFactory.createText(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomTextBinding = DialogUiFactory.bindText(view)
    }
}

class DialogBottomUpdateBinding constructor(
    private val rootView: LinearLayout,
    val title: TextView,
    val text: TextView,
    val cancel: CardView,
    val skip: CardView,
    val confirm: CardView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomUpdateBinding {
            return DialogUiFactory.createUpdate(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomUpdateBinding = DialogUiFactory.bindUpdate(view)
    }
}

class DialogBottomCheckableListBinding constructor(
    private val rootView: LinearLayout,
    val title: TextView,
    val list: RecyclerView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomCheckableListBinding {
            return DialogUiFactory.createCheckableList(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomCheckableListBinding = DialogUiFactory.bindCheckableList(view)
    }
}

class DialogBottomCheckableListItemBinding constructor(
    private val rootView: LinearLayout,
    val item: LinearLayout,
    val icon: ImageView,
    val text: TextView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomCheckableListItemBinding {
            return DialogUiFactory.createCheckableListItem(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomCheckableListItemBinding = DialogUiFactory.bindCheckableListItem(view)
    }
}

class DialogBottomSelectableListBinding constructor(
    private val rootView: View,
    val title: TextView,
    val cancel: CardView,
    val confirm: CardView,
    val list: RecyclerView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomSelectableListBinding {
            return DialogUiFactory.createSelectableList(inflater.context, asDialog = false).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun inflateDialog(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomSelectableListBinding {
            return DialogUiFactory.createSelectableList(inflater.context, asDialog = true).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomSelectableListBinding = DialogUiFactory.bindSelectableList(view)
    }
}

class DialogSelectableListBinding constructor(
    private val binding: DialogBottomSelectableListBinding
) : ViewBinding {
    val title: TextView get() = binding.title
    val cancel: CardView get() = binding.cancel
    val confirm: CardView get() = binding.confirm
    val list: RecyclerView get() = binding.list
    override fun getRoot(): View = binding.root

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogSelectableListBinding {
            return DialogSelectableListBinding(
                DialogBottomSelectableListBinding.inflateDialog(inflater, parent, attachToParent)
            )
        }

        fun bind(view: View): DialogSelectableListBinding {
            return DialogSelectableListBinding(DialogBottomSelectableListBinding.bind(view))
        }
    }
}

class DialogBottomSelectableListItemBinding constructor(
    private val rootView: LinearLayout,
    val item: LinearLayout,
    val icon: ImageView,
    val text: TextView,
    val selected: ImageView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomSelectableListItemBinding {
            return DialogUiFactory.createSelectableListItem(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomSelectableListItemBinding = DialogUiFactory.bindSelectableListItem(view)
    }
}

class DialogBottomEditTextBinding constructor(
    private val rootView: LinearLayout,
    val title: TextView,
    val cancel: CardView,
    val confirm: CardView,
    val text: EditText,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomEditTextBinding {
            return DialogUiFactory.createEditText(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomEditTextBinding = DialogUiFactory.bindEditText(view)
    }
}

class DialogBottomAutoEditTextBinding constructor(
    private val rootView: FrameLayout,
    val title: TextView,
    val cancel: CardView,
    val confirm: CardView,
    val list: RecyclerView,
    val text: EditText,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomAutoEditTextBinding {
            return DialogUiFactory.createAutoEditText(inflater.context, asDialog = false).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomAutoEditTextBinding = DialogUiFactory.bindAutoEditText(view)
    }
}

class DialogAutoEditTextBinding constructor(
    private val binding: DialogBottomAutoEditTextBinding
) : ViewBinding {
    val title: TextView get() = binding.title
    val cancel: CardView get() = binding.cancel
    val confirm: CardView get() = binding.confirm
    val list: RecyclerView get() = binding.list
    val text: EditText get() = binding.text
    override fun getRoot(): View = binding.root

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogAutoEditTextBinding {
            return DialogAutoEditTextBinding(
                DialogUiFactory.createAutoEditText(inflater.context, asDialog = true).also {
                    if (attachToParent) parent?.addView(it.root)
                }
            )
        }

        fun bind(view: View): DialogAutoEditTextBinding {
            return DialogAutoEditTextBinding(DialogBottomAutoEditTextBinding.bind(view))
        }
    }
}

class DialogBottomFloatPickerBinding constructor(
    private val rootView: LinearLayout,
    val title: TextView,
    val cancel: CardView,
    val confirm: CardView,
    val pickTimeLayout: LinearLayout,
    val a: MWheel3DView,
    val b: MWheel3DView,
) : ViewBinding {
    override fun getRoot(): View = rootView

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): DialogBottomFloatPickerBinding {
            return DialogUiFactory.createFloatPicker(inflater.context).also {
                if (attachToParent) parent?.addView(it.root)
            }
        }

        fun bind(view: View): DialogBottomFloatPickerBinding = DialogUiFactory.bindFloatPicker(view)
    }
}

private object DialogUiFactory {
    private const val ID_TITLE = 0x1f010001
    private const val ID_TEXT = 0x1f010002
    private const val ID_CANCEL = 0x1f010003
    private const val ID_CONFIRM = 0x1f010004
    private const val ID_LIST = 0x1f010005
    private const val ID_ITEM = 0x1f010006
    private const val ID_ICON = 0x1f010007
    private const val ID_SELECTED = 0x1f010008
    private const val ID_SKIP = 0x1f010009
    private const val ID_PICK_TIME_LAYOUT = 0x1f01000a
    private const val ID_A = 0x1f01000b
    private const val ID_B = 0x1f01000c

    fun createText(context: Context): DialogBottomTextBinding {
        val root = bottomSheetRoot(context)
        val title = titleView(context).also { it.id = ID_TITLE }
        val text = TextView(context).apply {
            id = ID_TEXT
            hint = context.getString(R.string.input_hint)
            setTextColor(attrColor(context, R.attr.textColorPrimary))
            setHintTextColor(attrColor(context, R.attr.textColorSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
        }
        root.addView(title)
        root.addView(text)
        val buttons = horizontalButtons(context, ID_CANCEL to R.string.cancel, ID_CONFIRM to R.string.confirm)
        root.addView(buttons)
        return bindText(root)
    }

    fun bindText(view: View): DialogBottomTextBinding {
        return DialogBottomTextBinding(
            view as LinearLayout,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_TEXT),
            view.findViewById(ID_CANCEL),
            view.findViewById(ID_CONFIRM),
        )
    }

    fun createUpdate(context: Context): DialogBottomUpdateBinding {
        val root = bottomSheetRoot(context).apply {
            layoutParams = ViewGroup.LayoutParams(match, match)
        }
        val title = titleView(context).also { it.id = ID_TITLE }
        val text = TextView(context).apply {
            id = ID_TEXT
            hint = context.getString(R.string.input_hint)
            setTextColor(attrColor(context, R.attr.textColorPrimary))
            setHintTextColor(attrColor(context, R.attr.textColorSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
        }
        val textScroller = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(match, 0, 1f).apply {
                topMargin = -dp(context, 16)
            }
            addView(text, ViewGroup.LayoutParams(match, wrap))
        }
        root.addView(title)
        root.addView(textScroller)
        root.addView(horizontalButtons(context, ID_CANCEL to R.string.cancel, ID_SKIP to R.string.skip, ID_CONFIRM to R.string.confirm))
        return bindUpdate(root)
    }

    fun bindUpdate(view: View): DialogBottomUpdateBinding {
        return DialogBottomUpdateBinding(
            view as LinearLayout,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_TEXT),
            view.findViewById(ID_CANCEL),
            view.findViewById(ID_SKIP),
            view.findViewById(ID_CONFIRM),
        )
    }

    fun createCheckableList(context: Context): DialogBottomCheckableListBinding {
        val root = bottomSheetRoot(context)
        root.addView(titleView(context).also { it.id = ID_TITLE })
        root.addView(RecyclerView(context).apply {
            id = ID_LIST
            layoutParams = LinearLayout.LayoutParams(match, match).apply { bottomMargin = dp(context, 16) }
        })
        return bindCheckableList(root)
    }

    fun bindCheckableList(view: View): DialogBottomCheckableListBinding {
        return DialogBottomCheckableListBinding(
            view as LinearLayout,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_LIST),
        )
    }

    fun createCheckableListItem(context: Context): DialogBottomCheckableListItemBinding {
        val root = listItemRoot(context)
        val icon = ImageView(context).apply {
            id = ID_ICON
            visibility = View.GONE
            setImageResource(R.drawable.element_round_grey)
            layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val text = itemText(context)
        root.addView(icon)
        root.addView(text)
        return bindCheckableListItem(root)
    }

    fun bindCheckableListItem(view: View): DialogBottomCheckableListItemBinding {
        return DialogBottomCheckableListItemBinding(
            view as LinearLayout,
            view,
            view.findViewById(ID_ICON),
            view.findViewById(ID_TEXT),
        )
    }

    fun createSelectableList(context: Context, asDialog: Boolean): DialogBottomSelectableListBinding {
        val outer: ViewGroup
        val content: LinearLayout
        if (asDialog) {
            outer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
                layoutParams = ViewGroup.LayoutParams(match, wrap)
            }
            content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.dialog_rounded_background)
                layoutParams = LinearLayout.LayoutParams(match, wrap)
            }
            outer.addView(content)
        } else {
            content = bottomSheetRoot(context)
            outer = content
        }
        content.addView(header(context, hideCancel = asDialog))
        content.addView(RecyclerView(context).apply {
            id = ID_LIST
            layoutParams = LinearLayout.LayoutParams(match, match).apply { bottomMargin = dp(context, 16) }
        })
        return bindSelectableList(outer)
    }

    fun bindSelectableList(view: View): DialogBottomSelectableListBinding {
        return DialogBottomSelectableListBinding(
            view,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_CANCEL),
            view.findViewById(ID_CONFIRM),
            view.findViewById(ID_LIST),
        )
    }

    fun createSelectableListItem(context: Context): DialogBottomSelectableListItemBinding {
        val root = listItemRoot(context)
        val icon = ImageView(context).apply {
            id = ID_ICON
            visibility = View.GONE
            setImageResource(R.drawable.element_round_grey)
            layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val text = itemText(context)
        val selected = ImageView(context).apply {
            id = ID_SELECTED
            contentDescription = context.getString(R.string.select)
            imageTintList = ColorStateList.valueOf(attrColor(context, R.attr.colorPrimary))
            setImageResource(R.drawable.ic_baseline_check_24)
            layoutParams = LinearLayout.LayoutParams(wrap, wrap)
        }
        root.addView(icon)
        root.addView(text)
        root.addView(selected)
        return bindSelectableListItem(root)
    }

    fun bindSelectableListItem(view: View): DialogBottomSelectableListItemBinding {
        return DialogBottomSelectableListItemBinding(
            view as LinearLayout,
            view,
            view.findViewById(ID_ICON),
            view.findViewById(ID_TEXT),
            view.findViewById(ID_SELECTED),
        )
    }

    fun createEditText(context: Context): DialogBottomEditTextBinding {
        val root = bottomSheetRoot(context)
        root.addView(header(context))
        root.addView(editText(context).apply {
            id = ID_TEXT
            layoutParams = LinearLayout.LayoutParams(match, wrap)
        })
        return bindEditText(root)
    }

    fun bindEditText(view: View): DialogBottomEditTextBinding {
        return DialogBottomEditTextBinding(
            view as LinearLayout,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_CANCEL),
            view.findViewById(ID_CONFIRM),
            view.findViewById(ID_TEXT),
        )
    }

    fun createAutoEditText(context: Context, asDialog: Boolean): DialogBottomAutoEditTextBinding {
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(match, wrap)
            background = if (asDialog) ColorDrawable(Color.TRANSPARENT) else ContextCompat.getDrawable(context, R.drawable.bottom_sheet_rounded_background)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = if (asDialog) ContextCompat.getDrawable(context, R.drawable.dialog_rounded_background) else null
            layoutParams = FrameLayout.LayoutParams(match, wrap).apply {
                if (asDialog) setMargins(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            }
        }
        content.addView(header(context, hideCancel = asDialog))
        content.addView(RecyclerView(context).apply {
            id = ID_LIST
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply {
                bottomMargin = if (asDialog) 0 else dp(context, 72)
            }
        })
        root.addView(content)
        root.addView(editText(context).apply {
            id = ID_TEXT
            layoutParams = FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM).apply {
                if (asDialog) topMargin = -dp(context, 8)
            }
        })
        return bindAutoEditText(root)
    }

    fun bindAutoEditText(view: View): DialogBottomAutoEditTextBinding {
        return DialogBottomAutoEditTextBinding(
            view as FrameLayout,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_CANCEL),
            view.findViewById(ID_CONFIRM),
            view.findViewById(ID_LIST),
            view.findViewById(ID_TEXT),
        )
    }

    fun createFloatPicker(context: Context): DialogBottomFloatPickerBinding {
        val root = bottomSheetRoot(context).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(match, wrap)
        }
        content.addView(header(context, horizontalPadding = 8))
        val pickLayout = LinearLayout(context).apply {
            id = ID_PICK_TIME_LAYOUT
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(context, 24)
                bottomMargin = dp(context, 28)
            }
        }
        val selectedColor = attrColor(context, R.attr.colorAccent)
        val unselectedColor = attrColor(context, R.attr.backgroundIconColorBottom)
        val wheelSize = sp(context, 48f).toInt()
        val a = MWheel3DView(context).apply {
            id = ID_A
            setCyclic(true)
            setVisibleItems(2)
            setTextSize(wheelSize)
            setSelectedColor(selectedColor)
            setUnselectedColor(unselectedColor)
            layoutParams = LinearLayout.LayoutParams(dp(context, 64), wrap)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        val b = MWheel3DView(context).apply {
            id = ID_B
            alpha = 0.6f
            setCyclic(true)
            setVisibleItems(2)
            setTextSize(wheelSize)
            setSelectedColor(selectedColor)
            setUnselectedColor(unselectedColor)
            layoutParams = LinearLayout.LayoutParams(dp(context, 64), wrap)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        pickLayout.addView(a)
        pickLayout.addView(TextView(context).apply {
            text = "."
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(attrColor(context, R.attr.textColorSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply {
                gravity = Gravity.CENTER_VERTICAL
                topMargin = dp(context, 4)
            }
        })
        pickLayout.addView(b)
        content.addView(pickLayout)
        root.addView(content)
        return bindFloatPicker(root)
    }

    fun bindFloatPicker(view: View): DialogBottomFloatPickerBinding {
        return DialogBottomFloatPickerBinding(
            view as LinearLayout,
            view.findViewById(ID_TITLE),
            view.findViewById(ID_CANCEL),
            view.findViewById(ID_CONFIRM),
            view.findViewById(ID_PICK_TIME_LAYOUT),
            view.findViewById(ID_A),
            view.findViewById(ID_B),
        )
    }

    private fun bottomSheetRoot(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bottom_sheet_rounded_background)
            layoutParams = ViewGroup.LayoutParams(match, wrap)
        }
    }

    private fun titleView(context: Context): TextView {
        return TextView(context).apply {
            text = "TextView"
            setTextColor(attrColor(context, R.attr.textColorPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply {
                setMargins(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            }
        }
    }

    private fun header(context: Context, hideCancel: Boolean = false, horizontalPadding: Int = 0): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, horizontalPadding), 0, dp(context, horizontalPadding), 0)
            layoutParams = LinearLayout.LayoutParams(match, wrap)
            addView(titleView(context).also {
                it.id = ID_TITLE
                it.layoutParams = LinearLayout.LayoutParams(0, wrap, 1f).apply {
                    setMargins(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
                }
            })
            addView(smallButton(context, ID_CANCEL, R.string.cancel, primary = false).apply {
                if (hideCancel) visibility = View.GONE
            })
            addView(smallButton(context, ID_CONFIRM, R.string.confirm, primary = true).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(context, 16)
            })
        }
    }

    private fun horizontalButtons(context: Context, vararg specs: Pair<Int, Int>): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(context, 16) }
            specs.forEach { (id, label) ->
                addView(largeButton(context, id, label, primary = id == ID_CONFIRM))
            }
        }
    }

    private fun smallButton(context: Context, id: Int, label: Int, primary: Boolean): CardView {
        return buttonCard(context, id, 16, primary).apply {
            layoutParams = LinearLayout.LayoutParams(wrap, dp(context, 32)).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(context, 8)
            }
            addView(buttonText(context, label, primary, 16f).apply {
                setPadding(dp(context, 16), 0, dp(context, 16), 0)
            })
        }
    }

    private fun largeButton(context: Context, id: Int, label: Int, primary: Boolean): CardView {
        return buttonCard(context, id, 24, primary).apply {
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f).apply {
                setMargins(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            }
            addView(buttonText(context, label, primary, 18f))
        }
    }

    private fun buttonCard(context: Context, id: Int, radius: Int, primary: Boolean): CardView {
        return CardView(context).apply {
            this.id = id
            isClickable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground = selectableBorderless(context)
            }
            this.radius = dp(context, radius).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(attrColor(context, if (primary) R.attr.colorPrimary else R.attr.colorControlDisabled))
        }
    }

    private fun buttonText(context: Context, label: Int, primary: Boolean, sizeSp: Float): TextView {
        return TextView(context).apply {
            text = context.getString(label)
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else attrColor(context, R.attr.textColorSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER)
        }
    }

    private fun listItemRoot(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            id = ID_ITEM
            orientation = LinearLayout.HORIZONTAL
            val feedback = selectable(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground = feedback
            } else {
                background = feedback
            }
            setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12))
            layoutParams = ViewGroup.LayoutParams(match, wrap)
        }
    }

    private fun itemText(context: Context): TextView {
        return TextView(context).apply {
            id = ID_TEXT
            text = "男"
            setTextColor(attrColor(context, R.attr.textColorPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
        }
    }

    private fun editText(context: Context): EditText {
        return EditText(context).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            hint = context.getString(R.string.input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setHintTextColor(attrColor(context, R.attr.textColorSecondary))
            setTextColor(attrColor(context, R.attr.textColorPrimary))
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            setEms(10)
        }
    }

    private fun selectable(context: Context): android.graphics.drawable.Drawable? {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return ContextCompat.getDrawable(context, out.resourceId)
    }

    private fun selectableBorderless(context: Context): android.graphics.drawable.Drawable? {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return ContextCompat.getDrawable(context, out.resourceId)
    }

    private fun attrColor(context: Context, attr: Int): Int {
        val out = TypedValue()
        return if (context.theme.resolveAttribute(attr, out, true)) out.data else Color.TRANSPARENT
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun sp(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics
        )
    }

    private const val match = ViewGroup.LayoutParams.MATCH_PARENT
    private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
}
