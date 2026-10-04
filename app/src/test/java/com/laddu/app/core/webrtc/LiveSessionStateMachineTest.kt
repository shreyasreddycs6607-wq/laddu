package com.laddu.app.core.webrtc

import com.laddu.app.core.model.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebRTC connection lifecycle, disconnection and reconnection (pure state machine). */
class LiveSessionStateMachineTest {
    private fun connected(): LiveSessionStateMachine = LiveSessionStateMachine().apply {
        onEvent(LiveEvent.RequestSent); onEvent(LiveEvent.OfferReceived); onEvent(LiveEvent.IceConnected)
    }

    @Test fun `happy path requesting - connecting - live`() {
        val sm = LiveSessionStateMachine()
        assertEquals(LiveState.REQUESTING, sm.onEvent(LiveEvent.RequestSent).state)
        assertEquals(LiveState.CONNECTING, sm.onEvent(LiveEvent.OfferReceived).state)
        assertEquals(LiveState.LIVE, sm.onEvent(LiveEvent.IceConnected).state)
    }

    @Test fun `short network blip recovers without a new session`() {
        val sm = connected()
        assertEquals(LiveState.RECONNECTING, sm.onEvent(LiveEvent.IceDisconnected).state)
        assertEquals(LiveAction.None, sm.onEvent(LiveEvent.IceDisconnected).action)
        assertEquals(LiveState.LIVE, sm.onEvent(LiveEvent.IceConnected).state)
    }

    @Test fun `lost connection triggers bounded automatic reconnects with growing back-off`() {
        val sm = connected()
        sm.onEvent(LiveEvent.IceDisconnected)
        val delays = mutableListOf<Long>()
        repeat(3) {
            val s = sm.onEvent(LiveEvent.DisconnectGraceExpired)
            val a = s.action as LiveAction.Reconnect
            assertEquals(it + 1, a.attempt); delays += a.delayMs
            assertEquals(LiveState.RECONNECTING, s.state)
            sm.onEvent(LiveEvent.RequestSent) // the new session is being requested
        }
        assertTrue(delays[0] < delays[1] && delays[1] < delays[2])
        val final = sm.onEvent(LiveEvent.IceFailed)
        assertEquals(LiveState.FAILED, final.state)
        assertEquals(LiveAction.Teardown, final.action)
        assertTrue(final.message!!.isNotBlank())
    }

    @Test fun `successful reconnect resets the attempt counter`() {
        val sm = connected()
        sm.onEvent(LiveEvent.IceFailed); sm.onEvent(LiveEvent.RequestSent); sm.onEvent(LiveEvent.OfferReceived); sm.onEvent(LiveEvent.IceConnected)
        val s = sm.onEvent(LiveEvent.IceFailed)
        assertEquals(1, (s.action as LiveAction.Reconnect).attempt)
    }

    @Test fun `camera never answers - timeout retries then fails`() {
        val sm = LiveSessionStateMachine(maxAutoReconnects = 1)
        sm.onEvent(LiveEvent.RequestSent)
        assertTrue(sm.onEvent(LiveEvent.Timeout).action is LiveAction.Reconnect)
        sm.onEvent(LiveEvent.RequestSent)
        assertEquals(LiveState.FAILED, sm.onEvent(LiveEvent.Timeout).state)
    }

    @Test fun `manual reconnect from failed starts over immediately`() {
        val sm = LiveSessionStateMachine(maxAutoReconnects = 0)
        sm.onEvent(LiveEvent.RequestSent); sm.onEvent(LiveEvent.IceFailed)
        assertEquals(LiveState.FAILED, sm.state)
        val s = sm.onEvent(LiveEvent.UserReconnect)
        assertEquals(LiveState.REQUESTING, s.state)
        assertEquals(LiveAction.Reconnect(0, 0), s.action)
    }

    @Test fun `offline camera fails fast with a clear message`() {
        val s = LiveSessionStateMachine().onEvent(LiveEvent.CameraOffline)
        assertEquals(LiveState.FAILED, s.state); assertTrue(s.message!!.contains("offline"))
    }

    @Test fun `stopping ends the session and later failures cannot resurrect it`() {
        val sm = connected()
        assertEquals(LiveAction.Teardown, sm.onEvent(LiveEvent.UserStop).action)
        assertEquals(LiveState.ENDED, sm.state)
        assertEquals(LiveAction.None, sm.onEvent(LiveEvent.IceFailed).action)
        assertEquals(LiveState.ENDED, sm.state)
    }
}
