package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.monitor.UserActionRecord
import com.zamateka.smartfollow.sync.ActionsResponse
import com.zamateka.smartfollow.sync.SequencedAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration

/**
 * Shared actions, numbered so that devices can ask for those they have not seen yet. Kept in
 * memory: after a restart the [epoch] changes and devices start over from the new sequence.
 */
class ActionLog(private val capacity: Int = 500) {
    val epoch: String = UUID.randomUUID().toString()
    private val actions = ArrayDeque<SequencedAction>()
    private val lastSequence = MutableStateFlow(0L)

    /** Returns `false` if an action with the same id is already logged. */
    @Synchronized
    fun append(record: UserActionRecord): Boolean {
        if (actions.any { it.record.id == record.id }) return false
        actions.addLast(SequencedAction(lastSequence.value + 1, record))
        if (actions.size > capacity) actions.removeFirst()
        lastSequence.value++
        return true
    }

    @Synchronized
    fun after(sequence: Long): ActionsResponse =
        ActionsResponse(epoch, lastSequence.value, actions.filter { it.sequence > sequence })

    /**
     * Long polling: waits up to [timeout] for an action after [sequence], so that a device learns
     * of it at once without asking every few seconds. A device that knows another [epoch] (the
     * server restarted) gets the answer right away, to start over.
     */
    suspend fun await(sequence: Long, epoch: String?, timeout: Duration): ActionsResponse {
        if (epoch == this.epoch) withTimeoutOrNull(timeout) { lastSequence.first { it > sequence } }
        return after(sequence)
    }
}
