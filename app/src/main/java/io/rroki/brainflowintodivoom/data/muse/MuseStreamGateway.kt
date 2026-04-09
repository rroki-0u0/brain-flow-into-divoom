package io.rroki.brainflowintodivoom.data.muse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Minimal stream abstraction before BrainFlow integration.
 * Replace with a real BrainFlow-backed implementation during device integration.
 */
interface MuseStreamGateway {
    fun streamAlphaNormalized(): Flow<Double>
}

class PlaceholderMuseStreamGateway : MuseStreamGateway {
    override fun streamAlphaNormalized(): Flow<Double> = emptyFlow()
}
