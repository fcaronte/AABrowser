package com.fcaronte.aabrowser

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.BaseInputConnection

class CarInputConnection internal constructor(
    private val m_CarInputManager: CarInputManager?,
    private val m_InputConnection: InputConnection
) : InputConnection {

    private var m_LastCommitTime: Long = 0
    private var m_LastCommittedText: String = ""
    private val DEBOUNCING_DELAY = 100L // ms
    private val MIN_INTER_CHARACTER_DELAY = 30L // ms

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val managerText = m_CarInputManager?.getCurrentText() ?: ""
        if (managerText.isNotEmpty()) {
            val start = m_CarInputManager?.getSelectionStart() ?: 0
            return managerText.substring(0.coerceAtLeast(start - n), start)
        }
        return m_InputConnection.getTextBeforeCursor(n, flags)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val managerText = m_CarInputManager?.getCurrentText() ?: ""
        if (managerText.isNotEmpty()) {
            val end = m_CarInputManager?.getSelectionEnd() ?: 0
            return managerText.substring(end, (end + n).coerceAtMost(managerText.length))
        }
        return m_InputConnection.getTextAfterCursor(n, flags)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val managerText = m_CarInputManager?.getCurrentText() ?: ""
        if (managerText.isNotEmpty()) {
            val start = m_CarInputManager?.getSelectionStart() ?: 0
            val end = m_CarInputManager?.getSelectionEnd() ?: 0
            if (start == end) return null
            return managerText.substring(start, end)
        }
        return m_InputConnection.getSelectedText(flags)
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        return 0
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (m_CarInputManager?.onTextCommitted != null) {
            val managerText = m_CarInputManager.getCurrentText()
            val start = m_CarInputManager.getSelectionStart()
            val end = m_CarInputManager.getSelectionEnd()
            
            return ExtractedText().apply {
                this.text = managerText
                this.startOffset = 0
                this.selectionStart = start
                this.selectionEnd = end
                this.flags = 0
            }
        }
        return m_InputConnection.getExtractedText(request, flags)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        android.util.Log.d(TAG, "deleteSurroundingText: $beforeLength, $afterLength")
        
        if (m_CarInputManager?.onTextCommitted != null) {
            m_CarInputManager.onDeleteRequested?.invoke(beforeLength)
            return true
        }

        val result = m_InputConnection.deleteSurroundingText(beforeLength, afterLength)
        if (beforeLength > 0 && afterLength == 0) {
            m_InputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            m_InputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return m_InputConnection.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return false
        
        // MODALITÀ SINCRONIZZATA (Campi nativi Compose)
        if (m_CarInputManager?.onTextCommitted != null) {
            if (text.toString() == m_LastCommittedText) return true
            return commitText(text, newCursorPosition)
        }
        
        // MODALITÀ DIRETTA (WebView)
        // Alcune tastiere AA usano setComposingText invece di commitText per inserire singoli caratteri
        val result = m_InputConnection.setComposingText(text, newCursorPosition)
        if (result && text.length == 1) {
            // Forziamo il commit se è un singolo carattere per assicurare la scrittura
            m_InputConnection.finishComposingText()
        }
        return result
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return m_InputConnection.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean {
        return m_InputConnection.finishComposingText()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return false
        val textStr = text.toString()
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - m_LastCommitTime

        if (timeDiff < DEBOUNCING_DELAY && textStr == m_LastCommittedText) return true
        if (timeDiff < MIN_INTER_CHARACTER_DELAY) return true

        m_LastCommitTime = currentTime
        m_LastCommittedText = textStr

        android.util.Log.d(TAG, "commitText: '$textStr'")
        
        if (m_CarInputManager?.onTextCommitted != null) {
            val currentText = m_CarInputManager.getCurrentText()
            
            m_CarInputManager.isImeUpdating = true
            try {
                if (textStr.length == 1) {
                    m_CarInputManager.onTextCommitted?.invoke(textStr)
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
                           currentText[currentSuffixIdx] == textStr[textSuffixIdx]) {
                        commonSuffixLen++
                        currentSuffixIdx--
                        textSuffixIdx--
                    }

                    val deletedLen = currentText.length - commonPrefixLen - commonSuffixLen
                    val insertedText = textStr.substring(commonPrefixLen, textStr.length - commonSuffixLen)

                    if (deletedLen > 0) {
                        m_CarInputManager.onSelectionChanged?.invoke(commonPrefixLen + deletedLen, commonPrefixLen + deletedLen)
                        m_CarInputManager.onDeleteRequested?.invoke(deletedLen)
                    }
                    if (insertedText.isNotEmpty()) {
                        m_CarInputManager.onSelectionChanged?.invoke(commonPrefixLen, commonPrefixLen)
                        m_CarInputManager.onTextCommitted?.invoke(insertedText)
                    }
                }
                
                // Deleghiamo anche alla connessione nativa (EditText)
                return m_InputConnection.commitText(text, newCursorPosition)
            } finally {
                m_CarInputManager.isImeUpdating = false
            }
        }
        
        // MODALITÀ DIRETTA (WebView)
        val result = m_InputConnection.commitText(text, newCursorPosition)
        
        // Per le WebView su AA, se il commit nativo sembra non funzionare (comune su AA),
        // emuliamo un evento JavaScript per forzare l'inserimento del testo.
        // Lo facciamo SOLO per singoli caratteri (Tastiera Car) per evitare duplicati con lo smartphone.
        if (result && textStr.length == 1 && textStr != "\n") {
            val webView = m_CarInputManager?.getTargetView() as? android.webkit.WebView
            webView?.post {
                webView.evaluateJavascript("if(window.AndroidBridge) AndroidBridge.injectText('${textStr.replace("'", "\\'")}');", null)
            }
        }
        
        // Backup: ENTER key handling
        if (textStr == "\n") {
            m_InputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            m_InputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        return result
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        return m_InputConnection.commitCompletion(text)
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        return m_InputConnection.commitCorrection(correctionInfo)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        android.util.Log.d(TAG, "setSelection: $start-$end")
        m_CarInputManager?.onSelectionChanged?.invoke(start, end)
        m_CarInputManager?.updateState(m_CarInputManager.getCurrentText(), start, end)
        return m_InputConnection.setSelection(start, end)
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        val result = m_InputConnection.performEditorAction(editorAction)
        if (m_CarInputManager != null) m_CarInputManager.stopInput()
        return result
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return m_InputConnection.performContextMenuAction(id)
    }

    override fun beginBatchEdit(): Boolean {
        return try {
            m_InputConnection.beginBatchEdit()
        } catch (_: Exception) {
            false
        }
    }

    override fun endBatchEdit(): Boolean {
        return try {
            m_InputConnection.endBatchEdit()
        } catch (_: Exception) {
            false
        }
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - m_LastCommitTime < DEBOUNCING_DELAY) return true
        }
        return m_InputConnection.sendKeyEvent(event)
    }

    override fun clearMetaKeyStates(states: Int): Boolean {
        return m_InputConnection.clearMetaKeyStates(states)
    }

    override fun reportFullscreenMode(enabled: Boolean): Boolean {
        return m_InputConnection.reportFullscreenMode(enabled)
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
        return m_InputConnection.performPrivateCommand(action, data)
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        return m_InputConnection.requestCursorUpdates(cursorUpdateMode)
    }

    override fun getHandler(): Handler? {
        return m_InputConnection.getHandler()
    }

    override fun closeConnection() {
        m_InputConnection.closeConnection()
    }

    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?
    ): Boolean {
        return m_InputConnection.commitContent(inputContentInfo, flags, opts)
    }

    companion object {
        private const val TAG = "CarInputConnection"
    }
}
