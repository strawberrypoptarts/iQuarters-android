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
