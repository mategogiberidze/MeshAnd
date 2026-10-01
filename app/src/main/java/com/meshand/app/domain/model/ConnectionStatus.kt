package com.meshand.app.domain.model

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Scanning : ConnectionStatus

    /** [detail] describes the current step (bonding, GATT connect, config handshake phase). */
    data class Connecting(val radio: DiscoveredRadio, val detail: String) : ConnectionStatus
    data class Connected(val radio: DiscoveredRadio) : ConnectionStatus

    /** Was connected; the link dropped and is being re-established. Last-known nodes are kept. */
    data class Reconnecting(val radio: DiscoveredRadio, val detail: String) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}
