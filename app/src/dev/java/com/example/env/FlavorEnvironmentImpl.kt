package com.example.env

import com.example.data.remote.ApiService
import com.example.data.remote.FakeBackend
import com.example.domain.model.RoutineTriggerType
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository

/**
 * Dev flavor environment (`src/dev`).
 * Provides FakeBackend for local development and optional demo data seeding.
 */
class FlavorEnvironmentImpl : FlavorEnvironment {
    override val isDevFlavor: Boolean = true
    override val supportsSimulatedFeatures: Boolean = true
    override val supportsLatencyDebugPanel: Boolean = true

    private val fakeBackendInstance = FakeBackend()

    override fun provideOptionalFakeApiService(): ApiService = fakeBackendInstance

    override suspend fun seedDemoDataIfDev(
        memoryRepository: MemoryRepository,
        routineRepository: RoutineRepository
    ) {
        val existingRoutines = routineRepository.getAllRoutinesSnapshot()
        if (existingRoutines.isEmpty()) {
            routineRepository.upsertRoutine(
                title = "Good Morning / सुप्रभात ब्रीफिंग",
                triggerType = RoutineTriggerType.TIME_DAILY,
                triggerValue = "07:30",
                actionsPrompt = "Good morning briefing",
                enabled = false
            )
        }
    }
}
