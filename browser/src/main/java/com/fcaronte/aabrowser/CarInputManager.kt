package com.fcaronte.aabrowser

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.car.input.CarEditable
import com.google.android.gms.car.input.CarEditableListener
import com.google.android.gms.car.input.InputManager

class CarInputManager internal constructor(private val mInputmanager: InputManager?) :
    CarEditable {
    private var mTargetview: View? = null

    private val _isInputActiveState = mutableStateOf(false)
    val isInputActiveState: State<Boolean> = _isInputActiveState

    private var mManualstop = false

    fun isCurrentCarEditable(carEditable: CarEditable?): Boolean {
        return mInputmanager != null && mInputmanager.isCurrentCarEditable(carEditable)
    }

    val isInputActive: Boolean
        get() {
            if (mManualstop) {
                if (_isInputActiveState.value) _isInputActiveState.value = false
                return false
            }
            val active = mInputmanager != null && mInputmanager.isInputActive
            if (_isInputActiveState.value != active) {
                _isInputActiveState.value = active
            }
            return active
        }

    val isValid: Boolean
        get() = mInputmanager != null && mInputmanager.isValid

    fun startInput(TargetView: View?) {
        if (mInputmanager == null || TargetView == null) return

        // Se stiamo già gestendo la stessa View e l'input è attivo, non facciamo nulla
        if (mTargetview == TargetView && mInputmanager.isInputActive) {
            android.util.Log.d("CarInputManager", "Input already active for this view")
            return
        }

        mManualstop = false
        mTargetview = TargetView

        android.util.Log.d("CarInputManager", "Requesting startInput for view: $TargetView")
        try {
            // Se l'input è già attivo su un'altra view, facciamo stop pulito
            if (mInputmanager.isInputActive && mTargetview != TargetView) {
                mInputmanager.stopInput()
            }

            // Forza il focus sulla view prima di iniziare
            if (!TargetView.isFocused) {
                TargetView.requestFocus()
            }

            // Per le WebView, usiamo un ciclo di post per garantire che il thread UI 
            // abbia processato il focus HTML prima di agganciare la tastiera car.
            if (TargetView is android.webkit.WebView) {
                TargetView.post {
                    mInputmanager.startInput(this)
                    updateActiveState()
                }
            } else {
                mInputmanager.startInput(this)
                updateActiveState()
            }
        } catch (e: Exception) {
            android.util.Log.e("CarInputManager", "Error starting input", e)
        }
    }

    fun stopInput() {
        mManualstop = true
        if (mInputmanager != null) {
            try {
                mInputmanager.stopInput()
                _isInputActiveState.value = false
            } catch (e: Exception) {
                android.util.Log.e("CarInputManager", "Error stopping input", e)
            }
            mTargetview = null
        }
    }

    private fun updateActiveState() {
        if (mManualstop) {
            _isInputActiveState.value = false
            return
        }
        val active = mInputmanager != null && mInputmanager.isInputActive
        if (_isInputActiveState.value != active) {
            _isInputActiveState.value = active
        }
    }

    fun getTargetView(): View? = mTargetview

    override fun onCreateInputConnection(editorInfo: EditorInfo?): InputConnection? {
        if (mTargetview == null) return null

        // NON chiamiamo requestFocus() qui perché potrebbe resettare il campo HTML 
        // se chiamato nel thread sbagliato o nel momento sbagliato dell'SDK AA.
        mTargetview?.onCheckIsTextEditor()

        var inputConnection = mTargetview!!.onCreateInputConnection(editorInfo)

        // Se la WebView non fornisce una connessione, proviamo a forzare il focus e riprovare una volta
        if (inputConnection == null && mTargetview is android.webkit.WebView) {
            android.util.Log.w(
                "CarInputManager",
                "WebView returned null InputConnection, forcing focus sync"
            )
            mTargetview?.requestFocus()
            inputConnection = mTargetview!!.onCreateInputConnection(editorInfo)
        }

        if (inputConnection == null) {
            android.util.Log.w("CarInputManager", "Using BaseInputConnection fallback")
            inputConnection = android.view.inputmethod.BaseInputConnection(mTargetview!!, true)
        }

        // Configura editorInfo per massimizzare la compatibilità con la tastiera car
        editorInfo?.apply {
            if (inputType == EditorInfo.TYPE_NULL) {
                inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
            }
            imeOptions =
                imeOptions or EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI

            // Sovrascriviamo la selezione solo se siamo in modalità sincronizzazione manuale (campi nativi)
            // Per le WebView, lasciamo che il sistema usi quanto riportato dalla WebView stessa.
            if (onTextCommitted != null) {
                initialSelStart = mSelectionstart
                initialSelEnd = mSelectionend
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
    private var mCurrenttext: String = ""
    private var mSelectionstart: Int = 0
    private var mSelectionend: Int = 0

    fun updateState(text: String, selectionStart: Int, selectionEnd: Int) {
        if (mCurrenttext == text && mSelectionstart == selectionStart && mSelectionend == selectionEnd) return

        mCurrenttext = text
        mSelectionstart = selectionStart
        mSelectionend = selectionEnd
    }

    fun getCurrentText(): String = mCurrenttext
    fun getSelectionStart(): Int = mSelectionstart
    fun getSelectionEnd(): Int = mSelectionend

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
