package com.fcaronte.aabrowser

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.android.gms.car.input.CarEditable
import com.google.android.gms.car.input.CarEditableListener
import com.google.android.gms.car.input.InputManager

class CarInputManager internal constructor(private val m_InputManager: InputManager?) :
    CarEditable {
    private var m_TargetView: View? = null

    fun isCurrentCarEditable(carEditable: CarEditable?): Boolean {
        return m_InputManager != null && m_InputManager.isCurrentCarEditable(carEditable)
    }

    val isInputActive: Boolean
        get() = m_InputManager != null && m_InputManager.isInputActive()

    val isValid: Boolean
        get() = m_InputManager != null && m_InputManager.isValid()

    fun startInput(TargetView: View?) {
        if (m_InputManager != null && !this.isInputActive) {
            m_TargetView = TargetView
            
            android.util.Log.d("CarInputManager", "Requesting startInput for WebView")
            try {
                // Rilasciamo eventuali connessioni pendenti senza togliere il focus alla View
                m_InputManager.stopInput()
                
                // Richiediamo l'input. Il sistema car chiamerà onCreateInputConnection.
                // NON chiamiamo requestFocus() qui per evitare di deselezionare il campo HTML.
                m_InputManager.startInput(this)
            } catch (e: Exception) {
                android.util.Log.e("CarInputManager", "Error starting input", e)
            }
        }
    }

    fun stopInput() {
        if (m_InputManager != null && this.isInputActive) {
            try {
                m_InputManager.stopInput()
            } catch (e: Exception) {
                android.util.Log.e("CarInputManager", "Error stopping input", e)
            }
            m_TargetView = null
        }
    }

    fun getTargetView(): View? = m_TargetView

    override fun onCreateInputConnection(editorInfo: EditorInfo?): InputConnection? {
        if (m_TargetView == null) return null

        // Forza il sistema a rinfrescare lo stato dell'editor
        m_TargetView?.onCheckIsTextEditor()
        
        var inputConnection = m_TargetView!!.onCreateInputConnection(editorInfo)
        
        // Se la WebView non fornisce una connessione, proviamo a richiederla di nuovo dopo un micro-delay
        // ma siccome questo metodo deve essere sincrono, usiamo un fallback BaseInputConnection
        // legato alla WebView, che è meglio di niente.
        if (inputConnection == null) {
            android.util.Log.w("CarInputManager", "WebView returned null InputConnection, using BaseInputConnection")
            inputConnection = android.view.inputmethod.BaseInputConnection(m_TargetView!!, true)
        }
        
        // Configura editorInfo per massimizzare la compatibilità con la tastiera car
        editorInfo?.apply {
            if (inputType == EditorInfo.TYPE_NULL) {
                inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
            }
            imeOptions = imeOptions or EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            initialSelStart = 0
            initialSelEnd = 0
        }

        return CarInputConnection(this, inputConnection)
    }

    override fun setCarEditableListener(carEditableListener: CarEditableListener?) {
    }

    override fun setInputEnabled(b: Boolean) {
    }

    companion object {
        private const val TAG = "CarInputManager"
    }

    internal var onTextCommitted: ((String) -> Unit)? = null
    internal var onDeleteRequested: ((Int) -> Unit)? = null
    internal var onSelectionChanged: ((Int, Int) -> Unit)? = null

    // Stato del testo corrente per sincronizzazione con la tastiera AA
    private var m_CurrentText: String = ""
    private var m_SelectionStart: Int = 0
    private var m_SelectionEnd: Int = 0

    fun updateState(text: String, selectionStart: Int, selectionEnd: Int) {
        if (m_CurrentText == text && m_SelectionStart == selectionStart && m_SelectionEnd == selectionEnd) return
        
        m_CurrentText = text
        m_SelectionStart = selectionStart
        m_SelectionEnd = selectionEnd
        
        // Se l'input è attivo, informiamo il sistema car del cambiamento
        // Questo dovrebbe aiutare a sincronizzare il cursore sulla proiezione
        if (isInputActive) {
            // Alcune versioni di Gearhead richiedono il riavvio dell'input per aggiornare i metadati
            // ma lo facciamo solo se necessario per evitare loop.
            // android.util.Log.d("CarInputManager", "State updated: $text ($selectionStart-$selectionEnd)")
        }
    }

    fun getCurrentText(): String = m_CurrentText
    fun getSelectionStart(): Int = m_SelectionStart
    fun getSelectionEnd(): Int = m_SelectionEnd

    fun setOnInputEventListener(
        onText: (String) -> Unit,
        onDelete: (Int) -> Unit,
        onSelection: (Int, Int) -> Unit = { _, _ -> }
    ) {
        onTextCommitted = onText
        onDeleteRequested = onDelete
        onSelectionChanged = onSelection
    }

    fun clearListeners() {
        onTextCommitted = null
        onDeleteRequested = null
        onSelectionChanged = null
    }
}
