import { firestoreClient, projectId, databaseId, getChannelById, getAllUsersList, isChannelOpenPublic } from './db.ts';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

export interface DeviceTokenRecord {
  id: string;
  token: string;
  userId: number;
  username: string;
  deviceId?: string;
  platform?: string;
  deviceModel?: string;
  createdAt: number;
  updatedAt: number;
}

const TOKENS_FILE = path.resolve('data/fcm_tokens.json');
const NOTIFICATIONS_LOG_FILE = path.resolve('data/dispatched_notifications.json');

// Track recently sent (messageId -> Set<token>) to prevent duplicate notifications
const sentMessageTokenMap = new Map<string, number>();
const DEDUP_TTL_MS = 10 * 60 * 1000; // 10 minutes

function pruneDedupCache(): void {
  const cutoff = Date.now() - DEDUP_TTL_MS;
  for (const [key, ts] of sentMessageTokenMap.entries()) {
    if (ts < cutoff) {
      sentMessageTokenMap.delete(key);
    }
  }
}

function getFirestoreBaseUrl(): string {
  const dbSegment = encodeURIComponent(databaseId || '(default)');
  return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/databases/${dbSegment}/documents`;
}

function loadLocalTokens(): Record<string, DeviceTokenRecord> {
  try {
    if (fs.existsSync(TOKENS_FILE)) {
      const data = fs.readFileSync(TOKENS_FILE, 'utf8');
      return JSON.parse(data);
    }
  } catch (err) {
    console.warn('[FCM Server] Warning reading fcm_tokens.json:', err);
  }
  return {};
}

function saveLocalTokens(tokens: Record<string, DeviceTokenRecord>): void {
  try {
    const dir = path.dirname(TOKENS_FILE);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    fs.writeFileSync(TOKENS_FILE, JSON.stringify(tokens, null, 2), 'utf8');
  } catch (err) {
    console.error('[FCM Server] Error saving fcm_tokens.json:', err);
  }
}

const memoryTokens = loadLocalTokens();

async function saveTokenToSharedFirestoreRest(record: DeviceTokenRecord): Promise<void> {
  try {
    const url = `${getFirestoreBaseUrl()}/user_fcm_tokens/${encodeURIComponent(record.id)}`;
    await fetch(url, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fields: {
          id: { stringValue: record.id },
          token: { stringValue: record.token },
          userId: { integerValue: String(record.userId) },
          username: { stringValue: record.username || '' },
          deviceId: { stringValue: record.deviceId || 'android_device' },
          platform: { stringValue: record.platform || 'android' },
          deviceModel: { stringValue: record.deviceModel || 'Android Device' },
          createdAt: { integerValue: String(record.createdAt) },
          updatedAt: { integerValue: String(record.updatedAt) },
        },
      }),
    });
  } catch {}
}

async function deleteTokenFromSharedFirestoreRest(docId: string): Promise<void> {
  try {
    const url = `${getFirestoreBaseUrl()}/user_fcm_tokens/${encodeURIComponent(docId)}`;
    await fetch(url, { method: 'DELETE' });
  } catch {}
}

async function querySharedFirestoreTokensForUsers(userIds: number[]): Promise<{ docId: string; token: string; userId: number }[]> {
  const results: { docId: string; token: string; userId: number }[] = [];
  if (userIds.length === 0) return results;

  // Firestore IN operator supports up to 30 values per query
  const chunks: number[][] = [];
  for (let i = 0; i < userIds.length; i += 30) {
    chunks.push(userIds.slice(i, i + 30));
  }

  for (const chunk of chunks) {
    try {
      const res = await fetch(`${getFirestoreBaseUrl()}:runQuery`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          structuredQuery: {
            from: [{ collectionId: 'user_fcm_tokens' }],
            where: {
              fieldFilter: {
                field: { fieldPath: 'userId' },
                op: 'IN',
                value: {
                  arrayValue: {
                    values: chunk.map((uid) => ({ integerValue: String(uid) })),
                  },
                },
              },
            },
          },
        }),
      });
      if (!res.ok) continue;
      const rows: any[] = await res.json();
      if (!Array.isArray(rows)) continue;
      for (const row of rows) {
        const doc = row?.document;
        const f = doc?.fields;
        if (!f) continue;
        const token = f.token?.stringValue ? String(f.token.stringValue).trim() : '';
        const uid = f.userId?.integerValue ? Number(f.userId.integerValue) : 0;
        const docName = String(doc?.name || '');
        const docId = f.id?.stringValue || docName.split('/').pop() || '';
        if (token && uid > 0) {
          results.push({ docId, token, userId: uid });
        }
      }
    } catch {}
  }

  return results;
}

export async function registerDeviceToken(params: {
  token: string;
  userId: number;
  username: string;
  deviceId?: string;
  platform?: string;
  deviceModel?: string;
}): Promise<DeviceTokenRecord> {
  const { token, userId, username, deviceId, platform, deviceModel } = params;
  const now = Date.now();
  const safeToken = token.trim();
  const cleanDeviceId = (deviceId || safeToken.substring(0, 20)).replace(/[^a-zA-Z0-9_-]/g, '_');
  const docId = `token_${userId}_${cleanDeviceId}`;

  // Clean up stale mappings if this exact FCM token or physical deviceId belonged to another session/token
  for (const [existingKey, existingRec] of Object.entries(memoryTokens)) {
    if (
      existingKey !== docId &&
      (existingRec.token === safeToken || (deviceId && existingRec.deviceId === deviceId))
    ) {
      delete memoryTokens[existingKey];
      if (firestoreClient) {
        firestoreClient.collection('user_fcm_tokens').doc(existingKey).delete().catch(() => {});
      }
      deleteTokenFromSharedFirestoreRest(existingKey).catch(() => {});
    }
  }

  const record: DeviceTokenRecord = {
    id: docId,
    token: safeToken,
    userId,
    username,
    deviceId: cleanDeviceId,
    platform: platform || 'android',
    deviceModel: deviceModel || 'Android Device',
    createdAt: memoryTokens[docId]?.createdAt || now,
    updatedAt: now,
  };

  memoryTokens[docId] = record;
  saveLocalTokens(memoryTokens);

  if (firestoreClient) {
    try {
      await firestoreClient.collection('user_fcm_tokens').doc(docId).set(record, { merge: true });
    } catch (err: any) {
      console.warn('[FCM Server] Firestore token registration note:', err?.message);
    }
  }
  await saveTokenToSharedFirestoreRest(record);

  return record;
}

export async function unregisterDeviceToken(userId: number, token?: string, deviceId?: string): Promise<void> {
  let changed = false;
  for (const [key, record] of Object.entries(memoryTokens)) {
    const matchesUser = record.userId === userId;
    const matchesToken = !token || record.token === token;
    const matchesDevice = !deviceId || record.deviceId === deviceId;
    if (matchesUser && matchesToken && matchesDevice) {
      delete memoryTokens[key];
      changed = true;
      if (firestoreClient) {
        firestoreClient.collection('user_fcm_tokens').doc(key).delete().catch(() => {});
      }
      await deleteTokenFromSharedFirestoreRest(key);
    }
  }

  if (changed) {
    saveLocalTokens(memoryTokens);
  }
}

async function pruneInvalidToken(badToken: string, docId?: string): Promise<void> {
  let changed = false;
  for (const [key, rec] of Object.entries(memoryTokens)) {
    if (rec.token === badToken || (docId && key === docId)) {
      delete memoryTokens[key];
      changed = true;
      if (firestoreClient) {
        firestoreClient.collection('user_fcm_tokens').doc(key).delete().catch(() => {});
      }
      deleteTokenFromSharedFirestoreRest(key).catch(() => {});
    }
  }
  if (docId) {
    deleteTokenFromSharedFirestoreRest(docId).catch(() => {});
  }
  if (changed) {
    saveLocalTokens(memoryTokens);
  }
}

export async function getTokensForUsers(userIds: number[]): Promise<{ token: string; userId: number; docId?: string }[]> {
  const userTokens: { token: string; userId: number; docId?: string }[] = [];
  const seen = new Set<string>();

  // 1. Search local memory/disk tokens
  for (const record of Object.values(memoryTokens)) {
    if (userIds.includes(record.userId) && record.token && !seen.has(record.token)) {
      seen.add(record.token);
      userTokens.push({ token: record.token, userId: record.userId, docId: record.id });
    }
  }

  // 2. Search Firestore Admin SDK tokens if initialized
  if (firestoreClient) {
    try {
      for (const uid of userIds) {
        const snap = await firestoreClient
          .collection('user_fcm_tokens')
          .where('userId', '==', uid)
          .get();
        snap.forEach((doc: any) => {
          const data = doc.data() as DeviceTokenRecord;
          if (data.token && !seen.has(data.token)) {
            seen.add(data.token);
            userTokens.push({ token: data.token, userId: data.userId, docId: doc.id });
          }
        });
      }
    } catch {}
  }

  // 3. Search shared Cloud Firestore via REST query (ensures Android WebView & Web tokens are always found)
  const restTokens = await querySharedFirestoreTokensForUsers(userIds);
  for (const rt of restTokens) {
    if (rt.token && !seen.has(rt.token)) {
      seen.add(rt.token);
      userTokens.push(rt);
    }
  }

  return userTokens;
}

export interface NotificationPayload {
  title: string;
  body: string;
  chatType: 'channel' | 'private';
  channelId?: string;
  partnerId?: number;
  messageId?: string;
  senderName?: string;
  senderId?: number;
}

let cachedAccessToken: { token: string; expiresAt: number } | null = null;

/**
 * Generate Google OAuth2 access token for FCM HTTP v1 using Service Account credentials or GCE Metadata Server
 */
async function getGoogleOAuth2AccessToken(): Promise<string | null> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) {
    return cachedAccessToken.token;
  }

  // 1. Try Service Account Key from server environment
  try {
    const rawKey = process.env.GCP_SERVICE_ACCOUNT_KEY || process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
    if (rawKey) {
      const jsonStr = rawKey.trim().startsWith('{')
        ? rawKey.trim()
        : Buffer.from(rawKey.trim(), 'base64').toString('utf8');
      const sa = JSON.parse(jsonStr);

      if (sa.client_email && sa.private_key) {
        const now = Math.floor(Date.now() / 1000);
        const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
        const claimSet = Buffer.from(
          JSON.stringify({
            iss: sa.client_email,
            scope: 'https://www.googleapis.com/auth/firebase.messaging',
            aud: 'https://oauth2.googleapis.com/token',
            exp: now + 3600,
            iat: now,
          })
        ).toString('base64url');

        const signer = crypto.createSign('RSA-SHA256');
        signer.update(`${header}.${claimSet}`);
        const signature = signer.sign(sa.private_key, 'base64url');
        const assertion = `${header}.${claimSet}.${signature}`;

        const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            assertion,
          }),
        });

        if (tokenRes.ok) {
          const data = (await tokenRes.json()) as any;
          if (data.access_token) {
            cachedAccessToken = {
              token: data.access_token,
              expiresAt: Date.now() + ((Number(data.expires_in) || 3500) - 60) * 1000,
            };
            return data.access_token;
          }
        }
      }
    }
  } catch (err: any) {
    console.warn('[FCM Server] Notice on Service Account OAuth2 token generation:', err?.message);
  }

  // 2. Try Google Cloud Run / GCE Metadata Server ADC (zero secrets needed on Cloud Run!)
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 1500);
    const metaRes = await fetch(
      'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token',
      {
        headers: { 'Metadata-Flavor': 'Google' },
        signal: controller.signal,
      }
    );
    clearTimeout(timer);
    if (metaRes.ok) {
      const metaJson = (await metaRes.json()) as any;
      if (metaJson?.access_token) {
        cachedAccessToken = {
          token: metaJson.access_token,
          expiresAt: Date.now() + ((Number(metaJson.expires_in) || 1800) - 60) * 1000,
        };
        return metaJson.access_token;
      }
    }
  } catch {}

  return null;
}

export async function sendNotificationToUsers(
  recipientUserIds: number[],
  payload: NotificationPayload
): Promise<{ sentCount: number; failedCount: number; skippedDuplicates?: number }> {
  pruneDedupCache();

  // Strictly exclude sender
  const filteredUserIds = Array.from(new Set(recipientUserIds.filter((id) => id > 0 && id !== payload.senderId)));
  if (filteredUserIds.length === 0) {
    return { sentCount: 0, failedCount: 0 };
  }

  const userTokens = await getTokensForUsers(filteredUserIds);
  if (userTokens.length === 0) {
    return { sentCount: 0, failedCount: 0 };
  }

  let sentCount = 0;
  let failedCount = 0;
  let skippedDuplicates = 0;

  const oauthToken = await getGoogleOAuth2AccessToken();
  const serverKey = process.env.FCM_SERVER_KEY || process.env.FIREBASE_SERVER_KEY;
  const collapseTag = payload.messageId || `sami_${payload.chatType}_${payload.channelId || payload.partnerId || 'msg'}`;
  const androidChannelId = payload.chatType === 'private' ? 'sami_private_messages' : 'sami_channel_messages';

  for (const { token, userId, docId } of userTokens) {
    const dedupKey = payload.messageId ? `${payload.messageId}::${token}` : '';
    if (dedupKey && sentMessageTokenMap.has(dedupKey)) {
      skippedDuplicates++;
      continue;
    }

    let sent = false;

    const dataMap: Record<string, string> = {
      title: payload.title,
      body: payload.body,
      chatType: payload.chatType,
      channelId: payload.channelId || '',
      partnerId: payload.partnerId ? String(payload.partnerId) : '',
      messageId: payload.messageId || '',
      senderName: payload.senderName || '',
      senderId: payload.senderId ? String(payload.senderId) : '',
      recipientId: String(userId),
    };

    // 1. FCM HTTP v1 API
    if (oauthToken) {
      try {
        const v1Url = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;
        const v1Body = {
          message: {
            token,
            notification: {
              title: payload.title,
              body: payload.body,
            },
            android: {
              priority: 'HIGH',
              collapse_key: collapseTag.slice(0, 64),
              notification: {
                channel_id: androidChannelId,
                title: payload.title,
                body: payload.body,
                sound: 'default',
                tag: collapseTag.slice(0, 64),
                notification_priority: 'PRIORITY_HIGH',
                visibility: payload.chatType === 'private' ? 'PRIVATE' : 'PUBLIC',
                color: '#F59E0B',
                click_action: 'OPEN_SAMI_CHAT',
              },
            },
            webpush: {
              headers: { Urgency: 'high' },
              notification: {
                title: payload.title,
                body: payload.body,
                icon: '/icon-192.png',
                badge: '/favicon.png',
                tag: collapseTag,
                renotify: true,
              },
              fcm_options: {
                link: '/',
              },
            },
            data: dataMap,
          },
        };

        const res = await fetch(v1Url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${oauthToken}`,
          },
          body: JSON.stringify(v1Body),
        });

        if (res.ok) {
          sent = true;
          sentCount++;
          if (dedupKey) sentMessageTokenMap.set(dedupKey, Date.now());
        } else {
          const errText = await res.text().catch(() => '');
          if (
            res.status === 404 ||
            errText.includes('UNREGISTERED') ||
            errText.includes('registration-token-not-registered')
          ) {
            await pruneInvalidToken(token, docId);
          }
          if (!serverKey) {
            failedCount++;
          }
        }
      } catch (err: any) {
        console.warn(`[FCM Server] HTTP v1 send error for user ${userId}:`, err?.message);
        if (!serverKey) failedCount++;
      }
    }

    // 2. Legacy FCM HTTP API fallback if configured
    if (!sent && serverKey) {
      try {
        const legacyRes = await fetch('https://fcm.googleapis.com/fcm/send', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `key=${serverKey}`,
          },
          body: JSON.stringify({
            to: token,
            priority: 'high',
            collapse_key: collapseTag.slice(0, 64),
            notification: {
              title: payload.title,
              body: payload.body,
              sound: 'default',
              tag: collapseTag.slice(0, 64),
              android_channel_id: androidChannelId,
            },
            data: dataMap,
          }),
        });

        const legacyJson: any = await legacyRes.json().catch(() => null);
        if (legacyRes.ok && (!legacyJson || Number(legacyJson.success) > 0)) {
          sent = true;
          sentCount++;
          if (dedupKey) sentMessageTokenMap.set(dedupKey, Date.now());
        } else {
          const errCode = legacyJson?.results?.[0]?.error || '';
          if (errCode === 'NotRegistered' || errCode === 'InvalidRegistration') {
            await pruneInvalidToken(token, docId);
          }
          failedCount++;
        }
      } catch {
        failedCount++;
      }
    }

    if (!sent && !oauthToken && !serverKey) {
      failedCount++;
    }
  }

  // Record dispatched notification in local audit log
  try {
    let logs: any[] = [];
    if (fs.existsSync(NOTIFICATIONS_LOG_FILE)) {
      logs = JSON.parse(fs.readFileSync(NOTIFICATIONS_LOG_FILE, 'utf8'));
    }
    logs.push({
      timestamp: Date.now(),
      messageId: payload.messageId || null,
      chatType: payload.chatType,
      recipientCount: userTokens.length,
      sentCount,
      failedCount,
      skippedDuplicates,
      credentialsAvailable: Boolean(oauthToken || serverKey),
    });
    if (logs.length > 100) logs = logs.slice(-100);
    const dir = path.dirname(NOTIFICATIONS_LOG_FILE);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(NOTIFICATIONS_LOG_FILE, JSON.stringify(logs, null, 2), 'utf8');
  } catch {}

  return { sentCount, failedCount, skippedDuplicates };
}

