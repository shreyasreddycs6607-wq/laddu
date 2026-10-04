package com.laddu.app.core.webrtc

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints = MediaConstraints()): SessionDescription =
    suspendCancellableCoroutine { c ->
        createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
            override fun onCreateFailure(e: String?) = c.resumeWithException(IllegalStateException("createOffer: $e"))
        }, constraints)
    }

suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints = MediaConstraints()): SessionDescription =
    suspendCancellableCoroutine { c ->
        createAnswer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
            override fun onCreateFailure(e: String?) = c.resumeWithException(IllegalStateException("createAnswer: $e"))
        }, constraints)
    }

suspend fun PeerConnection.setLocalSuspend(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
    setLocalDescription(object : SimpleSdp() {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(e: String?) = c.resumeWithException(IllegalStateException("setLocal: $e"))
    }, d)
}

suspend fun PeerConnection.setRemoteSuspend(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
    setRemoteDescription(object : SimpleSdp() {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(e: String?) = c.resumeWithException(IllegalStateException("setRemote: $e"))
    }, d)
}
