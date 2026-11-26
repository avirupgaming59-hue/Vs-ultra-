package com.example.workspace

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Custom WebView subclass that preserves native touch/keyboard/IME behavior while adding:
 * 1. Reliable physical mouse wheel scrolling inside nested Compose hosts (`requestDisallowInterceptTouchEvent`).
 * 2. Scoped `Ctrl + Mouse Wheel` zooming when permitted by foreground interaction gate.
 * 3. Scoped hardware keyboard shortcut interception (`Ctrl+Plus`/`Ctrl+Equals`/`Ctrl+Minus`/`Ctrl+0`)
 *    without blocking Bengali IME, Unicode characters, AltGr, or standard editor/terminal keys.
 */
class WorkspaceWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    var onScopedKeyShortcut: ((KeyEvent) -> Boolean)? = null
    var onWheelZoom: ((zoomIn: Boolean) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && onScopedKeyShortcut?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null &&
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)

            val ctrlOnly = (event.metaState and KeyEvent.META_CTRL_ON) != 0 &&
                (event.metaState and KeyEvent.META_ALT_ON) == 0 &&
                (event.metaState and KeyEvent.META_META_ON) == 0
            if (ctrlOnly && abs(vScroll) > 0.01f) {
                if (onWheelZoom?.invoke(vScroll > 0f) == true) {
                    return true
                }
            }

            val handledByWebView = super.onGenericMotionEvent(event)
            if (handledByWebView) {
                return true
            }

            // Fallback scroll when inner web page does not report consumed wheel motion
            val dx = (-hScroll * 64f).roundToInt()
            val dy = (-vScroll * 64f).roundToInt()
            if (dx != 0 || dy != 0) {
                val maxY = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
                val maxX = (computeHorizontalScrollRange() - computeHorizontalScrollExtent()).coerceAtLeast(0)
                val targetX = (scrollX + dx).coerceIn(0, maxX)
                val targetY = (scrollY + dy).coerceIn(0, maxY)
                if (targetX != scrollX || targetY != scrollY) {
                    scrollTo(targetX, targetY)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