/**
 * Privacy-enforced push dispatcher used after a message is persisted to the shared DB.
 * Ensures private chats only notify the exact recipient and hidden/protected channels
 * only notify authorized members without leaking content.
 */
export async function dispatchAuthorizedChatPush(params: {
  senderId: number;
  senderName: string;
  senderRole: string;
  chatType: 'channel' | 'private';
  channelId?: string;
  receiverId?: number;
  messageId?: string;
  snippet?: string;
}): Promise<{ sentCount: number; failedCount: number }> {
  const { senderId, senderName, senderRole, chatType, channelId, receiverId, messageId, snippet } = params;

  if (chatType === 'private') {
    if (!receiverId || receiverId <= 0 || receiverId === senderId) {
      return { sentCount: 0, failedCount: 0 };
    }
    const title = senderId === 1 ? '👑 رسالة خاصة من القائد Sami' : `💬 رسالة خاصة من ${senderName}`;
    // Keep body generic or safe for lockscreen privacy (Android channel also uses VISIBILITY_PRIVATE)
    const body = snippet && snippet.trim() ? snippet.trim().slice(0, 100) : 'لديك رسالة خاصة جديدة';
    return sendNotificationToUsers([receiverId], {
      title,
      body,
      chatType: 'private',
      partnerId: senderId,
      messageId,
      senderName,
      senderId,
    });
  }

  if (!channelId) {
    return { sentCount: 0, failedCount: 0 };
  }

  const channel = await getChannelById(channelId);
  if (!channel) {
    return { sentCount: 0, failedCount: 0 };
  }

  // Enforce sender access check
  const openPublic = isChannelOpenPublic(channel);
  const authorizedMembers = Array.from(
    new Set([...(channel.allowedMemberIds || []), ...(channel.memberIds || []), 1])
  ).filter((id) => id > 0);

  if (!openPublic && senderRole !== 'owner' && senderId !== 1 && !authorizedMembers.includes(senderId)) {
    return { sentCount: 0, failedCount: 0 };
  }

  let recipientIds: number[] = [];
  if (channel.visibility === 'hidden' || !openPublic) {
    // Strictly send ONLY to authorized members of hidden / password-protected / private channels!
    recipientIds = authorizedMembers;
  } else {
    // Open public channel: send to all registered users + joined members
    const allUsers = await getAllUsersList();
    recipientIds = Array.from(new Set([...allUsers.map((u) => u.id), ...authorizedMembers]));
  }

  const senderPrefix =
    senderRole === 'owner' || senderId === 1
      ? '👑 القائد Sami'
      : senderRole === 'moderator'
        ? `🛡️ المشرف ${senderName}`
        : senderName;

  return sendNotificationToUsers(recipientIds, {
    title: `${senderPrefix} • ${channel.name}`,
    body: snippet && snippet.trim() ? snippet.trim().slice(0, 100) : 'رسالة جديدة في القناة',
    chatType: 'channel',
    channelId: channel.id,
    messageId,
    senderName,
    senderId,
  });
}

