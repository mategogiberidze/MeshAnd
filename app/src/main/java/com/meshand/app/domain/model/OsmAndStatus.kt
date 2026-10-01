package com.meshand.app.domain.model

/** State of the bridge that mirrors mesh nodes onto the OsmAnd map. */
sealed interface OsmAndStatus {
    /** The user hasn't switched "Show on OsmAnd" on. */
    data object Off : OsmAndStatus

    /** No OsmAnd build with the AIDL V2 service is installed. */
    data object NotInstalled : OsmAndStatus

    data class Connecting(val detail: String) : OsmAndStatus

    /**
     * OsmAnd is reachable but rejects our calls. Since OsmAnd 5.3, a new client app is disabled
     * until the user enables it under OsmAnd → Menu → Plugins.
     */
    data class NotAllowed(val packageName: String) : OsmAndStatus

    data class Showing(val packageName: String, val nodeCount: Int) : OsmAndStatus

    data class Error(val message: String) : OsmAndStatus
}
