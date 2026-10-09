package com.laddu.app.features.viewer

import com.laddu.app.core.model.CameraStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyStatusTest {
    @Test fun `offline camera warns that the information may be old`() {
        assertTrue(friendlyStatus(false, CameraStatus(dogPresent = true)).contains("offline"))
    }

    @Test fun `barking wins over everything else when the dog is in view`() {
        assertTrue(friendlyStatus(true, CameraStatus(dogPresent = true, moving = true, barking = true)).contains("barking"))
    }

    @Test fun `no dog is described as out of view, never as sleeping`() {
        val t = friendlyStatus(true, CameraStatus())
        assertTrue(t.contains("out of the camera's view"))
        assertTrue(!t.contains("sleep"))
    }

    @Test fun `calm dog in view`() {
        assertTrue(friendlyStatus(true, CameraStatus(dogPresent = true)).contains("calm"))
    }
}
