package com.example.data.remote

import com.example.di.Inject
import com.example.di.Singleton
import okhttp3.ResponseBody

/**
 * Release source-set implementation of [FakeBackend].
 * Release builds strictly fail closed and never return simulated/scripted responses.
 */
@Singleton
class FakeBackend @Inject constructor() : ApiService {
    override suspend fun agentStep(request: AgentStepRequestDto): AgentStepResponseDto {
        throw IllegalStateException("Backend is not configured. FakeBackend is disabled in release builds.")
    }

    override suspend fun synthesizeSpeech(request: TtsRequestDto): ResponseBody {
        throw IllegalStateException("Backend TTS is not configured. FakeBackend is disabled in release builds.")
    }
}
