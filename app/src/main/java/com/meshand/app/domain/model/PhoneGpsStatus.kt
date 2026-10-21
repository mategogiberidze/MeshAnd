package com.meshand.app.domain.model

import java.time.Instant

/** The "use phone GPS for my radio" override, as the UI and notification show it. */
sealed interface PhoneGpsStatus {
    /** Switched off in Settings. */
    data object Off : PhoneGpsStatus

    /** Switched on, but MeshAnd may not read the phone's location. */
    data object NoPermission : PhoneGpsStatus

    /** Switched on, waiting for the radio connection. */
    data object WaitingForRadio : PhoneGpsStatus

    /** The phone's Location setting is off. */
    data object LocationOff : PhoneGpsStatus

    /** Android won't give MeshAnd the location in the background until the app is opened once. */
    data object NeedsApp : PhoneGpsStatus

    data object WaitingForFix : PhoneGpsStatus

    data class Sending(val lastSentAt: Instant, val accuracyMeters: Int) : PhoneGpsStatus
}
