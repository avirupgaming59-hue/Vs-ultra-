package com.example.workspace

import android.view.KeyEvent

enum class WorkspaceKeyAction {
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_RESET,
    NONE,
}

/**
 * Pure, testable hardware-keyboard shortcut policy.
 *
 * Design guarantees:
 * 1. Normal English, Bengali, and Unicode IME/AltGr input is NEVER intercepted.
 * 2. Ctrl+Plus, Ctrl+Equals, Ctrl+Minus, and Ctrl+0 zoom shortcuts ONLY apply when the
 *    foreground WebView Workspace is actively focused and unobstructed by overlays/text fields.
 * 3. Standard shortcuts (Ctrl+C/V/X/A, Enter, Tab, Arrow keys, Backspace, Delete) pass untouched.
 */
object WorkspaceKeyboardShortcuts {

    const val MIN_ZOOM_PERCENT = 50
    const val MAX_ZOOM_PERCENT = 250
    const val DEFAULT_ZOOM_PERCENT = 100
    const val ZOOM_STEP_PERCENT = 10

    fun clampZoomPercent(percent: Int): Int =
        percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)

    fun nextZoomInPercent(currentPercent: Int): Int =
        clampZoomPercent(currentPercent + ZOOM_STEP_PERCENT)

    fun nextZoomOutPercent(currentPercent: Int): Int =
        clampZoomPercent(currentPercent - ZOOM_STEP_PERCENT)

    /**
     * Resolves a physical keyboard event into a scoped WebView zoom action, or [WorkspaceKeyAction.NONE]
     * if the event must pass straight through to IME / Compose / WebView unchanged.
     */
    fun resolveScopedWebViewKeyAction(
        keyCode: Int,
        isKeyDown: Boolean,
        repeatCount: Int,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isMetaPressed: Boolean,
        isWorkspaceForegroundFocused: Boolean,
        isComposeTextFieldFocused: Boolean,
    ): WorkspaceKeyAction {
        if (!isWorkspaceForegroundFocused || isComposeTextFieldFocused) {
            return WorkspaceKeyAction.NONE
        }
        if (!isKeyDown || repeatCount != 0) {
            return WorkspaceKeyAction.NONE
        }
        // Require Ctrl without Alt (AltGr is used by Bengali & international keyboards) or Meta.
        if (!isCtrlPressed || isAltPressed || isMetaPressed) {
            return WorkspaceKeyAction.NONE
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_NUMPAD_ADD,
            KeyEvent.KEYCODE_EQUALS,
            -> WorkspaceKeyAction.ZOOM_IN

            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
            -> WorkspaceKeyAction.ZOOM_OUT

            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0,
            -> WorkspaceKeyAction.ZOOM_RESET

            else -> WorkspaceKeyAction.NONE
        }
    }

    /**
     * Returns true when pressing Enter on a hardware keyboard should submit (e.g. AI chat or terminal),
     * while Shift+Enter inserts a newline and modifier/repeat/IME events pass through untouched.
     */
    fun shouldSubmitOnEnterKey(
        keyCode: Int,
        isKeyDown: Boolean,
        repeatCount: Int,
        isShiftPressed: Boolean,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isMetaPressed: Boolean,
    ): Boolean {
        if (!isKeyDown || repeatCount != 0) return false
        if (isShiftPressed || isCtrlPressed || isAltPressed || isMetaPressed) return false
        return keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }
}
