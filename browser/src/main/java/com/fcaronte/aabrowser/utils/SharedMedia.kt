package com.fcaronte.aabrowser.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.UUID

/**
 * La foto, il video o il file audio condivisi dal telefono ("Condividi su CarWeb"): l'ultimo condiviso, aperto in una
 * scheda dalla pagina https://player.local/shared/ (LocalPlayer). Il permesso di leggere il file dura quanto l'attività
 * che lo riceve, che si chiude subito: l'app lo chiede per sé e lo restituisce al file condiviso dopo.
 */
object SharedMedia {
    const val PAGE_PATH = "/shared/"
    private const val TAG = "SharedMedia"
    private val KINDS = setOf("image", "video", "audio")

    class Item(val id: String, val uri: Uri, val mime: String, val name: String) {
        /** "image", "video" o "audio". */
        val kind: String get() = mime.substringBefore('/')
        val pageUrl: String get() = LocalPlayer.BASE_URL + PAGE_PATH.removePrefix("/") + id
    }

    @Volatile
    private var current: Item? = null

    fun find(id: String?): Item? = current?.takeIf { it.id == id }

    /** Dall'attività che ha ricevuto il file, finché è aperta. null = non è un file multimediale che l'app può leggere. */
    fun accept(context: Context, uri: Uri, declaredType: String?): Item? {
        // L'attività accetta richieste da qualsiasi app: niente percorsi diretti né i file privati di CarWeb
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || isOwnProvider(context, uri)) return null
        val resolver = context.contentResolver
        val mime = listOfNotNull(runCatching { resolver.getType(uri) }.getOrNull(), declaredType)
            .map { it.lowercase() }
            .firstOrNull { it.substringBefore('/') in KINDS }
            ?: return null
        try {
            context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.w(TAG, "No access to the shared file: ${e.javaClass.simpleName}")
            return null
        }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        val item = Item(UUID.randomUUID().toString(), uri, mime, name)
        current?.takeIf { it.uri != uri }?.let { releaseAccess(context, it.uri) }
        current = item
        return item
    }

    private fun isOwnProvider(context: Context, uri: Uri): Boolean {
        val authority = uri.authority ?: return true
        return context.packageManager.resolveContentProvider(authority, 0)?.packageName == context.packageName
    }

    private fun releaseAccess(context: Context, uri: Uri) {
        try {
            context.revokeUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to release the shared file: ${e.javaClass.simpleName}")
        }
    }
}
