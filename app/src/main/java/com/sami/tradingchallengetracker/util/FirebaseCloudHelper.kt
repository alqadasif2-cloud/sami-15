package com.sami.tradingchallengetracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Shared Firebase Cloud Helper for Android App
 * Connects to shared Firestore database (`ai-studio-c1d1b606-9578-42a6-b7b2-17faaec47f72`)
 * and shared Storage bucket (`majestic-interface-ksmzh.firebasestorage.app`).
 */
object FirebaseCloudHelper {

    private const val TAG = "FirebaseCloudHelper"
    private const val SHARED_FIREBASE_PROJECT_ID = "majestic-interface-ksmzh"
    private const val SHARED_FIREBASE_STORAGE_BUCKET = "majestic-interface-ksmzh.firebasestorage.app"
    private const val SHARED_FIRESTORE_DB_ID = "ai-studio-c1d1b606-9578-42a6-b7b2-17faaec47f72"
    private const val SHARED_FIREBASE_API_KEY = "AIzaSyCWSCiM0EVatqiBL5wUO8ZAfKcJHqud9dU"
    private const val SHARED_FIREBASE_APP_ID = "1:462035009117:web:ef127abfd25d8ff2ec17b4"
    private const val SHARED_FIREBASE_SENDER_ID = "462035009117"

    // Authorized shared Firestore collection for notifications & device push tokens
    const val COLLECTION_NOTIFICATIONS = "community_notifications"
    const val COLLECTION_CHANNELS = "community_channels"

    @Volatile
    private var notificationListenerReg: ListenerRegistration? = null

