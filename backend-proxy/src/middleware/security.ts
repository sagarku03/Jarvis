import { Request, Response, NextFunction } from 'express';
import * as admin from 'firebase-admin';

// Initialize Firebase Admin SDK once (uses Application Default Credentials in Cloud Run)
if (!admin.apps.length) {
  try {
    admin.initializeApp();
  } catch {
    // Fallback for local test environments without ADC
  }
}

export interface AuthenticatedRequest extends Request {
  uid?: string;
  appCheckVerified?: boolean;
}

/**
 * Sliding-window per-user rate limiter & daily token/TTS character budget tracker.
 * Prevents abuse and runaway cloud costs.
 */
interface UserQuotaBucket {
  requestTimestamps: number[];
  dailyTokensUsed: number;
  dailyTtsCharsUsed: number;
  dayKey: string;
}

const userBuckets = new Map<string, UserQuotaBucket>();

export const QUOTA_LIMITS = {
  MAX_REQUESTS_PER_MINUTE: 30,
  MAX_DAILY_TOKENS: 120_000,
  MAX_DAILY_TTS_CHARS: 50_000,
};

function currentDayKey(): string {
  return new Date().toISOString().slice(0, 10);
}

export function getOrInitBucket(uid: string): UserQuotaBucket {
  const today = currentDayKey();
  const existing = userBuckets.get(uid);
  if (!existing || existing.dayKey !== today) {
    const fresh: UserQuotaBucket = {
      requestTimestamps: [],
      dailyTokensUsed: 0,
      dailyTtsCharsUsed: 0,
      dayKey: today,
    };
    userBuckets.set(uid, fresh);
    return fresh;
  }
  return existing;
}

export function checkSlidingWindowRateLimit(uid: string, nowMs: number = Date.now()): boolean {
  const bucket = getOrInitBucket(uid);
  const windowStart = nowMs - 60_000;
  bucket.requestTimestamps = bucket.requestTimestamps.filter((t) => t > windowStart);
  if (bucket.requestTimestamps.length >= QUOTA_LIMITS.MAX_REQUESTS_PER_MINUTE) {
    return false;
  }
  bucket.requestTimestamps.push(nowMs);
  return true;
}

export function recordTokenUsage(uid: string, tokens: number): boolean {
  const bucket = getOrInitBucket(uid);
  if (bucket.dailyTokensUsed + tokens > QUOTA_LIMITS.MAX_DAILY_TOKENS) {
    return false;
  }
  bucket.dailyTokensUsed += tokens;
  return true;
}

export function recordTtsUsage(uid: string, charCount: number): boolean {
  const bucket = getOrInitBucket(uid);
  if (bucket.dailyTtsCharsUsed + charCount > QUOTA_LIMITS.MAX_DAILY_TTS_CHARS) {
    return false;
  }
  bucket.dailyTtsCharsUsed += charCount;
  return true;
}

/**
 * Middleware: Verifies Firebase Auth ID Token (Authorization: Bearer <token>)
 * and Firebase App Check Token (X-Firebase-AppCheck: <token>).
 */
export async function verifyFirebaseSecurity(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  const authHeader = req.header('Authorization') || '';
  const appCheckToken = req.header('X-Firebase-AppCheck') || '';

  if (!authHeader.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Missing or invalid Authorization Bearer token' });
    return;
  }

  if (!appCheckToken) {
    res.status(401).json({ error: 'Missing X-Firebase-AppCheck header' });
    return;
  }

  const idToken = authHeader.slice('Bearer '.length).trim();
  const allowDevBypass =
    process.env.NODE_ENV === 'test' || process.env.ALLOW_DEV_UNAUTHENTICATED === 'true';

  try {
    if (allowDevBypass && (idToken.startsWith('debug-') || idToken.startsWith('dev-'))) {
      req.uid = 'debug-user-jarvis';
      req.appCheckVerified = true;
    } else {
      const [decodedToken] = await Promise.all([
        admin.auth().verifyIdToken(idToken, true),
        admin.appCheck().verifyToken(appCheckToken),
      ]);
      req.uid = decodedToken.uid;
      req.appCheckVerified = true;
    }

    const uid = req.uid || req.ip || 'anonymous';
    if (!checkSlidingWindowRateLimit(uid)) {
      res.status(429).json({
        error: 'Rate limit exceeded (max 30 requests/minute). Please wait a moment.',
      });
      return;
    }

    next();
  } catch (err) {
    res.status(403).json({
      error: 'Firebase Auth or App Check verification failed',
      details: err instanceof Error ? err.message : 'Unknown error',
    });
  }
}