// Background outbox worker for Android APK / direct-Firestore message dispatches
let outboxPollingActive = false;
async function pollPendingFcmOutbox(): Promise<void> {
  if (outboxPollingActive) return;
  outboxPollingActive = true;
  try {
    const res = await fetch(`${getFirestoreBaseUrl()}:runQuery`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        structuredQuery: {
          from: [{ collectionId: 'fcm_outbox' }],
          where: {
            fieldFilter: {
              field: { fieldPath: 'status' },
              op: 'EQUAL',
              value: { stringValue: 'pending' },
            },
          },
          limit: 15,
        },
      }),
    });
    if (!res.ok) return;
    const rows: any[] = await res.json();
    if (!Array.isArray(rows)) return;

    const now = Date.now();
    for (const row of rows) {
      const doc = row?.document;
      const f = doc?.fields;
      if (!doc?.name || !f) continue;

      const jobId = f.id?.stringValue || String(doc.name).split('/').pop() || '';
      const createdAt = Number(f.createdAt?.integerValue || 0);
      // Skip stale jobs older than 15 minutes and mark expired
      if (createdAt > 0 && now - createdAt > 15 * 60 * 1000) {
        await fetch(`https://firestore.googleapis.com/v1/${doc.name}?updateMask.fieldPaths=status`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ fields: { status: { stringValue: 'expired' } } }),
        }).catch(() => {});
        continue;
      }

      const senderId = Number(f.senderId?.integerValue || 0);
      const senderName = String(f.senderName?.stringValue || 'Sami');
      const chatType = f.chatType?.stringValue === 'private' ? 'private' : 'channel';
      const channelId = f.channelId?.stringValue || undefined;
      const partnerId = Number(f.partnerId?.integerValue || 0);
      const messageId = f.messageId?.stringValue || jobId;
      const bodyText = String(f.body?.stringValue || 'رسالة جديدة');
      const targetUserIds: number[] = Array.isArray(f.targetUserIds?.arrayValue?.values)
        ? f.targetUserIds.arrayValue.values
            .map((v: any) => Number(v.integerValue || 0))
            .filter((n: number) => n > 0)
        : [];

      const receiverId = chatType === 'private' ? targetUserIds[0] : undefined;

      // Mark processing/processed first so multiple ticks don't re-send
      await fetch(`https://firestore.googleapis.com/v1/${doc.name}?updateMask.fieldPaths=status&updateMask.fieldPaths=processedAt`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fields: {
            status: { stringValue: 'dispatched' },
            processedAt: { integerValue: String(Date.now()) },
          },
        }),
      }).catch(() => {});

      await dispatchAuthorizedChatPush({
        senderId,
        senderName,
        senderRole: senderId === 1 ? 'owner' : 'user',
        chatType,
        channelId,
        receiverId: receiverId || (partnerId && partnerId !== senderId ? partnerId : undefined),
        messageId,
        snippet: bodyText,
      });
    }
  } catch {
    // Ignore transient network errors
  } finally {
    outboxPollingActive = false;
  }
}

setInterval(() => {
  pollPendingFcmOutbox().catch(() => {});
}, 6000);