    @Volatile
    private var listeningForUserId: Int = 0

    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        return try {
            val appCtx = context.applicationContext
            if (FirebaseApp.getApps(appCtx).isNotEmpty()) {
                return true
            }
            val defaultApp = FirebaseApp.initializeApp(appCtx)
            if (defaultApp != null) {
                return true
            }
            val options = FirebaseOptions.Builder()
                .setProjectId(SHARED_FIREBASE_PROJECT_ID)
                .setApplicationId(SHARED_FIREBASE_APP_ID)
                .setApiKey(SHARED_FIREBASE_API_KEY)
                .setGcmSenderId(SHARED_FIREBASE_SENDER_ID)
                .setStorageBucket(SHARED_FIREBASE_STORAGE_BUCKET)
                .build()
            FirebaseApp.initializeApp(appCtx, options)
            Log.d(TAG, "Initialized FirebaseApp explicitly with shared Sami configuration.")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase initialization check notice: ${e.message}")
            FirebaseApp.getApps(context.applicationContext).isNotEmpty()
        }
    }

    fun isFirebaseConfigured(context: Context): Boolean {
        return ensureInitialized(context)
    }

    fun getFirestore(context: Context): FirebaseFirestore? {
        if (!ensureInitialized(context)) return null
        return try {
            val app = FirebaseApp.getInstance()
            FirebaseFirestore.getInstance(app, SHARED_FIRESTORE_DB_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Named Firestore fallback to default: ${e.message}")
            try {
                FirebaseFirestore.getInstance()
            } catch (ex: Exception) {
                Log.e(TAG, "Firestore unavailable", ex)
                null
            }
        }
    }

    fun getStorage(context: Context): FirebaseStorage? {
        if (!ensureInitialized(context)) return null
        return try {
            FirebaseStorage.getInstance("gs://$SHARED_FIREBASE_STORAGE_BUCKET")
        } catch (e: Exception) {
            try {
                FirebaseStorage.getInstance()
            } catch (ex: Exception) {
                Log.e(TAG, "Firebase Storage unavailable", ex)
                null
            }
        }
    }

    /**
     * Generates or retrieves a persistent unique device identifier for multi-device FCM support
     */
    fun getStableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(SamiFirebaseMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString("stable_device_id", null)
        if (!cached.isNullOrBlank()) return cached

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val rawGenerated = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            "and_$androidId"
        } else {
            "and_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        }
        val cleanId = rawGenerated.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        prefs.edit().putString("stable_device_id", cleanId).apply()
        return cleanId
    }

    private fun buildTokenDocId(userId: Int, deviceId: String): String {
        val safeDev = deviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifEmpty { "android" }
        return "fcm_tok_${userId}_$safeDev"
    }

    /**
     * Registers or refreshes the device's FCM token in the authorized shared Firestore
     * collection (`community_notifications/fcm_tok_{userId}_{deviceId}`), unbinding any
     * stale account previously signed in on the same physical device.
     */
    suspend fun syncDeviceFcmToken(
        context: Context,
        token: String,
        userId: Int,
        username: String
    ): Boolean {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty() || userId <= 0) return false
        val db = getFirestore(context) ?: return false
        val deviceId = getStableDeviceId(context)
        val docId = buildTokenDocId(userId, deviceId)
        val now = System.currentTimeMillis()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        return try {
            // Unbind any previous user account associated with this same deviceId or token
            try {
                val sameDeviceDocs = db.collection(COLLECTION_NOTIFICATIONS)
                    .whereEqualTo("type", "fcm_device_token")
                    .whereEqualTo("deviceId", deviceId)
                    .get()
                    .await()
                for (doc in sameDeviceDocs.documents) {
                    if (doc.id != docId) {
                        doc.reference.delete().await()
                    }
                }
            } catch (_: Exception) {}

            try {
                val sameTokenDocs = db.collection(COLLECTION_NOTIFICATIONS)
                    .whereEqualTo("type", "fcm_device_token")
                    .whereEqualTo("token", cleanToken)
                    .get()
                    .await()
                for (doc in sameTokenDocs.documents) {
                    if (doc.id != docId) {
                        doc.reference.delete().await()
                    }
                }
            } catch (_: Exception) {}

            val payload = hashMapOf<String, Any>(
                "id" to docId,
                "type" to "fcm_device_token",
                "title" to "fcm_token",
                "body" to "",
                "token" to cleanToken,
                "userId" to userId,
                "senderId" to userId,
                "username" to username,
                "senderName" to username,
                "deviceId" to deviceId,
                "platform" to "android",
                "deviceModel" to model,
                "targetUserIds" to emptyList<Int>(),
                "timestamp" to now,
                "updatedAt" to now,
                "createdAt" to now
            )

            db.collection(COLLECTION_NOTIFICATIONS)
                .document(docId)
                .set(payload, SetOptions.merge())
                .await()

            Log.d(TAG, "Synced FCM token to shared Firestore for user=$userId device=$deviceId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync FCM token to Firestore: ${e.message}")
            false
        }
    }

    /**
     * Removes this device's FCM token binding on logout or account switch so private notifications
     * are never delivered after sign-out.
     */
    suspend fun removeDeviceFcmToken(context: Context, userId: Int): Boolean {
        if (userId <= 0) return false
        val db = getFirestore(context) ?: return false
        val deviceId = getStableDeviceId(context)
        val docId = buildTokenDocId(userId, deviceId)
        return try {
            db.collection(COLLECTION_NOTIFICATIONS)
                .document(docId)
                .delete()
                .await()
            Log.d(TAG, "Removed FCM token doc $docId on logout")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Notice removing FCM token doc: ${e.message}")
            false
        }
    }

    /**
     * Real-time authorized push notification listener on `community_notifications`.
     * Delivers OS notifications when the app is in background or viewing another screen,
     * strictly filtered by recipient userId and deduplicated atomically with FCM by messageId.
     */
    @Synchronized
    fun startNotificationPushListener(context: Context, userId: Int) {
        if (userId <= 0) {
            stopNotificationPushListener()
            return
        }
        if (listeningForUserId == userId && notificationListenerReg != null) {
            return
        }
        stopNotificationPushListener()

        val appCtx = context.applicationContext
        val db = getFirestore(appCtx) ?: return
        val listenerStartMs = System.currentTimeMillis() - 4_000L
        listeningForUserId = userId

        try {
            notificationListenerReg = db.collection(COLLECTION_NOTIFICATIONS)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(20)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val activeUid = SamiFirebaseMessagingService.getCurrentUserId(appCtx)
                    if (activeUid <= 0 || activeUid != userId) return@addSnapshotListener

                    for (change in snapshot.documentChanges) {
                        if (change.type != DocumentChange.Type.ADDED) continue
                        val data = change.document.data
                        val type = (data["type"] as? String) ?: ""
                        if (type == "fcm_device_token") continue

                        val ts = (data["timestamp"] as? Number)?.toLong() ?: 0L
                        if (ts < listenerStartMs) continue

                        val senderId = (data["senderId"] as? Number)?.toInt() ?: 0
                        if (senderId == activeUid) continue

                        val rawTargets = data["targetUserIds"] as? List<*>
                        val targetIds = rawTargets?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
                        if (!targetIds.contains(activeUid)) continue

                        val chatType = if (
                            (data["chatType"] as? String) == "private" || type == "private_message"
                        ) "private" else "channel"

                        val title = (data["title"] as? String)?.ifBlank { "رسالة جديدة - Sami" }
                            ?: "رسالة جديدة - Sami"
                        val body = (data["body"] as? String)?.ifBlank { "لديك رسالة جديدة" }
                            ?: "لديك رسالة جديدة"
                        val channelId = (data["channelId"] as? String) ?: ""
                        val partnerId = ((data["partnerId"] as? Number)
                            ?: (data["privateChatWithUserId"] as? Number))?.toInt()
                            ?: if (chatType == "private") senderId else null
                        val messageId = (data["messageId"] as? String)?.ifBlank { null }
                            ?: (data["id"] as? String)
                            ?: change.document.id
                        val senderName = (data["senderName"] as? String) ?: "Sami"

                        SamiFirebaseMessagingService.postAuthorizedSystemNotification(
                            context = appCtx,
                            title = title,
                            body = body,
                            chatType = chatType,
                            channelId = channelId,
                            partnerId = partnerId,
                            messageId = messageId,
                            senderId = senderId,
                            senderName = senderName,
                            targetRecipientId = activeUid
                        )
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed starting notification push listener: ${e.message}")
        }
    }

    @Synchronized
    fun stopNotificationPushListener() {
        try {
            notificationListenerReg?.remove()
        } catch (_: Exception) {}
        notificationListenerReg = null
        listeningForUserId = 0
    }

    /**
     * Compresses an image Uri and uploads to Firebase Cloud Storage (`chat_images/{userId}/{uuid}.jpg`).
     */
    suspend fun uploadChatImage(
        context: Context,
        imageUri: Uri,
        userId: Int,
        folder: String = "chat_images"
    ): Pair<String, String>? {
        val storage = getStorage(context) ?: return null
        return try {
            val bytes = compressUriToJpeg(context, imageUri, 1280, 82) ?: return null
            val fileId = "${folder}_${userId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val storagePath = "$folder/$fileId.jpg"
            val storageRef = storage.reference.child(storagePath)

            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .setCustomMetadata("uploaderId", userId.toString())
                .build()

            storageRef.putBytes(bytes, metadata).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            Pair(downloadUrl, storagePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading image to Firebase Storage", e)
            null
        }
    }

    suspend fun deleteStoragePath(context: Context, storagePath: String?) {
        if (storagePath.isNullOrBlank()) return
        val storage = getStorage(context) ?: return
        try {
            storage.reference.child(storagePath).delete().await()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting file at $storagePath: ${e.message}")
        }
    }

    private fun compressUriToJpeg(
        context: Context,
        uri: Uri,
        maxDimension: Int,
        quality: Int
    ): ByteArray? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            var inSampleSize = 1
            val rawW = boundsOptions.outWidth
            val rawH = boundsOptions.outHeight
            if (rawW > maxDimension || rawH > maxDimension) {
                var halfW = rawW / 2
                var halfH = rawH / 2
                while ((halfW / inSampleSize) >= maxDimension || (halfH / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val scaled = scaleDownBitmap(decoded, maxDimension)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled != decoded) scaled.recycle()
            decoded.recycle()
            baos.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed", e)
            null
        }
    }

    private fun scaleDownBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = w.toFloat() / h.toFloat()
        val (targetW, targetH) = if (w > h) {
            maxDim to (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            (maxDim * ratio).toInt().coerceAtLeast(1) to maxDim
        }
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }
}
