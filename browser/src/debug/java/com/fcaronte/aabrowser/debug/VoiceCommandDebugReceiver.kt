package com.fcaronte.aabrowser.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fcaronte.aabrowser.mediaservice.CarMediaService
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.VoiceCommandRouter
import com.fcaronte.aabrowser.utils.VoiceCommands

/**
 * Solo nelle build di debug: prova dei comandi vocali senza parlare. Lo può chiamare solo la shell (permesso DUMP):
 *
 *   adb shell am broadcast -n com.fcaronte.aabrowser/.debug.VoiceCommandDebugReceiver \
 *       --es text "metti never gonna give you up su youtube" [--es target main|wide|assistant]
 *
 * target "assistant" = la stessa chiamata dell'Assistente ("metti [text] su AABrowser"), dalla sessione media.
 */
class VoiceCommandDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        val target = intent.getStringExtra("target")
        if (target == "assistant") {
            val sent = CarMediaService.simulateVoiceSearch(text)
            AppLog.d(TAG, "Assistant search sent=$sent")
            return
        }
        val command = VoiceCommands.parse(text)
        AppLog.d(TAG, "Parsed: $command")
        val handled = when (target) {
            "main" -> VoiceCommandRouter.dispatch(command, VoiceCommandRouter.Target.MAIN)
            "wide" -> VoiceCommandRouter.dispatch(command, VoiceCommandRouter.Target.WIDE)
            else -> VoiceCommandRouter.dispatch(command)
        }
        AppLog.d(TAG, "Dispatched=$handled")
    }

    private companion object {
        const val TAG = "VoiceCommandDebug"
    }
}
