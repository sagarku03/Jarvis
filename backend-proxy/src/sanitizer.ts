/**
 * Prompt Injection Sanitizer for screen context & untrusted text.
 * Strips jailbreak directives, system role overrides, and suspicious OTP/banking exfiltration prompts.
 */

const INJECTION_PATTERNS: RegExp[] = [
  /ignore\s+(all\s+)?(previous|prior|above)\s+(instructions|prompts|rules)/gi,
  /system\s*override/gi,
  /you\s+are\s+now\s+in\s+developer\s+mode/gi,
  /disregard\s+safety\s+gate/gi,
  /<\s*\/?\s*(system|instruction|tool_call|function_call)\s*>/gi,
  /\b(send|forward|share)\s+(the\s+)?(otp|password|cvv|pin)\s+to\b/gi,
];

export function sanitizeUntrustedContext(raw: string): string {
  if (!raw) return '';
  let cleaned = raw;
  for (const pattern of INJECTION_PATTERNS) {
    cleaned = cleaned.replace(pattern, '[REDACTED_INJECTION_ATTEMPT]');
  }
  return cleaned.slice(0, 4000);
}
