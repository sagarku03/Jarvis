import express from "express";
import helmet from "helmet";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { verifyFirebaseAndAppCheck } from "./middleware/security";
import {
  AgentStepRequestDto,
  runVertexAgentStep,
  transcribeAudioChunk,
  synthesizeSpeechStream,
  TtsNotConfiguredError
} from "./services/vertexGemini";

export const app = express();

app.use(helmet());
app.use(cors({ origin: false }));
app.use(express.json({ limit: "256kb" }));

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Rate limit exceeded. Try again in a minute." }
});

app.get("/healthz", (_req, res) => {
  res.status(200).json({ status: "ok", service: "jarvis-backend-proxy" });
});

app.use("/v1", apiLimiter, verifyFirebaseAndAppCheck);

/**
 * POST /v1/agent/step
 * Body: { messages[], tools[], screenContext?, languageCode }
 * Returns: { assistantText, toolCalls[{ id, name, args }] }
 */
app.post("/v1/agent/step", async (req, res) => {
  try {
    const body = req.body as AgentStepRequestDto;
    if (!body || !Array.isArray(body.messages) || !Array.isArray(body.tools)) {
      res.status(400).json({ error: "Invalid request schema: messages[] and tools[] required." });
      return;
    }

    const decision = await runVertexAgentStep(body);
    res.status(200).json(decision);
  } catch (_err) {
    res.status(500).json({
      assistantText: "Backend proxy failed to process agent step.",
      toolCalls: []
    });
  }
});

/**
 * POST /v1/stt
 * Body: { audioBase64, sampleRateHz, languageCode }
 * Returns: { transcript, confidence, isFinal }
 */
app.post("/v1/stt", async (req, res) => {
  try {
    const { audioBase64, sampleRateHz, languageCode } = req.body || {};
    if (!audioBase64 || typeof audioBase64 !== "string") {
      res.status(400).json({ error: "Missing audioBase64 payload." });
      return;
    }

    const result = await transcribeAudioChunk(
      audioBase64,
      languageCode || "en-IN",
      Number(sampleRateHz) || 16000
    );
    res.status(200).json(result);
  } catch (_err) {
    res.status(500).json({ error: "Speech-to-text failed." });
  }
});

/**
 * POST /v1/tts
 * Body: { text, languageCode, voiceId, speed, pitch }
 * Returns: audio/mpeg stream (or 501 when cloud TTS is not configured)
 */
app.post("/v1/tts", async (req, res) => {
  try {
    const { text, languageCode, voiceId, speed, pitch } = req.body || {};
    if (!text || typeof text !== "string") {
      res.status(400).json({ error: "Missing text payload." });
      return;
    }

    const audioBuffer = await synthesizeSpeechStream(
      text,
      languageCode || "en-IN",
      voiceId || "en-IN-Neural2-D",
      Number(speed) || 1.0,
      Number(pitch) || 1.0
    );

    res.setHeader("Content-Type", "audio/mpeg");
    res.setHeader("Content-Length", audioBuffer.length.toString());
    res.status(200).send(audioBuffer);
  } catch (err) {
    if (err instanceof TtsNotConfiguredError) {
      res.status(501).json({ error: err.message });
      return;
    }
    res.status(500).json({ error: "Text-to-speech synthesis failed." });
  }
});

if (require.main === module) {
  const port = Number(process.env.PORT) || 8080;
  app.listen(port, () => {
    process.stdout.write(`JARVIS backend proxy listening on port ${port}\n`);
  });
}
