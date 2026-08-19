package com.fcaronte.aabrowser

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.core.view.NestedScrollingChild2
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat

open class NestedScrollWebView : WebView, NestedScrollingChild2 {
    private var m_NestedScrollingChildHelper: NestedScrollingChildHelper? = null
    private var m_LastMotionY = 0

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                m_LastMotionY = event.y.toInt()
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
            }

            MotionEvent.ACTION_MOVE -> {
                var deltaY = m_LastMotionY - event.y.toInt()
                if (deltaY != 0) {
                    val scrollConsumed = IntArray(2)
                    val scrollOffset = IntArray(2)
                    if (dispatchNestedPreScroll(
                            0,
                            deltaY,
                            scrollConsumed,
                            scrollOffset
                        )
                    ) deltaY -= scrollConsumed[1]

                    if (deltaY != 0) {
                        dispatchNestedScroll(0, deltaY, 0, 0, scrollOffset)
                        m_LastMotionY -= deltaY
                    }
                }
            }

            else -> stopNestedScroll()
        }

        return super.onTouchEvent(event)
    }

    private fun init() {
        m_NestedScrollingChildHelper = NestedScrollingChildHelper(this)
        isNestedScrollingEnabled = true
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        m_NestedScrollingChildHelper!!.setNestedScrollingEnabled(enabled)
    }

    override fun isNestedScrollingEnabled(): Boolean {
        return m_NestedScrollingChildHelper!!.isNestedScrollingEnabled
    }

    override fun hasNestedScrollingParent(): Boolean {
        return m_NestedScrollingChildHelper!!.hasNestedScrollingParent()
    }

    override fun hasNestedScrollingParent(type: Int): Boolean {
        return m_NestedScrollingChildHelper!!.hasNestedScrollingParent(type)
    }

    override fun startNestedScroll(axes: Int): Boolean {
        return m_NestedScrollingChildHelper!!.startNestedScroll(axes)
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean {
        return m_NestedScrollingChildHelper!!.startNestedScroll(axes, type)
    }

    override fun stopNestedScroll() {
        m_NestedScrollingChildHelper!!.stopNestedScroll()
    }

    override fun stopNestedScroll(type: Int) {
        m_NestedScrollingChildHelper!!.stopNestedScroll(type)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean {
        return m_NestedScrollingChildHelper!!.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow
        )
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return m_NestedScrollingChildHelper!!.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow,
            type
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?
    ): Boolean {
        return m_NestedScrollingChildHelper!!.dispatchNestedPreScroll(
            dx,
            dy,
            consumed,
            offsetInWindow
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return m_NestedScrollingChildHelper!!.dispatchNestedPreScroll(
            dx,
            dy,
            consumed,
            offsetInWindow,
            type
        )
    }

    override fun dispatchNestedFling(
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        return m_NestedScrollingChildHelper!!.dispatchNestedFling(velocityX, velocityY, consumed)
    }

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean {
        return m_NestedScrollingChildHelper!!.dispatchNestedPreFling(velocityX, velocityY)
    }

    public override fun onDetachedFromWindow() {
        m_NestedScrollingChildHelper!!.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onStopNestedScroll(child: View) {
        m_NestedScrollingChildHelper!!.onStopNestedScroll(child)
    }

    companion object {
        private const val TAG = "NestedScrollWebView"
    }
}
