/**
 * Firebase ID Token verification on Cloudflare Workers using Web Crypto RS256 + Google JWKS.
 * Rejects all `dev-` / `debug-` tokens unless `ALLOW_DEV_TOKENS === "true"`.
 */

export interface VerifiedUser {
  uid: string;
  email?: string;
}

interface JwkKey {
  kid: string;
  kty: string;
  alg: string;
  use: string;
  n: string;
  e: string;
}

interface JwksResponse {
  keys: JwkKey[];
}

const GOOGLE_JWKS_URL =
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';

let cachedJwks: { keys: JwkKey[]; expiresAt: number } | null = null;

function base64UrlToUint8Array(base64Url: string): Uint8Array {
  const padding = '='.repeat((4 - (base64Url.length % 4)) % 4);
  const base64 = (base64Url + padding).replace(/-/g, '+').replace(/_/g, '/');
  const rawData = atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

function decodeJwtSegment<T>(segment: string): T {
  const bytes = base64UrlToUint8Array(segment);
  const json = new TextDecoder().decode(bytes);
  return JSON.parse(json) as T;
}

async function getGoogleJwks(): Promise<JwkKey[]> {
  const now = Date.now();
  if (cachedJwks && now < cachedJwks.expiresAt) {
    return cachedJwks.keys;
  }
  const resp = await fetch(GOOGLE_JWKS_URL);
  if (!resp.ok) {
    throw new Error(`Failed to fetch Google JWKS: ${resp.status}`);
  }
  const data = (await resp.json()) as JwksResponse;
  cachedJwks = {
    keys: data.keys || [],
    expiresAt: now + 3600_000,
  };
  return cachedJwks.keys;
}

export async function verifyFirebaseIdToken(
  authHeader: string | null | undefined,
  firebaseProjectId: string | undefined,
  allowDevTokens: boolean = false
): Promise<VerifiedUser> {
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    throw new Error('Missing or malformed Authorization Bearer header');
  }

  const token = authHeader.slice('Bearer '.length).trim();
  if (!token) {
    throw new Error('Empty Bearer token');
  }

  // Strictly reject dev-* / debug-* tokens unless ALLOW_DEV_TOKENS is explicitly true in dev
  if (token.startsWith('dev-') || token.startsWith('debug-')) {
    if (allowDevTokens) {
      return { uid: 'dev-user' };
    }
    throw new Error('Development tokens are forbidden');
  }

  if (!firebaseProjectId || firebaseProjectId.trim() === '') {
    throw new Error('FIREBASE_PROJECT_ID is not configured on Worker');
  }

  const parts = token.split('.');
  if (parts.length !== 3) {
    throw new Error('Invalid JWT structure');
  }

  const header = decodeJwtSegment<{ alg?: string; kid?: string }>(parts[0]);
  const payload = decodeJwtSegment<{
    iss?: string;
    aud?: string;
    sub?: string;
    exp?: number;
    iat?: number;
    email?: string;
  }>(parts[1]);

  if (header.alg !== 'RS256' || !header.kid) {
    throw new Error('Unsupported JWT algorithm or missing kid');
  }

  const expectedIssuer = `https://securetoken.google.com/${firebaseProjectId}`;
  if (payload.iss !== expectedIssuer) {
    throw new Error('JWT issuer mismatch');
  }
  if (payload.aud !== firebaseProjectId) {
    throw new Error('JWT audience mismatch');
  }

  const nowSec = Math.floor(Date.now() / 1000);
  if (!payload.exp || payload.exp <= nowSec) {
    throw new Error('JWT has expired');
  }
  if (!payload.sub || payload.sub.trim() === '') {
    throw new Error('JWT subject (uid) is empty');
  }

  const jwks = await getGoogleJwks();
  const jwk = jwks.find((k) => k.kid === header.kid);
  if (!jwk) {
    throw new Error('Matching JWK kid not found');
  }

  const cryptoKey = await crypto.subtle.importKey(
    'jwk',
    jwk,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify']
  );

  const signedContent = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const signatureBytes = base64UrlToUint8Array(parts[2]);

  const isValid = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    cryptoKey,
    signatureBytes,
    signedContent
  );

  if (!isValid) {
    throw new Error('Invalid JWT signature');
  }

  return {
    uid: payload.sub,
    email: payload.email,
  };
}
