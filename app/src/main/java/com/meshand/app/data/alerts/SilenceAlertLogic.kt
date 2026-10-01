package com.meshand.app.data.alerts

import com.meshand.app.domain.model.MeshNode
import java.time.Duration
import java.time.Instant

/** Result of one evaluation: who just went silent, who came back, and the new alerted set. */
data class AlertDecisions(
    val wentSilent: List<MeshNode>,
    val cameBack: List<MeshNode>,
    val alerted: Set<Long>,
)

/**
 * Pure "teammate not heard" logic. A watched node alerts once when it has been silent for longer
 * than [threshold], and produces a "back" event the next time it is heard.
 */
object SilenceAlertLogic {
    fun evaluate(
        nodes: List<MeshNode>,
        watched: Set<Long>,
        threshold: Duration,
        now: Instant,
        alreadyAlerted: Set<Long>,
    ): AlertDecisions {
        val byId = nodes.associateBy { it.id }
        fun silent(node: MeshNode) = node.lastSeen?.let { Duration.between(it, now) > threshold } == true

        val wentSilent = watched.asSequence()
            .filter { it !in alreadyAlerted }
            .mapNotNull { byId[it] }
            .filter { !it.isOwnNode && silent(it) }
            .toList()
        val cameBack = alreadyAlerted.asSequence()
            .filter { it in watched }
            .mapNotNull { byId[it] }
            .filter { !silent(it) }
            .toList()
        val alerted = (alreadyAlerted intersect watched) + wentSilent.map { it.id } - cameBack.map { it.id }.toSet()
        return AlertDecisions(wentSilent, cameBack, alerted)
    }
}
