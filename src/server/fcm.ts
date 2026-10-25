import { firestoreClient, projectId } from './db.ts';
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
  const docId = `token_${userId}_${deviceId || safeToken.substring(0, 16)}`;

  const record: DeviceTokenRecord = {
    id: docId,
    token: safeToken,
    userId,
    username,
    deviceId: deviceId || 'android_device',
    platform: platform || 'android',
    deviceModel: deviceModel || 'Android Device',
    createdAt: memoryTokens[docId]?.createdAt || now,
    updatedAt: now,
  };

  memoryTokens[docId] = record;
  saveLocalTokens(memoryTokens);

  if (firestoreClient) {
    try {
      await firestoreClient.collection('user_fcm_tokens').doc(docId).set(record);
      console.log(`[FCM Server] Registered token for user ${userId} (${username}) in Firestore`);
    } catch (err: any) {
      console.warn('[FCM Server] Firestore token registration note:', err?.message);
    }
  }

  return record;
}

export async function unregisterDeviceToken(userId: number, token?: string): Promise<void> {
  let changed = false;
  for (const [key, record] of Object.entries(memoryTokens)) {
    if (record.userId === userId && (!token || record.token === token)) {
      delete memoryTokens[key];
      changed = true;
      if (firestoreClient) {
        try {
          await firestoreClient.collection('user_fcm_tokens').doc(key).delete();
        } catch {}
      }
    }
  }

  if (changed) {
    saveLocalTokens(memoryTokens);
    console.log(`[FCM Server] Unregistered token for user ${userId}`);
  }
}

export async function getTokensForUsers(userIds: number[]): Promise<{ token: string; userId: number }[]> {
  const userTokens: { token: string; userId: number }[] = [];
  const seen = new Set<string>();

  // 1. Search memory tokens
  for (const record of Object.values(memoryTokens)) {
    if (userIds.includes(record.userId) && record.token && !seen.has(record.token)) {
      seen.add(record.token);
      userTokens.push({ token: record.token, userId: record.userId });
    }
  }

  // 2. Search Firestore tokens if client is connected
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
            userTokens.push({ token: data.token, userId: data.userId });
          }
        });
      }
    } catch (err: any) {
      console.warn('[FCM Server] Firestore token query note:', err?.message);
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

/**
 * Generate Google OAuth2 access token for FCM HTTP v1 using Service Account credentials
 */
async function getGoogleOAuth2AccessToken(): Promise<string | null> {
  try {
    const rawKey = process.env.GCP_SERVICE_ACCOUNT_KEY;
    if (!rawKey) return null;

    const jsonStr = rawKey.trim().startsWith('{')
      ? rawKey.trim()
      : Buffer.from(rawKey.trim(), 'base64').toString('utf8');
    const sa = JSON.parse(jsonStr);

    if (!sa.client_email || !sa.private_key) return null;

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
      return data.access_token || null;
    }
  } catch (err: any) {
    console.warn('[FCM Server] Notice on OAuth2 token generation:', err?.message);
  }
  return null;
}

export async function sendNotificationToUsers(
  recipientUserIds: number[],
  payload: NotificationPayload
): Promise<{ sentCount: number; failedCount: number }> {
  // Exclude sender
  const filteredUserIds = recipientUserIds.filter((id) => id !== payload.senderId);
  if (filteredUserIds.length === 0) {
    return { sentCount: 0, failedCount: 0 };
  }

  const userTokens = await getTokensForUsers(filteredUserIds);
  if (userTokens.length === 0) {
    console.log(`[FCM Server] No device tokens found for users: ${filteredUserIds.join(', ')}`);
    return { sentCount: 0, failedCount: 0 };
  }

  console.log(
    `[FCM Server] Dispatching push notification to ${userTokens.length} devices for users [${filteredUserIds.join(', ')}]: "${payload.title}"`
  );

  let sentCount = 0;
  let failedCount = 0;

  // 1. Try FCM HTTP v1 using OAuth2 Bearer token
  const oauthToken = await getGoogleOAuth2AccessToken();
  const serverKey = process.env.FCM_SERVER_KEY || process.env.FIREBASE_SERVER_KEY;

  for (const { token, userId } of userTokens) {
    let sent = false;

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
              notification: {
                channel_id:
                  payload.chatType === 'private'
                    ? 'sami_private_messages'
                    : 'sami_channel_messages',
                title: payload.title,
                body: payload.body,
                sound: 'default',
                notification_priority: 'PRIORITY_HIGH',
                visibility: payload.chatType === 'private' ? 'PRIVATE' : 'PUBLIC',
                color: '#F59E0B',
              },
            },
            data: {
              title: payload.title,
              body: payload.body,
              chatType: payload.chatType,
              channelId: payload.channelId || '',
              partnerId: payload.partnerId ? String(payload.partnerId) : '',
              messageId: payload.messageId || '',
              senderName: payload.senderName || '',
              recipientId: String(userId),
            },
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
        }
      } catch (err: any) {
        console.warn(`[FCM Server] Error sending via HTTP v1 to ${token.substring(0, 10)}:`, err?.message);
      }
    }

    // 2. Try legacy FCM HTTP API if v1 was not used or failed
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
            notification: {
              title: payload.title,
              body: payload.body,
              sound: 'default',
              android_channel_id:
                payload.chatType === 'private'
                  ? 'sami_private_messages'
                  : 'sami_channel_messages',
            },
            data: {
              title: payload.title,
              body: payload.body,
              chatType: payload.chatType,
              channelId: payload.channelId || '',
              partnerId: payload.partnerId ? String(payload.partnerId) : '',
              messageId: payload.messageId || '',
              senderName: payload.senderName || '',
              recipientId: String(userId),
            },
          }),
        });

        if (legacyRes.ok) {
          sent = true;
          sentCount++;
        } else {
          failedCount++;
        }
      } catch {
        failedCount++;
      }
    }

    if (!sent && !oauthToken && !serverKey) {
      // Local dev simulation
      sentCount++;
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
      payload,
      recipientCount: userTokens.length,
      sentCount,
      failedCount,
    });
    if (logs.length > 100) logs = logs.slice(-100);
    fs.writeFileSync(NOTIFICATIONS_LOG_FILE, JSON.stringify(logs, null, 2), 'utf8');
  } catch {}

  return { sentCount, failedCount };
}
