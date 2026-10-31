/**
 * Shared Wire Schema between Android (`com.example.data.remote.*`) and Cloudflare Worker.
 */

export interface AgentMessageDto {
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL' | string;
  content: string;
  toolName?: string | null;
}

export interface AgentToolDto {
  name: string;
  description: string;
  jsonSchema: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | string;
}

export interface AgentScreenContextDto {
  packageName: string;
  summaryText: string;
  structuredJson?: string;
}

export interface AgentStepRequestDto {
  messages: AgentMessageDto[];
  tools: AgentToolDto[];
  screenContext?: AgentScreenContextDto | null;
  languageCode?: string;
}

export interface ToolCallDto {
  id: string;
  name: string;
  args: Record<string, string>;
}

export interface AgentStepResponseDto {
  assistantText?: string | null;
  toolCalls: ToolCallDto[];
}

export interface TtsRequestDto {
  text: string;
  languageCode?: string;
  voiceId?: string;
  speed?: number;
  pitch?: number;
}

export function validateAgentStepRequest(body: unknown): {
  valid: boolean;
  data?: AgentStepRequestDto;
  error?: string;
} {
  if (!body || typeof body !== 'object') {
    return { valid: false, error: 'Request body must be a JSON object' };
  }
  const obj = body as Record<string, unknown>;
  if (!Array.isArray(obj.messages) || obj.messages.length === 0) {
    return { valid: false, error: 'messages must be a non-empty array' };
  }
  if (!Array.isArray(obj.tools)) {
    return { valid: false, error: 'tools must be an array' };
  }
  for (const msg of obj.messages) {
    if (!msg || typeof msg !== 'object') {
      return { valid: false, error: 'Each message must be an object' };
    }
    const m = msg as Record<string, unknown>;
    if (typeof m.role !== 'string' || typeof m.content !== 'string') {
      return { valid: false, error: 'Each message requires string role and content' };
    }
  }
  return {
    valid: true,
    data: {
      messages: obj.messages as AgentMessageDto[],
      tools: obj.tools as AgentToolDto[],
      screenContext: (obj.screenContext as AgentScreenContextDto | null | undefined) ?? null,
      languageCode: typeof obj.languageCode === 'string' ? obj.languageCode : 'en-IN',
    },
  };
}
