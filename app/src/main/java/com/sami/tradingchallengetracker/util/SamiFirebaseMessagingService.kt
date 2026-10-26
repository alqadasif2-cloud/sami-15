package com.sami.tradingchallengetracker.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sami.tradingchallengetracker.MainActivity
import com.sami.tradingchallengetracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SamiFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val PREFS_NAME = "sami_fcm_prefs"
        const val KEY_FCM_TOKEN = "key_fcm_token"
        const val KEY_CURRENT_USER_ID = "key_current_user_id"
        const val KEY_CURRENT_USERNAME = "key_current_username"
        private const val KEY_RECENT_MSG_IDS = "key_recent_notified_msg_ids"

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

        @Synchronized
        private fun isDuplicateAndRecord(context: Context, messageId: String): Boolean {
            if (messageId.isBlank()) return false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_RECENT_MSG_IDS, "") ?: ""
            val list = raw.split("|").filter { it.isNotBlank() }.toMutableList()
            if (list.contains(messageId)) {
                return true
            }
            list.add(messageId)
            while (list.size > 60) {
                list.removeAt(0)
            }
            prefs.edit().putString(KEY_RECENT_MSG_IDS, list.joinToString("|")).apply()
            return false
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return

        FirebaseCloudHelper.ensureInitialized(applicationContext)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, cleanToken).apply()

        val userId = getCurrentUserId(this)
        if (userId > 0) {
            val username = prefs.getString(KEY_CURRENT_USERNAME, "") ?: ""
            CoroutineScope(Dispatchers.IO).launch {
                FirebaseCloudHelper.syncDeviceFcmToken(
                    context = applicationContext,
                    token = cleanToken,
                    userId = userId,
                    username = username
                )
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = data["title"] ?: notification?.title ?: "رسالة جديدة - Sami"
        val body = data["body"] ?: notification?.body ?: "لديك إشعار جديد في تطبيق Sami"
        val chatType = data["chatType"] ?: if (!data["partnerId"].isNullOrBlank()) "private" else "channel"
        val channelId = data["channelId"] ?: ""
        val partnerId = data["partnerId"]?.toIntOrNull()
        val senderId = data["senderId"]?.toIntOrNull()
        val messageId = data["messageId"] ?: remoteMessage.messageId ?: ""
        val senderName = data["senderName"] ?: ""

        // Strict account & privacy check:
        // 1. Do not notify the sender of their own message
        val currentUserId = getCurrentUserId(this)
        if (senderId != null && senderId > 0 && currentUserId > 0 && senderId == currentUserId) {
            return
        }

        // 2. If message targets a specific recipient account, ensure that exact user is currently logged in
        val targetRecipientId = data["recipientId"]?.toIntOrNull()
        if (targetRecipientId != null && targetRecipientId > 0) {
            if (currentUserId <= 0 || targetRecipientId != currentUserId) {
                return
            }
        }

        // 3. Deduplicate repeated push deliveries for the same messageId
        if (messageId.isNotEmpty() && isDuplicateAndRecord(this, messageId)) {
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
        // Ensure Android 8+ notification channels always exist even when woken in background
        createNotificationChannels(this)

        // Respect Android 13+ POST_NOTIFICATIONS runtime permission
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val channelIdNotification = if (chatType == "private") CHANNEL_ID_PRIVATE else CHANNEL_ID_PUBLIC
        val notificationId = if (messageId.isNotEmpty()) messageId.hashCode() else (System.currentTimeMillis() % 100000).toInt()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_SAMI_CHAT_${notificationId}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("fromNotification", true)
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
            notificationId,
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
            .setColor(0xFFF59E0B.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (chatType == "private") NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        try {
            val notificationManager = NotificationManagerCompat.from(this)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(messageId.ifEmpty { "sami_notif" }, notificationId, builder.build())
            }
        } catch (_: SecurityException) {
            // Permission revoked at OS level
        }
    }
}
