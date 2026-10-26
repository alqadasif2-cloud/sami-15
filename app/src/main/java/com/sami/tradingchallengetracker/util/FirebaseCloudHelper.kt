package com.sami.tradingchallengetracker.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.storage.FirebaseStorage
import com.sami.tradingchallengetracker.data.ChallengeEntity
import com.sami.tradingchallengetracker.data.TradeEntity
import com.sami.tradingchallengetracker.data.TradeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID

object FirebaseCloudHelper {

    @Volatile
    private var firestoreInstance: FirebaseFirestore? = null

    @Volatile
    private var storageInstance: FirebaseStorage? = null

    private const val SHARED_FIRESTORE_DB_ID = "ai-studio-c1d1b606-9578-42a6-b7b2-17faaec47f72"
    private const val FCM_PREFS = "sami_fcm_prefs"
    private const val KEY_STABLE_DEVICE_ID = "key_stable_device_id"

    fun getStableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(FCM_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_STABLE_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "dev_android_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        prefs.edit().putString(KEY_STABLE_DEVICE_ID, generated).apply()
        return generated
    }

    fun getFirestore(context: Context): FirebaseFirestore {
        return firestoreInstance ?: synchronized(this) {
            firestoreInstance ?: run {
                val app = try {
                    if (FirebaseApp.getApps(context).isEmpty()) {
                        FirebaseApp.initializeApp(context)
                    } else {
                        FirebaseApp.getInstance()
                    }
                } catch (_: Exception) {
                    null
                }

                val db = try {
                    if (app != null) {
                        FirebaseFirestore.getInstance(app, SHARED_FIRESTORE_DB_ID)
                    } else {
                        FirebaseFirestore.getInstance(SHARED_FIRESTORE_DB_ID)
                    }
                } catch (_: Exception) {
                    FirebaseFirestore.getInstance()
                }

                try {
                    val settings = FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(
                            PersistentCacheSettings.newBuilder()
                                .setSizeBytes(100L * 1024 * 1024) // 100MB offline local cache
                                .build()
                        )
                        .build()
                    db.firestoreSettings = settings
                } catch (_: Exception) {}

                firestoreInstance = db
                db
            }
        }
    }

