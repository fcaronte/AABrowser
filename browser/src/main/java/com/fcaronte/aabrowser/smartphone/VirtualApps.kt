package com.fcaronte.aabrowser.smartphone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.view.KeyEvent
import com.fcaronte.aabrowser.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Le app del telefono come "pagine" di CarWeb: un indirizzo `app://<pacchetto>` che sta nelle schede della Main e viene
 * aperto dalla Wide, più l'elenco delle app, le icone e i preferiti (stella nella pagina "Altre App").
 */
object VirtualApps {
    const val SCHEME = "app://"

    // Nome di pacchetto di Android: segmenti separati da punti, lettere, cifre e trattino basso
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun isAppUrl(url: String?): Boolean = url?.startsWith(SCHEME) == true

    fun urlFor(packageName: String): String = SCHEME + packageName

    /** Il pacchetto di un indirizzo `app://…`, null se non ha la forma di un nome di pacchetto. */
    fun packageOf(url: String?): String? {
        if (!isAppUrl(url)) return null
        return url!!.removePrefix(SCHEME).substringBefore('/').substringBefore('?').substringBefore('#')
            .takeIf { PACKAGE_NAME.matches(it) }
    }

    /**
     * L'attività da aprire per il pacchetto (quella dell'icona nel launcher), null se non c'è. CarWeb stessa si può aprire
     * (dal 15/11/2026, richiesta dell'utente): è il modo di usare la Main dentro la pagina della Wide, sul display virtuale.
     */
    fun launchComponent(context: Context, packageName: String): ComponentName? {
        if (!PACKAGE_NAME.matches(packageName)) return null
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component
        } catch (_: Exception) {
            null
        }
    }

    /** Si può aprire sul display virtuale: installata, con un'attività nel launcher (anche CarWeb). */
    fun isAvailable(context: Context, packageName: String): Boolean = launchComponent(context, packageName) != null

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    /** Il motivo per cui l'app non si mostra, nella lingua del contesto (la Wide: sul display dell'auto non c'è altro). */
    fun problemMessage(context: Context, problem: VirtualAppSession.Problem): String = when (problem.kind) {
        VirtualAppSession.Kind.SHIZUKU_MISSING -> context.getString(R.string.vapp_error_missing) + "\n" + context.getString(R.string.apps_hint_phone)
        VirtualAppSession.Kind.SHIZUKU_STOPPED -> context.getString(R.string.vapp_error_stopped) + "\n" + context.getString(R.string.apps_hint_phone)
        VirtualAppSession.Kind.SHIZUKU_PERMISSION -> context.getString(R.string.vapp_error_permission) + "\n" + context.getString(R.string.apps_hint_phone)
        VirtualAppSession.Kind.NO_APP -> context.getString(R.string.vapp_error_no_app)
        VirtualAppSession.Kind.FAILED -> context.getString(R.string.vapp_error_failed, problem.detail)
    }

    /** Tutte le app con un'icona nel launcher (CarWeb compresa), per nome. */
    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val byPackage = LinkedHashMap<String, AppEntry>()
        for (info in found) {
            val pkg = info.activityInfo.packageName
            if (byPackage.containsKey(pkg)) continue
            byPackage[pkg] = AppEntry(pkg, info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg)
        }
        return byPackage.values.sortedBy { it.name.lowercase() }
    }

    data class AppEntry(val packageName: String, val name: String)

    // ------------------------------------------------------------------ icone

    private val icons = LruCache<String, Bitmap>(64)

    /** L'icona dell'app a [size] pixel (cache), null se non si legge. */
    fun icon(context: Context, packageName: String, size: Int = 128): Bitmap? {
        val key = "$packageName@$size"
        icons.get(key)?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null && drawable.bitmap.width >= size) {
                Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
            } else {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(it))
                }
            }
            icons.put(key, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ preferiti

    /** Le app con la stella: si aprono dalla home di CarWeb (Main e Wide). */
    fun favorites(context: Context): List<AppEntry> {
        val manager = FavoriteAppsManager(context)
        return manager.loadFavoritePackageNames()
            .filter { isAvailable(context, it) }
            .map { AppEntry(it, label(context, it)) }
            .sortedBy { it.name.lowercase() }
    }

    // ------------------------------------------------------------------ sessioni aperte

    // Le sessioni delle schede della Main, per scheda: il tasto Indietro del telefono e la barra delle schede le raggiungono da qui
    private val sessions = ConcurrentHashMap<String, VirtualAppSession>()

    fun register(tabId: String, session: VirtualAppSession) {
        sessions[tabId] = session
    }

    fun unregister(tabId: String, session: VirtualAppSession) {
        sessions.remove(tabId, session)
    }

    fun sessionOf(tabId: String?): VirtualAppSession? = tabId?.let { sessions[it] }

    /** Indietro dell'app (il tasto del telefono, della barra schede, comando vocale): true se c'era un'app a riceverlo. */
    fun back(tabId: String?): Boolean {
        val session = sessionOf(tabId) ?: return false
        session.key(KeyEvent.KEYCODE_BACK)
        return true
    }
}
