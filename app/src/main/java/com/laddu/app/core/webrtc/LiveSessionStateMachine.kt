package com.laddu.app.core.webrtc

import com.laddu.app.core.model.LiveState

sealed interface LiveEvent {
    data object RequestSent : LiveEvent
    data object OfferReceived : LiveEvent
    data object IceConnected : LiveEvent
    data object IceDisconnected : LiveEvent
    data object IceFailed : LiveEvent
    /** Camera never produced an offer / connection never completed in time. */
    data object Timeout : LiveEvent
    data object CameraOffline : LiveEvent
    data object UserReconnect : LiveEvent
    data object UserStop : LiveEvent
    /** Disconnected state lasted too long without recovering. */
    data object DisconnectGraceExpired : LiveEvent
}

sealed interface LiveAction {
    data object None : LiveAction
    /** Tear down the peer and start a brand-new session. */
    data class Reconnect(val attempt: Int, val delayMs: Long) : LiveAction
    data object Teardown : LiveAction
}

data class LiveStep(val state: LiveState, val action: LiveAction, val message: String? = null)

/**
 * Viewer connection lifecycle as a pure state machine (unit-tested): connect -> live ->
 * disconnect -> bounded automatic reconnects with back-off -> failed (manual reconnect).
 */
class LiveSessionStateMachine(private val maxAutoReconnects: Int = 3) {
    var state: LiveState = LiveState.IDLE
        private set
    private var attempts = 0

    private fun backoff(attempt: Int) = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)

    private fun step(s: LiveState, a: LiveAction = LiveAction.None, m: String? = null): LiveStep {
        state = s
        return LiveStep(s, a, m)
    }

    fun onEvent(e: LiveEvent): LiveStep = when (e) {
        LiveEvent.RequestSent -> step(LiveState.REQUESTING)
        LiveEvent.OfferReceived -> if (state == LiveState.REQUESTING || state == LiveState.RECONNECTING) step(LiveState.CONNECTING) else step(state)
        LiveEvent.IceConnected -> { attempts = 0; step(LiveState.LIVE) }
        LiveEvent.IceDisconnected ->
            if (state == LiveState.LIVE) step(LiveState.RECONNECTING, m = "Connection interrupted, trying to recover...") else step(state)
        LiveEvent.DisconnectGraceExpired, LiveEvent.IceFailed, LiveEvent.Timeout ->
            if (state == LiveState.ENDED || state == LiveState.IDLE) step(state)
            else if (attempts < maxAutoReconnects) {
                attempts++
                step(LiveState.RECONNECTING, LiveAction.Reconnect(attempts, backoff(attempts)), "Reconnecting (attempt $attempts of $maxAutoReconnects)...")
            } else step(LiveState.FAILED, LiveAction.Teardown, "Could not connect. Check the camera's Internet and try again.")
        LiveEvent.CameraOffline -> step(LiveState.FAILED, LiveAction.Teardown, "The camera is offline.")
        LiveEvent.UserReconnect -> { attempts = 0; step(LiveState.REQUESTING, LiveAction.Reconnect(0, 0)) }
        LiveEvent.UserStop -> step(LiveState.ENDED, LiveAction.Teardown)
    }
}
