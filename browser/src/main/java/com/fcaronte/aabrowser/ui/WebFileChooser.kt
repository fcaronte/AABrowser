package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.LastFileSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "WebFileChooser"

// Tempo massimo tra il tocco su un campo webkitdirectory e l'apertura del selettore
private const val FOLDER_HINT_MS = 1500L

/**
 * Selettore di file e cartelle per le pagine web (WebChromeClient.onShowFileChooser).
 *
 * Usa solo lo Storage Access Framework: l'app riceve il permesso sui file o sulla cartella scelti
 * dall'utente, senza accesso a tutta la memoria.
 * - `<input type="file">`: intent preparato dalla WebView (rispetta accept e multiple).
 * - `<input webkitdirectory>`: la WebView lo presenta come scelta multipla di file e si aspetta i file
 *   della cartella. Il listener dei campi (BrowserJavascript) avvisa prima con [onFolderInputClicked]:
 *   si ripropone la cartella già autorizzata ([WebFileChooserDialog]) o si apre il selettore cartella,
 *   e si restituiscono i file della cartella e delle sottocartelle.
 * - `showDirectoryPicker()` (File System Access, targetSdk >= 36): MODE_OPEN_FOLDER, la WebView vuole
 *   l'URI della cartella e la legge da sola.
 * Senza Activity (Android Auto) il selettore non si apre: la pagina riceve l'ultima scelta del telefono.
 */
class WebFileChooser internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private enum class Request { FILES, FOLDER_FILES, FOLDER }

    internal var launcher: ActivityResultLauncher<Intent>? = null

    /** Cartella già autorizzata proposta al posto del selettore; null quando il dialogo è chiuso. */
    var folderOffer by mutableStateOf<String?>(null)
        private set

    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingRequest = Request.FILES

    // Scritto dal thread del JavaBridge, letto dal main thread
    @Volatile
    private var folderInputClickedAt = 0L

    fun onFolderInputClicked() {
        folderInputClickedAt = SystemClock.elapsedRealtime()
    }

    /** Ritorna false se la richiesta non viene gestita: in quel caso è la WebView ad annullarla. */
    @SuppressLint("InlinedApi") // MODE_OPEN_FOLDER è una costante: arriva solo dalle WebView con File System Access
    fun show(callback: ValueCallback<Array<Uri>>, params: FileChooserParams?): Boolean {
        val mode = params?.mode ?: FileChooserParams.MODE_OPEN
        val isFolderInput = SystemClock.elapsedRealtime() - folderInputClickedAt < FOLDER_HINT_MS
        folderInputClickedAt = 0L
        val launcher = launcher ?: return LastFileSelection.deliver(context, callback)

        val request = when {
            mode == FileChooserParams.MODE_OPEN_FOLDER -> Request.FOLDER
            mode == FileChooserParams.MODE_OPEN_MULTIPLE && isFolderInput -> Request.FOLDER_FILES
            else -> Request.FILES
        }

        // Chromium non apre due selettori insieme, ma una richiesta rimasta in sospeso va comunque chiusa
        takePendingCallback()?.onReceiveValue(null)

        if (request == Request.FOLDER_FILES) {
            // Cartella già autorizzata: si propone di riusarla, senza selettore né nuovo consenso
            LastFileSelection.rememberedFolderName(context)?.let { folderName ->
                pendingCallback = callback
                pendingRequest = request
                folderOffer = folderName
                return true
            }
        }

        val intent = if (request == Request.FILES) {
            (params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
                .apply {
                    val filenameHint = params?.filenameHint
                    if (mode == FileChooserParams.MODE_SAVE && filenameHint != null) putExtra(Intent.EXTRA_TITLE, filenameHint)
                }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        return launchPicker(launcher, intent, callback, request)
    }

    fun useRememberedFolder() {
        val callback = takePendingCallback() ?: return
        scope.launch { callback.onReceiveValue(LastFileSelection.listRememberedFolder(context)) }
    }

    fun chooseAnotherFolder() {
        val callback = takePendingCallback() ?: return
        val launcher = launcher
        if (launcher == null || !launchPicker(launcher, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), callback, Request.FOLDER_FILES)) {
            callback.onReceiveValue(null)
        }
    }

    fun dismissFolderOffer() {
        takePendingCallback()?.onReceiveValue(null)
    }

    private fun takePendingCallback(): ValueCallback<Array<Uri>>? {
        folderOffer = null
        return pendingCallback.also { pendingCallback = null }
    }

    // Non chiama il callback: se fallisce decide chi ha chiesto il selettore
    private fun launchPicker(
        launcher: ActivityResultLauncher<Intent>,
        intent: Intent,
        callback: ValueCallback<Array<Uri>>,
        request: Request,
    ): Boolean {
        return try {
            launcher.launch(intent)
            pendingCallback = callback
            pendingRequest = request
            AppLog.d(TAG, "Picker opened: request=$request")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Unable to open the picker", e)
            false
        }
    }

    internal fun onActivityResult(result: ActivityResult) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }

        when (pendingRequest) {
            Request.FILES -> {
                val uris = selectedUris(data)
                if (uris != null) LastFileSelection.saveFiles(uris)
                callback.onReceiveValue(uris)
            }
            Request.FOLDER -> {
                val treeUri = data.data
                if (treeUri != null) keepFolderPermission(treeUri, data.flags)
                callback.onReceiveValue(treeUri?.let { arrayOf(it) })
            }
            Request.FOLDER_FILES -> {
                val treeUri = data.data ?: return callback.onReceiveValue(null)
                LastFileSelection.saveFolder(context, treeUri)
                scope.launch { callback.onReceiveValue(LastFileSelection.listFolder(context, treeUri)) }
            }
        }
    }

    private fun selectedUris(data: Intent): Array<Uri>? {
        val clipData = data.clipData
        val uris = if (clipData != null) {
            List(clipData.itemCount) { clipData.getItemAt(it).uri }.filterNotNull()
        } else {
            listOfNotNull(data.data)
        }
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    // showDirectoryPicker: la pagina può conservare la cartella e riaprirla più tardi
    private fun keepFolderPermission(treeUri: Uri, resultFlags: Int) {
        val modeFlags = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, modeFlags)
        } catch (e: Exception) {
            AppLog.w(TAG, "Folder permission not persisted", e)
        }
    }
}

@Composable
fun rememberWebFileChooser(): WebFileChooser {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val chooser = remember { WebFileChooser(context, scope) }
    // Sull'auto (MainCarActivity non è una ComponentActivity) non c'è un registro dei risultati
    if (LocalActivityResultRegistryOwner.current != null) {
        chooser.launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            chooser.onActivityResult(it)
        }
    }
    return chooser
}

/** Propone la cartella già autorizzata al posto del selettore di sistema (vedi [WebFileChooser.show]). */
@Composable
fun WebFileChooserDialog(chooser: WebFileChooser) {
    val folderName = chooser.folderOffer ?: return
    AlertDialog(
        onDismissRequest = chooser::dismissFolderOffer,
        text = { Text(stringResource(R.string.file_chooser_last_folder, folderName)) },
        confirmButton = {
            TextButton(onClick = chooser::useRememberedFolder) {
                Text(stringResource(R.string.file_chooser_use_folder))
            }
        },
        dismissButton = {
            TextButton(onClick = chooser::chooseAnotherFolder) {
                Text(stringResource(R.string.file_chooser_change_folder))
            }
        },
    )
}
