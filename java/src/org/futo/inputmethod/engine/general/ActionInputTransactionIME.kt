package org.futo.inputmethod.engine.general

import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.InputConnectionInternalComposingWrapper
import org.futo.inputmethod.latin.SupportsNonComposing
import org.futo.inputmethod.latin.VoiceInputAlternativeIC
import org.futo.inputmethod.latin.VoiceInputAlternativeICComposing
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.uix.ActionInputTransaction
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.utils.TextContext
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2

class ActionInputTransactionIME(val helper: IMEHelper) : IMEInterface, ActionInputTransaction {
    val useComposingMode = run {
        val inputType = helper.getCurrentEditorInfo()?.inputType ?: 0
        val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
        inputClass == EditorInfo.TYPE_CLASS_TEXT
    }

    val ic = if(helper.context.getSetting(VoiceInputAlternativeIC) && SupportsNonComposing && useComposingMode) {
        InputConnectionInternalComposingWrapper(
            helper.context.getSetting(VoiceInputAlternativeICComposing),
            true,
            helper.getCurrentInputConnection())
    } else {
        helper.getCurrentInputConnection()
    }

    private var currentSelStart: Int = -1
    private var currentSelEnd: Int = -1
    private var committedLength: Int = 0

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDeviceUnlocked() {}
    override fun onStartInput() {
        currentSelStart = helper.getCurrentEditorInfo()?.initialSelStart ?: -1
        currentSelEnd = helper.getCurrentEditorInfo()?.initialSelEnd ?: -1
    }
    override fun onOrientationChanged() {}
    override fun onFinishInput() {}
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingSpanStart: Int,
        composingSpanEnd: Int
    ) {
        currentSelStart = newSelStart
        currentSelEnd = newSelEnd
        if(ic is InputConnectionInternalComposingWrapper) {
            if(!ic.mightBeBelated(oldSelStart, oldSelEnd, newSelStart, newSelEnd)) {
                ic.cursorUpdated(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
            }
        }
    }

    override fun isGestureHandlingAvailable(): Boolean = false
    override fun onEvent(event: Event) {}
    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}
    override fun onCancelInput() {}
    override fun onFinishSlidingInput() {}
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {}
    override fun onMoveDeletePointer(steps: Int) {}
    override fun onUpWithDeletePointerActive() {}
    override fun onUpWithPointerActive() {}
    override fun onSwipeLanguage(direction: Int) {}
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}
    override fun clearUserHistoryDictionaries() {}
    override fun requestSuggestionRefresh() {}
    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) { }

    override val textContext: TextContext
        get() = TextContext(
            beforeCursor = ic?.getTextBeforeCursor(Constants.VOICE_INPUT_CONTEXT_SIZE, 0),
            afterCursor = ic?.getTextAfterCursor(Constants.VOICE_INPUT_CONTEXT_SIZE, 0),
            selectedText = ic?.getSelectedText(0)
        )

    private var isFinished = false
    private var partialText = ""
    override fun updatePartial(text: String) {
        if (isFinished || !useComposingMode) return
        helper.requestCursorUpdate()
        partialText = text
        ic?.setComposingText(
            partialText,
            1
        )

        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    override fun commit(text: String) {
        if (isFinished) return
        helper.requestCursorUpdate()
        isFinished = true
        ic?.commitText(
            text,
            1
        )
        helper.endInputTransaction(this)
        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    override fun commitAndSelect(text: String) {
        if (isFinished) return
        helper.requestCursorUpdate()
        partialText = ""
        committedLength = text.length

        // Capture starting position before commit
        val start = if (currentSelStart >= 0) currentSelStart else (helper.getCurrentEditorInfo()?.initialSelStart ?: -1)
        ic?.commitText(text, 1)

        // Select the newly committed text range so user sees what was just typed
        if (start >= 0 && text.isNotEmpty()) {
            ic?.setSelection(start, start + text.length)
        } else if (text.isNotEmpty()) {
            helper.requestCursorUpdate()
            val end = if (currentSelEnd >= 0) currentSelEnd else (start + text.length)
            if (end >= text.length) {
                ic?.setSelection(end - text.length, end)
            }
        }
        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    override fun replaceSelection(text: String) {
        if (isFinished) return
        helper.requestCursorUpdate()
        isFinished = true
        
        // Committing text replaces whatever is selected in standard Android InputConnection
        ic?.commitText(text, 1)
        helper.endInputTransaction(this)
        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    override fun replaceInContext(target: String, replacement: String): Boolean {
        if (isFinished) return false
        helper.requestCursorUpdate()

        // Clear any active composing text before modifying surrounding text
        if (partialText.isNotEmpty()) {
            ic?.finishComposingText()
            partialText = ""
        }

        // 1. If text is currently selected, replace the selection
        val selected = ic?.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            if (target.isEmpty() || selected.contains(target, ignoreCase = true) || target.contains(selected, ignoreCase = true)) {
                isFinished = true
                ic?.commitText(replacement, 1)
                helper.endInputTransaction(this)
                (ic as? InputConnectionInternalComposingWrapper)?.send()
                return true
            }
        }

        // 2. Otherwise search backwards in beforeCursor
        val before = ic?.getTextBeforeCursor(Constants.VOICE_INPUT_CONTEXT_SIZE, 0)?.toString()
        if (!before.isNullOrEmpty() && target.isNotEmpty()) {
            val escaped = java.util.regex.Pattern.quote(target)
            val regex = Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE)
            val match = regex.findAll(before).lastOrNull()
            val startIdx = match?.range?.first ?: before.lastIndexOf(target, ignoreCase = true)
            val matchLen = if (match != null) (match.range.last - match.range.first + 1) else target.length

            if (startIdx >= 0) {
                isFinished = true
                val newBefore = before.substring(0, startIdx) + replacement + before.substring(startIdx + matchLen)
                ic?.deleteSurroundingText(before.length, 0)
                ic?.commitText(newBefore, 1)
                helper.endInputTransaction(this)
                (ic as? InputConnectionInternalComposingWrapper)?.send()
                return true
            }
        }
        return false
    }

    override fun cancel() {
        helper.requestCursorUpdate()
        commit(partialText)
        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    fun ensureFinished() {
        isFinished = true
    }
}