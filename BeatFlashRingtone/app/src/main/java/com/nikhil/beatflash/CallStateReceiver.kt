package com.nikhil.beatflash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
                    // Carry the broadcast arrival time through startup work to avoid resetting the timeline later.
                    putExtra(FlashBeatService.EXTRA_RING_START_ELAPSED, SystemClock.elapsedRealtime())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(startIntent)
                else context.startService(startIntent)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                val stopIntent = Intent(context, FlashBeatService::class.java).apply {
                    action = FlashBeatService.ACTION_STOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(stopIntent)
                else context.startService(stopIntent)
            }
        }
    }
}
