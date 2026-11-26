package com.nikhil.beatflash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!BeatAnalyzer.isFeatureEnabled(context)) return
                val startIntent = Intent(context, FlashBeatService::class.java).apply {
                    action = FlashBeatService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val stopIntent = Intent(context, FlashBeatService::class.java).apply {
                    action = FlashBeatService.ACTION_STOP
                }
                context.startService(stopIntent)
            }
        }
    }
}
