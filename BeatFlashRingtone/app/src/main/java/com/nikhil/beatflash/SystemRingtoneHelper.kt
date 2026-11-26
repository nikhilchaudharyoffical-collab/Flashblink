package com.nikhil.beatflash

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

object SystemRingtoneHelper {

    fun canWriteSystemSettings(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    fun getCurrentSystemRingtoneUri(context: Context): Uri? {
        return RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE
        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    fun getRingtoneTitle(context: Context, uri: Uri?): String {
        if (uri == null) return "Default System Ringtone"
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.getTitle(context) ?: "Unknown Ringtone"
        } catch (e: Exception) {
            "System Ringtone"
        }
    }

    /**
     * Copies selected audio into system Ringtones MediaStore (or uses existing system URI)
     * and sets it directly as the phone's actual default incoming ringtone.
     */
    fun applyAsSystemRingtone(context: Context, sourceUri: Uri, displayName: String): Uri? {
        if (!canWriteSystemSettings(context)) return null

        // If already a system ringtone URI, apply directly
        if (sourceUri.toString().startsWith("content://media/internal/audio")) {
            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE,
                sourceUri
            )
            return sourceUri
        }

        return try {
            val resolver = context.contentResolver
            val cleanName = displayName.substringBeforeLast(".")
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .ifBlank { "BeatFlash_Ringtone" }
            val fileName = "${cleanName}_${System.currentTimeMillis()}.mp3"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.TITLE, cleanName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.IS_RINGTONE, true)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
                put(MediaStore.Audio.Media.IS_ALARM, false)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val targetUri = resolver.insert(collection, values) ?: return null

            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(targetUri)?.use { output ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(targetUri, publishValues, null, null)
            }

            // Directly update Android system incoming call ringtone
            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE,
                targetUri
            )
            targetUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
