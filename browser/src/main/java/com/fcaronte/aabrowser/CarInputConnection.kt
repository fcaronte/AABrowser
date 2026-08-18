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
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val text = m_CarInputManager?.getCurrentText() ?: ""
        val start = m_CarInputManager?.getSelectionStart() ?: 0
        return text.substring(0.coerceAtLeast(start - n), start)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val text = m_CarInputManager?.getCurrentText() ?: ""
        val end = m_CarInputManager?.getSelectionEnd() ?: 0
        return text.substring(end, (end + n).coerceAtMost(text.length))
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val text = m_CarInputManager?.getCurrentText() ?: ""
        val start = m_CarInputManager?.getSelectionStart() ?: 0
        val end = m_CarInputManager?.getSelectionEnd() ?: 0
        if (start == end) return null
        return text.substring(start, end)
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        return 0
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        val text = m_CarInputManager?.getCurrentText() ?: ""
        val start = m_CarInputManager?.getSelectionStart() ?: 0
        val end = m_CarInputManager?.getSelectionEnd() ?: 0
        
        return ExtractedText().apply {
            this.text = text
            this.startOffset = 0
            this.selectionStart = start
            this.selectionEnd = end
        }
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        android.util.Log.d("CarInputConnection", "deleteSurroundingText: $beforeLength, $afterLength")
        
        // Se c'è un listener (es. overlay ricerca), notifichiamo la cancellazione
        if (beforeLength > 0 && afterLength == 0) {
            m_CarInputManager?.onDeleteRequested?.invoke(beforeLength)
        }

        // Fallback JavaScript per siti come YouTube che ignorano il comando nativo
        if (beforeLength > 0 && afterLength == 0) {
            val webView = m_CarInputManager?.getTargetView() as? android.webkit.WebView
            webView?.evaluateJavascript("""
                (function() {
                    var el = document.activeElement;
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                            var start = el.selectionStart;
                            var end = el.selectionEnd;
                            if (start === end && start > 0) {
                                el.value = el.value.substring(0, start - 1) + el.value.substring(end);
                                el.selectionStart = el.selectionEnd = start - 1;
                            } else {
                                el.value = el.value.substring(0, start) + el.value.substring(end);
                                el.selectionStart = el.selectionEnd = start;
                            }
                        } else {
                            // Per contenteditable (meno comune ma possibile)
                            document.execCommand('delete', false, null);
                        }
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                })();
            """.trimIndent(), null)
        }

        val result = m_InputConnection.deleteSurroundingText(beforeLength, afterLength)
        
        // Backup via KeyEvent per i cancellati
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
        android.util.Log.d("CarInputConnection", "setComposingText: $text")
        return m_InputConnection.setComposingText(text, newCursorPosition)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return m_InputConnection.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean {
        return m_InputConnection.finishComposingText()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        android.util.Log.d("CarInputConnection", "commitText: $text")
        if (text == null) return false
        
        // Se c'è un listener per il testo (es. overlay ricerca), lo notifichiamo
        m_CarInputManager?.onTextCommitted?.invoke(text.toString())
        
        val result = m_InputConnection.commitText(text, newCursorPosition)
        
        // Se il commitText nativo non riporta successo o siamo in fallback,
        // proviamo a iniettare il testo via bridge JavaScript.
        if (text.isNotEmpty()) {
            val webView = m_CarInputManager?.getTargetView() as? android.webkit.WebView
            val escapedText = text.toString().replace("'", "\\'")
            webView?.evaluateJavascript("if(window.AndroidBridge) AndroidBridge.injectText('$escapedText');", null)
            
            // Backup secondario via KeyEvent
            for (char in text) {
                val event = KeyEvent(System.currentTimeMillis(), char.toString(), 0, 0)
                m_InputConnection.sendKeyEvent(event)
            }
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
        m_CarInputManager?.onSelectionChanged?.invoke(start, end)
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
            // Deleghiamo la chiamata all'InputConnection originale
            m_InputConnection.beginBatchEdit()
        } catch (e: AssertionError) {
            // Ignoriamo il crash di Chromium. La digitazione continuerà a funzionare
            Log.w("CarInputConnection", "Soppresso AssertionError in beginBatchEdit", e)
            false
        } catch (e: Exception) {
            Log.e("CarInputConnection", "Errore in beginBatchEdit", e)
            false
        }
    }

    override fun endBatchEdit(): Boolean {
        return try {
            // Deleghiamo la chiamata all'InputConnection originale
            m_InputConnection.endBatchEdit()
        } catch (e: AssertionError) {
            Log.w("CarInputConnection", "Soppresso AssertionError in endBatchEdit", e)
            false
        } catch (e: Exception) {
            Log.e("CarInputConnection", "Errore in endBatchEdit", e)
            false
        }
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        android.util.Log.d("CarInputConnection", "sendKeyEvent: ${event?.keyCode}")
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
