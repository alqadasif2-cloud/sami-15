package com.sami.tradingchallengetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sami.tradingchallengetracker.MainActivity
import com.sami.tradingchallengetracker.R

class SamiFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val PREFS_NAME = "sami_fcm_prefs"
        const val KEY_FCM_TOKEN = "key_fcm_token"
        const val KEY_CURRENT_USER_ID = "key_current_user_id"
        const val KEY_CURRENT_USERNAME = "key_current_username"

        const val CHANNEL_ID_PUBLIC = "sami_channel_messages"
        const val CHANNEL_ID_PRIVATE = "sami_private_messages"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

                // 1. Channel messages & announcements
                val publicChannel = NotificationChannel(
                    CHANNEL_ID_PUBLIC,
                    "رسائل القنوات والتوصيات",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات الرسائل الجديدة والتوصيات في قنوات Sami"
                    enableLights(true)
                    lightColor = Color.rgb(245, 158, 11) // Amber / Gold accent
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                }

                // 2. Private 1-to-1 chats and Sami Admin alerts
                val privateChannel = NotificationChannel(
                    CHANNEL_ID_PRIVATE,
                    "المحادثات الخاصة وإشعارات المشرف",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات الرسائل الخاصة والمحادثات المباشرة مع القائد Sami والمشرفين"
                    enableLights(true)
                    lightColor = Color.rgb(234, 179, 8)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                }

                notificationManager.createNotificationChannel(publicChannel)
                notificationManager.createNotificationChannel(privateChannel)
            }
        }

        fun getSavedToken(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_FCM_TOKEN, null)
        }

        fun saveCurrentSession(context: Context, userId: Int, username: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_CURRENT_USER_ID, userId)
                .putString(KEY_CURRENT_USERNAME, username)
                .apply()
        }

        fun clearCurrentSession(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_CURRENT_USER_ID)
                .remove(KEY_CURRENT_USERNAME)
                .apply()
        }

        fun getCurrentUserId(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_CURRENT_USER_ID, 0)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()

        val userId = getCurrentUserId(this)
        if (userId > 0) {
            val username = prefs.getString(KEY_CURRENT_USERNAME, "") ?: ""
            // Sync to server / Firestore
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("action", "sync_fcm_token")
                putExtra("token", token)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = data["title"] ?: notification?.title ?: "رسالة جديدة - Sami"
        val body = data["body"] ?: notification?.body ?: "لديك إشعار جديد في تطبيق Sami"
        val chatType = data["chatType"] ?: if (data.containsKey("partnerId")) "private" else "channel"
        val channelId = data["channelId"] ?: ""
        val partnerId = data["partnerId"]?.toIntOrNull()
        val messageId = data["messageId"] ?: ""
        val senderName = data["senderName"] ?: ""

        // Multi-account privacy protection: Ensure notification recipient matches logged in session
        val targetRecipientId = data["recipientId"]?.toIntOrNull()
        val currentUserId = getCurrentUserId(this)
        if (targetRecipientId != null && targetRecipientId > 0 && currentUserId > 0 && targetRecipientId != currentUserId) {
            // Drop message intended for another account previously logged into this device
            return
        }

        sendNotification(
            title = title,
            body = body,
            chatType = chatType,
            channelId = channelId,
            partnerId = partnerId,
            messageId = messageId,
            senderName = senderName
        )
    }

    private fun sendNotification(
        title: String,
        body: String,
        chatType: String,
        channelId: String,
        partnerId: Int?,
        messageId: String,
        senderName: String
    ) {
        val channelIdNotification = if (chatType == "private") CHANNEL_ID_PRIVATE else CHANNEL_ID_PUBLIC

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("chatType", chatType)
            if (channelId.isNotEmpty()) putExtra("channelId", channelId)
            if (partnerId != null && partnerId > 0) {
                putExtra("partnerId", partnerId)
                putExtra("partnerIdStr", partnerId.toString())
            }
            if (messageId.isNotEmpty()) putExtra("messageId", messageId)
            if (senderName.isNotEmpty()) putExtra("senderName", senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 100000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(this, channelIdNotification)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(defaultSound)
            .setColor(0xFFF59E0B.toInt()) // Amber brand color
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (chatType == "private") NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        val notificationId = if (messageId.isNotEmpty()) messageId.hashCode() else (System.currentTimeMillis() % 100000).toInt()

        try {
            val notificationManager = NotificationManagerCompat.from(this)
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Handled when POST_NOTIFICATIONS permission not granted
        }
    }
}
