package com.example.env

import com.example.data.remote.ApiService
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository

/**
 * Abstracts flavor-specific capabilities between `src/dev` and `src/prod`.
 * In `prod`, `provideOptionalFakeApiService()` returns null, `seedDemoDataIfDev` is a no-op,
 * and no demo or simulation classes exist on the classpath.
 */
interface FlavorEnvironment {
    val isDevFlavor: Boolean
    val supportsSimulatedFeatures: Boolean
    val supportsLatencyDebugPanel: Boolean

    fun provideOptionalFakeApiService(): ApiService?

    suspend fun seedDemoDataIfDev(
        memoryRepository: MemoryRepository,
        routineRepository: RoutineRepository
    )
}
