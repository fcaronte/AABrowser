package com.fcaronte.aabrowser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class CarFrameLayout : FrameLayout {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (visibility != VISIBLE) return
        super.dispatchWindowVisibilityChanged(visibility)
    }

    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility != VISIBLE) return
        super.dispatchVisibilityChanged(changedView!!, visibility)
    }

    companion object {
        private const val TAG = "CarFrameLayout"
    }
}
