package com.nuvio.tv.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an install the user started is up to, so the banner can say so and ignore extra taps. */
enum class InstallPhase { IDLE, PREPARING, INSTALLING }

/**
 * Shared between the banner's view model and the receiver that hears back from Android. Only installs the user
 * started show up here; background installs never touch it.
 */
object UpdateInstallTracker {
    private val _phase = MutableStateFlow(InstallPhase.IDLE)
    val phase: StateFlow<InstallPhase> = _phase.asStateFlow()

    /** False when an install is already under way, so a second tap does nothing. */
    fun tryStart(): Boolean = _phase.compareAndSet(InstallPhase.IDLE, InstallPhase.PREPARING)

    /** Android showed (or is showing) its confirmation; from here the update is on its way. */
    fun installing() { _phase.value = InstallPhase.INSTALLING }

    fun idle() { _phase.value = InstallPhase.IDLE }
}
