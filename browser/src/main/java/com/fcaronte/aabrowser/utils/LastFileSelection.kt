package com.fcaronte.aabrowser.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.ValueCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ultima scelta fatta con il selettore di file del telefono.
 *
 * Le pagine del telefono e dell'auto sono WebView diverse e non condividono i file scelti; sull'auto
 * (Main e Wide) il selettore non si può aprire, quindi la pagina riceve questa scelta. Sul telefono la
 * cartella viene riproposta, così dopo un ricaricamento della pagina non serve concedere di nuovo il permesso.
 * La cartella ha un permesso persistente e resta anche dopo un riavvio; i file singoli restano solo in
 * memoria e si leggono finché esiste l'Activity del telefono che li ha ricevuti.
 */
object LastFileSelection {
    private const val TAG = "LastFileSelection"
    private const val PREFS_NAME = "WEB_FILE_CHOOSER_PREFS"
    private const val KEY_FOLDER = "last_folder"

    // Limite di file restituiti per una cartella (la WebView legge il nome di ognuno)
    private const val MAX_FOLDER_FILES = 5000

    private val scope = MainScope()

    @Volatile
    private var files: Array<Uri>? = null

    fun saveFiles(uris: Array<Uri>) {
        files = uris
    }

    fun saveFolder(context: Context, treeUri: Uri) {
        files = null
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs(context).edit().putString(KEY_FOLDER, treeUri.toString()).apply()
        } catch (e: Exception) {
            AppLog.w(TAG, "Folder not remembered", e)
        }
    }

    /** Risponde con l'ultima scelta; false se dal telefono non è ancora stato scelto nulla (la WebView annulla). */
    fun deliver(context: Context, callback: ValueCallback<Array<Uri>>): Boolean {
        files?.let {
            callback.onReceiveValue(it)
            return true
        }
        val folder = rememberedFolder(context) ?: return false
        scope.launch { callback.onReceiveValue(listFolder(context, folder)) }
        return true
    }

    /** Nome della cartella ricordata ("primary:Music/Rock" -> "Rock"); null se non c'è o il permesso è stato revocato. */
    fun rememberedFolderName(context: Context): String? = rememberedFolder(context)?.let { folder ->
        val documentId = DocumentsContract.getTreeDocumentId(folder)
        documentId.substringAfterLast('/').substringAfter(':').ifBlank { documentId }
    }

    suspend fun listRememberedFolder(context: Context): Array<Uri>? =
        rememberedFolder(context)?.let { listFolder(context, it) }

    private fun rememberedFolder(context: Context): Uri? {
        val folder = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isReadPermission }
        return folder.takeIf { granted }
    }

    /** File della cartella e delle sottocartelle, come webkitdirectory; null se vuota o non più leggibile. */
    suspend fun listFolder(context: Context, treeUri: Uri): Array<Uri>? {
        val listed = withContext(Dispatchers.IO) {
            try {
                listFolderFiles(context.contentResolver, treeUri)
            } catch (e: Exception) {
                AppLog.e(TAG, "Unable to list the folder", e)
                emptyList()
            }
        }
        AppLog.d(TAG, "Folder listed: ${listed.size} files")
        return listed.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Una sola query per cartella; elementi nascosti (.thumbnails, .nomedia, ...) esclusi
    private fun listFolderFiles(resolver: ContentResolver, treeUri: Uri): List<Uri> {
        val result = mutableListOf<Uri>()
        val folders = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri)))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (folders.isNotEmpty() && result.size < MAX_FOLDER_FILES) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folders.removeFirst())
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && result.size < MAX_FOLDER_FILES) {
                    val documentId = cursor.getString(0) ?: continue
                    if (cursor.getString(1)?.startsWith(".") == true) continue
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.add(documentId)
                    } else {
                        result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
                    }
                }
            }
        }
        if (result.size >= MAX_FOLDER_FILES) AppLog.w(TAG, "Folder truncated to $MAX_FOLDER_FILES files")
        return result
    }
}