    suspend fun syncDeviceFcmToken(
        context: Context,
        token: String,
        userId: Int,
        username: String
    ) = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty() || userId <= 0) return@withContext
        try {
            val db = getFirestore(context)
            val devId = getStableDeviceId(context)
            val docId = "token_${userId}_${devId}"
            val now = System.currentTimeMillis()

            // Remove any stale token document on this same physical device from a previous user account
            val prefs = context.getSharedPreferences(FCM_PREFS, Context.MODE_PRIVATE)
            val lastBoundUserId = prefs.getInt("key_last_bound_uid", 0)
            if (lastBoundUserId > 0 && lastBoundUserId != userId) {
                try {
                    db.collection("user_fcm_tokens").document("token_${lastBoundUserId}_${devId}").delete().await()
                } catch (_: Exception) {}
            }
            prefs.edit().putInt("key_last_bound_uid", userId).apply()

            val payload = hashMapOf<String, Any>(
                "id" to docId,
                "token" to cleanToken,
                "userId" to userId,
                "username" to username,
                "deviceId" to devId,
                "platform" to "android",
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "updatedAt" to now,
                "createdAt" to now
            )

            db.collection("user_fcm_tokens").document(docId).set(payload).await()
        } catch (e: Exception) {
            android.util.Log.w("SamiFCM", "Error syncing FCM token to shared Firestore: ${e.message}")
        }
    }

    suspend fun removeDeviceFcmToken(context: Context, userId: Int) = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext
        try {
            val db = getFirestore(context)
            val devId = getStableDeviceId(context)
            db.collection("user_fcm_tokens").document("token_${userId}_${devId}").delete().await()
        } catch (_: Exception) {}
    }

    fun getStorage(context: Context): FirebaseStorage {
        return storageInstance ?: synchronized(this) {
            storageInstance ?: run {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context)
                }
                val storage = FirebaseStorage.getInstance()
                storageInstance = storage
                storage
            }
        }
    }

    fun createCameraOutputUri(context: Context): Uri {
        val imagesFolder = File(context.cacheDir, "camera_images").apply { mkdirs() }
        val tempFile = File(imagesFolder, "capture_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }

    suspend fun uploadImageToCloud(
        context: Context,
        uri: Uri,
        folder: String,
        userId: Int
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val fileName = "${folder}/${userId}/${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
        try {
            val storage = getStorage(context)
            val ref = storage.reference.child(fileName)
            val uploadTask = ref.putFile(uri).await()
            val downloadUrl = ref.downloadUrl.await().toString()
            Pair(downloadUrl, fileName)
        } catch (e: Exception) {
            // Local fallback if Firebase Storage is unavailable or offline
            Pair(uri.toString(), fileName)
        }
    }

    suspend fun saveImageToGallery(context: Context, imageUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bitmap = when {
                imageUrl.startsWith("content://") || imageUrl.startsWith("file://") -> {
                    context.contentResolver.openInputStream(Uri.parse(imageUrl))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                    val stream = URL(imageUrl).openStream()
                    BitmapFactory.decodeStream(stream)
                }
                else -> null
            } ?: return@withContext Result.failure(Exception("تعذر تحميل بيانات الصورة"))

            val fileName = "Sami_Trading_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SamiTrading")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(Exception("تعذر إنشاء مسار الصورة في المعرض"))
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SamiTrading").apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }

            Result.success("تم حفظ الصورة في المعرض بنجاح")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUserChallengeFromFirestore(
        context: Context,
        userId: Int
    ): Pair<ChallengeEntity, List<TradeEntity>>? = withContext(Dispatchers.IO) {
        try {
            val db = getFirestore(context)
            val doc = db.collection("user_challenges").document("user_$userId").get().await()
            if (!doc.exists()) return@withContext null

            val challengeData = doc.get("challenge") as? Map<String, Any> ?: return@withContext null
            val ch = ChallengeEntity(
                id = challengeData["id"] as? String ?: UUID.randomUUID().toString(),
                userId = (challengeData["userId"] as? Number)?.toInt() ?: userId,
                userName = challengeData["userName"] as? String ?: "",
                initialCapitalCents = (challengeData["initialCapitalCents"] as? Number)?.toLong() ?: 10000L,
                currentBalanceCents = (challengeData["currentBalanceCents"] as? Number)?.toLong() ?: 10000L,
                targetBalanceCents = (challengeData["targetBalanceCents"] as? Number)?.toLong() ?: 300000L,
                tradeCount = (challengeData["tradeCount"] as? Number)?.toInt() ?: 0,
                challengeStarted = challengeData["challengeStarted"] as? Boolean ?: false,
                createdAt = (challengeData["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                updatedAt = (challengeData["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )

            val tradesRaw = doc.get("trades") as? List<Map<String, Any>> ?: emptyList()
            val tradesList = tradesRaw.map { t ->
                TradeEntity(
                    id = t["id"] as? String ?: UUID.randomUUID().toString(),
                    challengeId = t["challengeId"] as? String ?: ch.id,
                    tradeNumber = (t["tradeNumber"] as? Number)?.toInt() ?: 1,
                    resultCents = (t["resultCents"] as? Number)?.toLong() ?: 0L,
                    oldBalanceCents = (t["oldBalanceCents"] as? Number)?.toLong() ?: 0L,
                    newBalanceCents = (t["newBalanceCents"] as? Number)?.toLong() ?: 0L,
                    type = if ((t["type"] as? String) == "LOSS") TradeType.LOSS else TradeType.WIN,
                    attachmentPath = t["attachmentPath"] as? String,
                    timestamp = (t["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            }

            Pair(ch, tradesList)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncUserChallengeToFirestore(
        context: Context,
        userId: Int,
        challenge: ChallengeEntity,
        trades: List<TradeEntity>
    ) = withContext(Dispatchers.IO) {
        try {
            val db = getFirestore(context)
            val challengeMap = mapOf(
                "id" to challenge.id,
                "userId" to challenge.userId,
                "userName" to challenge.userName,
                "initialCapitalCents" to challenge.initialCapitalCents,
                "currentBalanceCents" to challenge.currentBalanceCents,
                "targetBalanceCents" to challenge.targetBalanceCents,
                "tradeCount" to challenge.tradeCount,
                "challengeStarted" to challenge.challengeStarted,
                "createdAt" to challenge.createdAt,
                "updatedAt" to challenge.updatedAt
            )
            val tradesMapList = trades.map { t ->
                mapOf(
                    "id" to t.id,
                    "challengeId" to t.challengeId,
                    "tradeNumber" to t.tradeNumber,
                    "resultCents" to t.resultCents,
                    "oldBalanceCents" to t.oldBalanceCents,
                    "newBalanceCents" to t.newBalanceCents,
                    "type" to t.type.name,
                    "attachmentPath" to t.attachmentPath,
                    "timestamp" to t.timestamp
                )
            }
            db.collection("user_challenges").document("user_$userId").set(
                mapOf(
                    "userId" to userId,
                    "challenge" to challengeMap,
                    "trades" to tradesMapList,
                    "lastSyncAt" to System.currentTimeMillis()
                )
            ).await()
        } catch (_: Exception) {}
    }

    suspend fun deleteMultipleCloudImages(context: Context, paths: List<String?>) = withContext(Dispatchers.IO) {
        try {
            val storage = getStorage(context)
            paths.filterNotNull().forEach { path ->
                if (path.startsWith("trade_images/") || path.startsWith("chat_images/")) {
                    try {
                        storage.reference.child(path).delete().await()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }
}
