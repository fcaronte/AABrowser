package com.fcaronte.aabrowser.utils

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fcaronte.aabrowser.BuildConfig
import com.fcaronte.aabrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val REPO_URL = "https://api.github.com/repos/fcaronte/AABrowser/releases/latest"
    private const val DOWNLOAD_PAGE = "https://github.com/fcaronte/AABrowser/releases"
    // browser_download_url degli APK delle release (API di GitHub): https://github.com + questo percorso
    private const val RELEASE_DOWNLOAD_PATH = "/fcaronte/AABrowser/releases/download/"
    private const val NETWORK_TIMEOUT_MS = 15_000
    private const val CHANNEL_ID = "com.fcaronte.aabrowser.updates"
    private const val NOTIFICATION_ID = 1001
    private const val READY_NOTIFICATION_ID = 1002

    const val ACTION_START_UPDATE_DOWNLOAD = "com.fcaronte.aabrowser.START_UPDATE_DOWNLOAD"
    const val EXTRA_DOWNLOAD_URL = "extra_download_url"
    const val EXTRA_VERSION = "extra_version"
    private const val KING_INSTALLER_PACKAGE = "com.example.kinginstaller"

    private const val PREFS_NAME = "app_updates"
    private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
    private const val KEY_PENDING_APK_PATH = "pending_apk_path"
    private const val KEY_LAST_DOWNLOAD_ID = "last_download_id"
    private const val STAGED_APK_DIR = "update"

    // Build di debug, "Test aggiornamento": l'app si comporta come se fosse installata questa versione
    private const val KEY_TEST_OLD_VERSION = "test_old_version"
    private const val TEST_OLD_VERSION = "2.3"

    private var isNotificationAlreadyShown = false
    var isBannerDismissed = false

    data class UpdateInfo(
        val isAvailable: Boolean,
        val latestVersion: String,
        val downloadUrl: String
    )

    /** Solo build di debug: banner, notifica e download dell'ultima release compaiono come per una versione vecchia. */
    fun isTestingOldVersion(context: Context): Boolean =
        BuildConfig.DEBUG &&
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TEST_OLD_VERSION, false)

    fun setTestingOldVersion(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TEST_OLD_VERSION, enabled)
            .apply()
        // Banner e notifica possono ricomparire subito
        isBannerDismissed = false
        isNotificationAlreadyShown = false
    }

    suspend fun checkForUpdates(context: Context): UpdateInfo {
        return withContext(Dispatchers.IO) {
            // L'app è ripartita: se c'era un aggiornamento pronto da installare, o è fatto o si ricomincia
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(READY_NOTIFICATION_ID)
            try {
                val url = URL(REPO_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                // Senza limiti una rete che non risponde lasciava il controllo appeso
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                connection.connect()

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val latestVersion = json.getString("tag_name").replace("v", "")

                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersion =
                        if (isTestingOldVersion(context)) TEST_OLD_VERSION else pInfo.versionName ?: "1.0"

                    // Recupera l'URL dell'APK se disponibile negli assets
                    var downloadUrl = DOWNLOAD_PAGE
                    val assets = json.optJSONArray("assets")
                    if (assets != null && assets.length() > 0) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }

                    UpdateInfo(
                        isAvailable = isVersionNewer(latestVersion, currentVersion),
                        latestVersion = "v$latestVersion",
                        downloadUrl = downloadUrl
                    )
                } else {
                    UpdateInfo(false, "", "")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Error checking for updates: ${e.message}")
                UpdateInfo(false, "", "")
            }
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toInt() }
            val currentParts = current.split(".").map { it.toInt() }

            val maxLength = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLength) {
                val l = if (i < latestParts.size) latestParts[i] else 0
                val c = if (i < currentParts.size) currentParts[i] else 0
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun getApkFileName(url: String, version: String): String {
        val extracted = url.substringAfterLast('/').substringBefore('?')
        return if (extracted.endsWith(".apk", ignoreCase = true)) {
            extracted
        } else {
            val clean = version.replace("v", "")
            "CarWeb-v$clean.apk"
        }
    }

    /** true = download avviato (la Wide lo dice sullo schermo dell'auto: i Toast si vedono solo sul telefono). */
    fun startDownload(context: Context, info: UpdateInfo): Boolean {
        if (info.downloadUrl.isBlank()) {
            AppLog.e(TAG, "Cannot start download: downloadUrl is blank")
            return false
        }
        // Si scaricano solo le release ufficiali: KingInstaller installa senza chiedere
        if (!isOfficialReleaseUrl(info.downloadUrl)) {
            AppLog.w(TAG, "Refusing download outside the official releases")
            Toast.makeText(context, context.getString(R.string.update_download_failed), Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // 1. Rimuovi i download precedenti da DownloadManager (e i loro file)
            listOf(KEY_PENDING_DOWNLOAD_ID, KEY_LAST_DOWNLOAD_ID).map { prefs.getLong(it, -1L) }.filter { it != -1L }.forEach { oldId ->
                try {
                    downloadManager.remove(oldId)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Impossibile rimuovere vecchio download ID $oldId: ${e.message}")
                }
            }

            // 2. Mantiene il nome file originale dall'URL di GitHub (es. CarWeb-v2.5.apk), nella cartella dell'app. Nei
            // Download pubblici l'app non può leggere né cancellare i file che non ha creato lei (sul telefono "Permission
            // denied" su un APK copiato lì): con un file rimasto con quel nome, quello nuovo ne prende un altro e il
            // controllo guardava il vecchio, dicendo che l'aggiornamento non era valido
            val fileName = getApkFileName(info.downloadUrl, info.latestVersion)
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles { file -> file.name.endsWith(".apk") }?.forEach { it.delete() }

            val request = DownloadManager.Request(info.downloadUrl.toUri()).apply {
                setTitle(fileName)
                setDescription(context.getString(R.string.downloading_update))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = downloadManager.enqueue(request)

            prefs.edit()
                .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
                .remove(KEY_PENDING_APK_PATH)
                .apply()

            Toast.makeText(
                context,
                context.getString(R.string.update_download_started),
                Toast.LENGTH_SHORT
            ).show()

            AppLog.d(TAG, "Enqueued download ID: $downloadId for file $fileName")
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start download: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.update_download_failed),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
    }

    /** Da un thread di lavoro: copia e controllo di un APK da 60 MB non vanno fatti sul thread principale. */
    fun handleDownloadComplete(context: Context, downloadId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -2L)

        if (downloadId != pendingId) {
            AppLog.d(TAG, "Completed download $downloadId does not match pending update $pendingId")
            return
        }

        prefs.edit()
            .remove(KEY_PENDING_DOWNLOAD_ID)
            .remove(KEY_PENDING_APK_PATH)
            .putLong(KEY_LAST_DOWNLOAD_ID, downloadId)
            .apply()

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (!isDownloadSuccessful(downloadManager, downloadId)) {
            Log.w(TAG, "Update: the download did not finish successfully")
            showToast(context, R.string.update_download_failed)
            return
        }

        // Si controlla una copia nella cache dell'app, quella che poi va a KingInstaller: da lì nessuno la può cambiare
        val apkFile = stageDownloadedApk(context, downloadManager, downloadId)
        if (apkFile == null) {
            showToast(context, R.string.update_unreadable)
            return
        }
        val problem = updateProblem(context, apkFile)
        if (problem != null) {
            apkFile.delete()
            showToast(context, problem)
            return
        }
        val apkUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            Log.w(TAG, "Update: no address for the checked file: ${e.javaClass.simpleName}")
            showToast(context, R.string.update_unreadable)
            return
        }
        Log.d(TAG, "Update: checked, handing it to KingInstaller")
        showReadyNotification(context, apkUri)
        launchKingInstaller(context, apkUri)
    }

    private fun isDownloadSuccessful(downloadManager: DownloadManager, downloadId: Long): Boolean =
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            cursor.moveToFirst() && statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        } == true

    private fun localFileOf(downloadManager: DownloadManager, downloadId: Long): File? =
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            val index = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (cursor.moveToFirst() && index != -1) cursor.getString(index)?.toUri()?.path?.let(::File) else null
        }

    private fun stageDownloadedApk(context: Context, downloadManager: DownloadManager, downloadId: Long): File? {
        val updateDir = File(context.cacheDir, STAGED_APK_DIR)
        updateDir.deleteRecursively()
        val downloaded = localFileOf(downloadManager, downloadId)
        // Il nome giusto, quello che KingInstaller mostra
        val stagedFile = File(updateDir, downloaded?.name?.takeIf { it.endsWith(".apk") } ?: "CarWeb-update.apk")
        // Prima il file nella cartella dell'app, poi l'indirizzo del DownloadManager
        val sources = listOf<() -> InputStream?>(
            { downloaded?.inputStream() },
            { downloadManager.getUriForDownloadedFile(downloadId)?.let { context.contentResolver.openInputStream(it) } }
        )
        updateDir.mkdirs()
        for (source in sources) {
            try {
                val input = source() ?: continue
                input.use { from -> stagedFile.outputStream().use { to -> from.copyTo(to) } }
                if (stagedFile.length() > 0L) return stagedFile
                Log.w(TAG, "Update: the downloaded file is empty")
            } catch (e: Exception) {
                Log.w(TAG, "Update: downloaded file not readable: ${e.javaClass.simpleName}")
            }
        }
        stagedFile.delete()
        return null
    }

    /** Indirizzo di un APK delle release di AABrowser su GitHub, letto come indirizzo e non come testo. */
    private fun isOfficialReleaseUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.path?.startsWith(RELEASE_DOWNLOAD_PATH) == true && uri.pathSegments.none { it == "." || it == ".." }
    }

    /**
     * null se è un aggiornamento valido, altrimenti il messaggio da mostrare: stesso pacchetto, stessa firma dell'app
     * installata e versione più nuova (il test di debug salta solo quest'ultimo controllo, così una release più vecchia
     * arriva fino a KingInstaller). Il motivo finisce nel log anche nelle release: senza non si capisce mai cosa è successo.
     */
    private fun updateProblem(context: Context, apkFile: File): Int? = try {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val apkSigners = apk?.let(::signers).orEmpty()
        when {
            apk == null -> {
                Log.w(TAG, "Update: Android cannot read the APK (${apkFile.length()} bytes)")
                R.string.update_unreadable
            }
            apk.packageName != context.packageName -> {
                Log.w(TAG, "Update: the APK is another app")
                R.string.update_invalid_apk
            }
            apkSigners.isEmpty() || apkSigners != signers(installed) -> {
                Log.w(TAG, "Update: the APK has another signature (signers: ${apkSigners.size})")
                R.string.update_invalid_apk
            }
            apk.longVersionCode <= installed.longVersionCode && !isTestingOldVersion(context) -> {
                Log.w(TAG, "Update: the APK is not newer (${apk.longVersionCode} <= ${installed.longVersionCode})")
                R.string.update_not_newer
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Update: the APK could not be checked: ${e.javaClass.simpleName}")
        R.string.update_unreadable
    }

    private fun showToast(context: Context, message: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
        }
    }

    // Impronte SHA-256 dei certificati con cui è firmato il contenuto dell'APK
    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    // MainActivity apre KingInstaller con il file caricato, pronto per scegliere come installarlo. ApkHandler, quello che
    // risponde a una richiesta generica, passa invece subito all'installazione standard di Android
    private fun installerIntent(apkUri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        setClassName(KING_INSTALLER_PACKAGE, "$KING_INSTALLER_PACKAGE.MainActivity")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launchKingInstaller(context: Context, apkUri: Uri) {
        try {
            context.startActivity(installerIntent(apkUri))
        } catch (_: ActivityNotFoundException) {
            AppLog.w(TAG, "MainActivity component not found, trying general package intent...")
            try {
                context.startActivity(installerIntent(apkUri).setComponent(null).setPackage(KING_INSTALLER_PACKAGE))
            } catch (e: Exception) {
                Log.w(TAG, "Update: KingInstaller not found: ${e.javaClass.simpleName}")
                showToast(context, R.string.kinginstaller_not_found)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update: KingInstaller not opened: ${e.javaClass.simpleName}")
        }
    }

    private fun createChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for new app versions"
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Aggiornamento controllato, pronto da installare. A download finito l'app può non essere davanti (si è passati ad
     * altro, o si è in auto) e allora Android non le fa aprire KingInstaller, senza errori: con la notifica si apre da lì.
     */
    private fun showReadyNotification(context: Context, apkUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(notificationManager)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, installerIntent(apkUri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(READY_NOTIFICATION_ID, notification)
    }

    fun showUpdateNotification(context: Context, info: UpdateInfo) {
        if (!info.isAvailable || isNotificationAlreadyShown) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Verifica permesso su Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                AppLog.w(TAG, "Missing POST_NOTIFICATIONS permission")
                return
            }
        }

        createChannel(notificationManager)

        val intent = Intent(context, UpdateStartReceiver::class.java).apply {
            action = ACTION_START_UPDATE_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_URL, info.downloadUrl)
            putExtra(EXTRA_VERSION, info.latestVersion)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_available_title, info.latestVersion))
            .setContentText(context.getString(R.string.update_available_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_play_arrow_black_24dp,
                context.getString(R.string.download_button),
                pendingIntent
            )

        notificationManager.notify(NOTIFICATION_ID, builder.build())
        isNotificationAlreadyShown = true
    }
}
