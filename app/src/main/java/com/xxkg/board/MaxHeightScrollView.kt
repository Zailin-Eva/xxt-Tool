package com.xxkg.board

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** 详情浮层的滚动区，避免课程段太多时顶到状态栏。 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (maxHeight > 0 && measuredHeight > maxHeight) {
            setMeasuredDimension(measuredWidth, maxHeight)
        }
    }
}
