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
        if (m_InputManager == null || TargetView == null) return
        
        // Se stiamo già gestendo la stessa View e l'input è attivo, non facciamo nulla
        if (m_TargetView == TargetView && m_InputManager.isInputActive) {
            android.util.Log.d("CarInputManager", "Input already active for this view")
            return
        }

        m_TargetView = TargetView
        
        android.util.Log.d("CarInputManager", "Requesting startInput for view: $TargetView")
        try {
            // Se l'input è già attivo su un'altra view, facciamo stop pulito
            if (m_InputManager.isInputActive && m_TargetView != TargetView) {
                m_InputManager.stopInput()
            }
            
            // Forza il focus sulla view prima di iniziare
            if (!TargetView.isFocused) {
                TargetView.requestFocus()
            }

            // Per le WebView, usiamo un ciclo di post per garantire che il thread UI 
            // abbia processato il focus HTML prima di agganciare la tastiera car.
            if (TargetView is android.webkit.WebView) {
                TargetView.post {
                    m_InputManager.startInput(this)
                }
            } else {
                m_InputManager.startInput(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("CarInputManager", "Error starting input", e)
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

        // NON chiamiamo requestFocus() qui perché potrebbe resettare il campo HTML 
        // se chiamato nel thread sbagliato o nel momento sbagliato dell'SDK AA.
        m_TargetView?.onCheckIsTextEditor()
        
        var inputConnection = m_TargetView!!.onCreateInputConnection(editorInfo)
        
        // Se la WebView non fornisce una connessione, proviamo a forzare il focus e riprovare una volta
        if (inputConnection == null && m_TargetView is android.webkit.WebView) {
            android.util.Log.w("CarInputManager", "WebView returned null InputConnection, forcing focus sync")
            m_TargetView?.requestFocus()
            inputConnection = m_TargetView!!.onCreateInputConnection(editorInfo)
        }
        
        if (inputConnection == null) {
            android.util.Log.w("CarInputManager", "Using BaseInputConnection fallback")
            inputConnection = android.view.inputmethod.BaseInputConnection(m_TargetView!!, true)
        }
        
        // Configura editorInfo per massimizzare la compatibilità con la tastiera car
        editorInfo?.apply {
            if (inputType == EditorInfo.TYPE_NULL) {
                inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
            }
            imeOptions = imeOptions or EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            
            // Sovrascriviamo la selezione solo se siamo in modalità sincronizzazione manuale (campi nativi)
            // Per le WebView, lasciamo che il sistema usi quanto riportato dalla WebView stessa.
            if (onTextCommitted != null) {
                initialSelStart = m_SelectionStart
                initialSelEnd = m_SelectionEnd
            }
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

    internal var isImeUpdating = false
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
