package com.example.env

import com.example.data.remote.ApiService
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository

/**
 * Production flavor environment (`src/prod`).
 * Contains zero references to scripted backends, seeded demo data, sample notifications, or simulations.
 */
class FlavorEnvironmentImpl : FlavorEnvironment {
    override val isDevFlavor: Boolean = false
    override val supportsSimulatedFeatures: Boolean = false
    override val supportsLatencyDebugPanel: Boolean = false

    override fun provideOptionalFakeApiService(): ApiService? = null

    override suspend fun seedDemoDataIfDev(
        memoryRepository: MemoryRepository,
        routineRepository: RoutineRepository
    ) {
        // Production starts completely empty — no demo contacts, memories, or routines.
    }
}
