package com.fcaronte.aabrowser

import com.fcaronte.aabrowser.utils.AppLog
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

class CarInputConnection internal constructor(
    private val mCarinputmanager: CarInputManager?,
    private val mInputconnection: InputConnection
) : InputConnection {

    private var mLastcommittime: Long = 0
    private var mLastcommittedtext: String = ""
    private val debouncingDelay = 100L // ms
    private val minInterCharacterDelay = 30L // ms

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val managerText = mCarinputmanager?.getCurrentText() ?: ""
        if (managerText.isNotEmpty()) {
            val start = mCarinputmanager?.getSelectionStart() ?: 0
            return managerText.substring(0.coerceAtLeast(start - n), start)
        }
        return mInputconnection.getTextBeforeCursor(n, flags)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val managerText = mCarinputmanager?.getCurrentText() ?: ""
        if (managerText.isNotEmpty()) {
            val end = mCarinputmanager?.getSelectionEnd() ?: 0
            return managerText.substring(end, (end + n).coerceAtMost(managerText.length))
        }
        return mInputconnection.getTextAfterCursor(n, flags)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val managerText = mCarinputmanager?.getCurrentText() ?: ""
        if (managerText.isNotEmpty()) {
            val start = mCarinputmanager?.getSelectionStart() ?: 0
            val end = mCarinputmanager?.getSelectionEnd() ?: 0
            if (start == end) return null
            return managerText.substring(start, end)
        }
        return mInputconnection.getSelectedText(flags)
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        return 0
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (mCarinputmanager?.onTextCommitted != null) {
            val managerText = mCarinputmanager.getCurrentText()
            val start = mCarinputmanager.getSelectionStart()
            val end = mCarinputmanager.getSelectionEnd()

            return ExtractedText().apply {
                this.text = managerText
                this.startOffset = 0
                this.selectionStart = start
                this.selectionEnd = end
                this.flags = 0
            }
        }
        return mInputconnection.getExtractedText(request, flags)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        AppLog.d(TAG, "deleteSurroundingText: $beforeLength, $afterLength")

        if (mCarinputmanager?.onTextCommitted != null) {
            mCarinputmanager.onDeleteRequested?.invoke(beforeLength)
            return true
        }

        val result = mInputconnection.deleteSurroundingText(beforeLength, afterLength)
        if (beforeLength > 0 && afterLength == 0) {
            mInputconnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            mInputconnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return mInputconnection.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return false

        // MODALITÀ SINCRONIZZATA (Campi nativi Compose)
        if (mCarinputmanager?.onTextCommitted != null) {
            if (text.toString() == mLastcommittedtext) return true
            return commitText(text, newCursorPosition)
        }

        // MODALITÀ DIRETTA (WebView)
        // Alcune tastiere AA usano setComposingText invece di commitText per inserire singoli caratteri
        val result = mInputconnection.setComposingText(text, newCursorPosition)
        if (result && text.length == 1) {
            // Forziamo il commit se è un singolo carattere per assicurare la scrittura
            mInputconnection.finishComposingText()
        }
        return result
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return mInputconnection.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean {
        return mInputconnection.finishComposingText()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return false
        val textStr = text.toString()
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - mLastcommittime

        if (timeDiff < debouncingDelay && textStr == mLastcommittedtext) return true
        if (timeDiff < minInterCharacterDelay) return true

        mLastcommittime = currentTime
        mLastcommittedtext = textStr

        AppLog.d(TAG, "commitText: '$textStr'")

        if (mCarinputmanager?.onTextCommitted != null) {
            val currentText = mCarinputmanager.getCurrentText()

            mCarinputmanager.isImeUpdating = true
            try {
                if (textStr.length == 1) {
                    mCarinputmanager.onTextCommitted?.invoke(textStr)
                } else if (textStr != currentText) {
                    var commonPrefixLen = 0
                    val minLen = minOf(currentText.length, textStr.length)
                    while (commonPrefixLen < minLen && currentText[commonPrefixLen] == textStr[commonPrefixLen]) {
                        commonPrefixLen++
                    }

                    var currentSuffixIdx = currentText.length - 1
                    var textSuffixIdx = textStr.length - 1
                    var commonSuffixLen = 0
                    while (currentSuffixIdx >= commonPrefixLen && textSuffixIdx >= commonPrefixLen &&
                        currentText[currentSuffixIdx] == textStr[textSuffixIdx]
                    ) {
                        commonSuffixLen++
                        currentSuffixIdx--
                        textSuffixIdx--
                    }

                    val deletedLen = currentText.length - commonPrefixLen - commonSuffixLen
                    val insertedText =
                        textStr.substring(commonPrefixLen, textStr.length - commonSuffixLen)

                    if (deletedLen > 0) {
                        mCarinputmanager.onSelectionChanged?.invoke(
                            commonPrefixLen + deletedLen,
                            commonPrefixLen + deletedLen
                        )
                        mCarinputmanager.onDeleteRequested?.invoke(deletedLen)
                    }
                    if (insertedText.isNotEmpty()) {
                        mCarinputmanager.onSelectionChanged?.invoke(
                            commonPrefixLen,
                            commonPrefixLen
                        )
                        mCarinputmanager.onTextCommitted?.invoke(insertedText)
                    }
                }

                // Deleghiamo anche alla connessione nativa (EditText)
                return mInputconnection.commitText(text, newCursorPosition)
            } finally {
                mCarinputmanager.isImeUpdating = false
            }
        }

        // MODALITÀ DIRETTA (WebView)
        val result = mInputconnection.commitText(text, newCursorPosition)

        // Per le WebView su AA, se il commit nativo sembra non funzionare (comune su AA),
        // emuliamo un evento JavaScript per forzare l'inserimento del testo.
        // Lo facciamo SOLO per singoli caratteri (Tastiera Car) per evitare duplicati con lo smartphone.
        if (result && textStr.length == 1 && textStr != "\n") {
            val webView = mCarinputmanager?.getTargetView() as? android.webkit.WebView
            webView?.post {
                webView.evaluateJavascript(
                    "if(window.AndroidBridge) AndroidBridge.injectText('${
                        textStr.replace(
                            "'",
                            "\\'"
                        )
                    }');", null
                )
            }
        }

        // Backup: ENTER key handling
        if (textStr.contains("\n") || textStr == "\n") {
            mInputconnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            mInputconnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            val targetView = mCarinputmanager?.getTargetView()
            if (targetView is android.webkit.WebView) {
                targetView.post {
                    targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }
        return result
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        return mInputconnection.commitCompletion(text)
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        return mInputconnection.commitCorrection(correctionInfo)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        AppLog.d(TAG, "setSelection: $start-$end")
        mCarinputmanager?.onSelectionChanged?.invoke(start, end)
        mCarinputmanager?.updateState(mCarinputmanager.getCurrentText(), start, end)
        return mInputconnection.setSelection(start, end)
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        val result = mInputconnection.performEditorAction(editorAction)
        val targetView = mCarinputmanager?.getTargetView()
        if (targetView is android.webkit.WebView) {
            targetView.post {
                targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
        if (mCarinputmanager != null) mCarinputmanager.stopInput()
        return result
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return mInputconnection.performContextMenuAction(id)
    }

    override fun beginBatchEdit(): Boolean {
        return try {
            mInputconnection.beginBatchEdit()
        } catch (_: Exception) {
            false
        }
    }

    override fun endBatchEdit(): Boolean {
        return try {
            mInputconnection.endBatchEdit()
        } catch (_: Exception) {
            false
        }
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - mLastcommittime < `debouncingDelay`) return true
        }
        return mInputconnection.sendKeyEvent(event)
    }

    override fun clearMetaKeyStates(states: Int): Boolean {
        return mInputconnection.clearMetaKeyStates(states)
    }

    override fun reportFullscreenMode(enabled: Boolean): Boolean {
        return mInputconnection.reportFullscreenMode(enabled)
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
        return mInputconnection.performPrivateCommand(action, data)
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        return mInputconnection.requestCursorUpdates(cursorUpdateMode)
    }

    override fun getHandler(): Handler? {
        return mInputconnection.handler
    }

    override fun closeConnection() {
        mInputconnection.closeConnection()
    }

    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?
    ): Boolean {
        return mInputconnection.commitContent(inputContentInfo, flags, opts)
    }

    companion object {
        private const val TAG = "CarInputConnection"
    }
}
