import { Request, Response, NextFunction } from "express";
import * as admin from "firebase-admin";

export interface AuthenticatedRequest extends Request {
  uid?: string;
  appId?: string;
}

/**
 * Verifies:
 * 1. Authorization: Bearer <Firebase ID Token>
 * 2. X-Firebase-AppCheck: <Play Integrity App Check Token>
 *
 * Production safety invariant:
 * When NODE_ENV === "production", debug-/dev- bypass tokens are strictly rejected
 * even if ALLOW_DEV_UNAUTHENTICATED is set in the environment.
 */
export async function verifyFirebaseAndAppCheck(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  const isProduction = process.env.NODE_ENV === "production";
  const allowDevBypass = !isProduction && process.env.ALLOW_DEV_UNAUTHENTICATED === "true";

  const authHeader = req.header("Authorization") || "";
  const idToken = authHeader.startsWith("Bearer ")
    ? authHeader.slice("Bearer ".length).trim()
    : "";
  const appCheckToken = (req.header("X-Firebase-AppCheck") || "").trim();

  if (!idToken) {
    res.status(401).json({
      error: "Missing Authorization Bearer Firebase ID token."
    });
    return;
  }

  // Strictly block debug-/dev- tokens in production
  if (isProduction && (idToken.startsWith("debug-") || idToken.startsWith("dev-"))) {
    res.status(401).json({
      error: "Development tokens are forbidden in production."
    });
    return;
  }

  if (allowDevBypass && (idToken.startsWith("debug-") || idToken.startsWith("dev-"))) {
    req.uid = "dev-local-user";
    req.appId = "dev-local-app";
    next();
    return;
  }

  try {
    if (!admin.apps.length) {
      admin.initializeApp();
    }

    const decodedToken = await admin.auth().verifyIdToken(idToken, true);
    req.uid = decodedToken.uid;

    if (!appCheckToken) {
      res.status(401).json({
        error: "Missing X-Firebase-AppCheck Play Integrity token."
      });
      return;
    }

    const appCheckClaims = await admin.appCheck().verifyToken(appCheckToken);
    req.appId = appCheckClaims.appId;
    next();
  } catch (_err) {
    res.status(401).json({
      error: "Unauthorized: Invalid Firebase ID token or App Check token."
    });
  }
}

/**
 * Prompt-injection sanitizer:
 * 1. Strips system-override phrases ("ignore previous instructions", "disregard safety", etc.).
 * 2. Caps screen context size so malicious third-party apps cannot overflow the prompt window.
 */
export function sanitizeUntrustedText(raw: string, maxChars = 4000): string {
  if (!raw) return "";
  const truncated = raw.slice(0, maxChars);
  return truncated
    .replace(/ignore\s+(all\s+)?(previous|prior)\s+instructions/gi, "[FILTERED]")
    .replace(/disregard\s+(all\s+)?safety\s+rules/gi, "[FILTERED]")
    .replace(/system\s*override/gi, "[FILTERED]")
    .replace(/<\/?(system|assistant|tool_call|UntrustedScreenContent)>/gi, "");
}
