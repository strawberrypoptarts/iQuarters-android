package com.guille.spring.iquarters
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ParityTest {
    @Test fun swipePowerDoesNotDependOnSampleRate() {
        var reference: FlickShot? = null
        for(hz in listOf(30,60,90,120,144,240)) {
            val g=TimedFlick();g.begin(0.0);var shot:FlickShot?=null
            for(i in 1..hz) shot=shot ?: g.sample(120f/hz,3000f/hz,i.toDouble()/hz)
            assertNotNull("No shot at $hz Hz",shot)
            if(reference==null)reference=shot
            assertEquals(reference!!.power,shot!!.power,1e-5f)
            assertEquals(reference.aim,shot.aim,1e-5f)
        }
    }
    @Test fun slowDragAndCancellationDoNotFire() {
        for(hz in listOf(30,60,120,240)) {
            val g=TimedFlick();g.begin(0.0)
            repeat(hz) { assertNull(g.sample(0f,30f/hz,(it+1.0)/hz)) }
            assertNull(g.end())
            g.begin(2.0);assertNull(g.sample(0f,20f,2.005));g.cancel();assertNull(g.end())
        }
    }
    @Test fun shortReleaseAndDuplicateTimeAreHandled() {
        val g=TimedFlick();g.begin(0.0)
        assertNull(g.sample(0f,9999f,0.0));assertNull(g.sample(0f,25f,.005))
        assertNotNull(g.end());assertNull(g.end())
    }
    @Test fun uiCoordinatesRemainUniformOnPhonesAndTablets() {
        for ((w,h) in listOf(320 to 480,600 to 1024,1080 to 2400,1536 to 2048,2560 to 1600)) {
            val v=IqViewport(w,h)
            val pt=v.touch(v.left+160*v.scale,v.bottom+240*v.scale)
            assertEquals(160f,pt.x,.001f);assertEquals(240f,pt.y,.001f)
            assertTrue(v.uiWidth<=w);assertTrue(v.uiHeight<=h)
        }
    }
    @Test fun expandedScreensDoNotExposeOffStageMenuArt() {
        for ((w,h) in listOf(320 to 480, 600 to 1024, 1080 to 2400, 1080 to 2520,
                1536 to 2048, 2560 to 1600, 1081 to 2401)) {
            val v = IqViewport(w,h)
            val clip = v.clip()
            // The hidden score logo/name banner live above the original 480-point stage.
            val hiddenBannerY = v.bottom + 500f * v.scale
            assertTrue(hiddenBannerY >= clip.bottom + clip.height)
            assertTrue(v.bottom + 240f * v.scale < clip.bottom + clip.height)
            for (anchor in IqViewport.Anchor.values()) {
                val c = v.clip(anchor)
                assertTrue(c.left >= 0 && c.bottom >= 0)
                assertTrue(c.left + c.width <= w && c.bottom + c.height <= h)
                assertEquals(v.uiWidth, c.width)
                assertEquals(v.uiHeight, c.height)
            }
            assertEquals(0, v.clip(IqViewport.Anchor.TOP_LEFT).left)
            assertEquals(0, v.clip(IqViewport.Anchor.BOTTOM_RIGHT).bottom)
            assertTrue(h - (v.clip(IqViewport.Anchor.TOP).bottom + v.uiHeight) <= 1)
        }
        assertNull(IqViewport.anchor("ui_quarter_logo_score"))
        assertEquals(IqViewport.Anchor.TOP_RIGHT, IqViewport.anchor("ex_round_mon"))
        assertTrue(IqViewport.fullScreenBackground("BackDrop"))
        assertFalse(IqViewport.fullScreenBackground("ui_quarter_logo_score"))
    }
    @Test fun cameraLookRemovesRollAfterSmoothing() {
        var q=Quat.IDENTITY
        for(i in 0..300) {
            val target=V3(kotlin.math.sin(i*.1f),kotlin.math.cos(i*.07f),1f)
            val b=Quat.slerp(q,Quat.lookRotation(target),.067f)
            q=Quat.lookRotation(b.rotate(V3.FORWARD))
            assertEquals(0f,q.rotate(V3.RIGHT).y,1e-5f)
        }
    }
    @Test fun auditRecoveredPresentationData() {
        val pack=IqPack.parse(File("src/main/assets/iquarters/scene.pack").readBytes())
        for(scene in pack.scenes) for(n in scene.nodes) {
            n.camera?.let { println("CAMERA ${scene.name} ${n.name} mask=${it.cullingMask} ortho=${it.orthographic} size=${it.orthoSize} fov=${it.fov}") }
            if(n.name=="BackDrop" || n.name=="backdrop") println("BACKDROP ${n.name} ${n.materials.toList()}")
        }
        assertTrue(pack.materials.any { it.shader.contains("Transparent") })
    }
}
