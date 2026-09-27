package com.guille.spring.iquarters

import com.guille.spring.iquarters.scripts.GameManagerScript
import com.guille.spring.iquarters.scripts.QuarterTrigger
import com.guille.spring.iquarters.scripts.mainmenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The iQuarters port, headless: the baked pack holds both scenes, every script either scene
 * attaches has a port, the front end and the game run without a script throwing, and a
 * flick from the waiting state launches the coin. None of this needs a GL context.
 */
class IqRuntimeTest {

    private val pack by lazy { IqPack.parse(File("src/main/assets/iquarters/scene.pack").readBytes()) }

    private class LogHost : IqHost {
        override val prefs = MemoryPrefs()
        val logs = ArrayList<String>()
        override fun log(msg: String) { logs += msg }
    }

    private fun IqRuntime.run(frames: Int, touches: (Int) -> List<IqTouch> = { emptyList() }) {
        repeat(frames) { frame(IqRuntime.FRAME_DT, touches(it)) }
    }

    @Test
    fun `the pack holds both scenes`() {
        val menu = pack.scene("mainData")
        val game = pack.scene(IqRuntime.LEVEL_GAME)
        assertNotNull(menu.nodes.firstOrNull { it.script("mainmenu") != null })
        val q = game.nodes.first { it.name == "a_quarter5" }
        assertEquals(1f, q.rigidbody!!.mass)
        assertNotNull(q.script("QuarterTrigger"))
        assertEquals(-19.64f, pack.settings.getValue("gravityY"), 1e-4f)
        assertEquals(0.02f, pack.settings.getValue("fixedTimestep"), 1e-6f)
    }

    @Test
    fun `every attached script has a port`() {
        val missing = pack.scenes.flatMap { s -> s.nodes.flatMap { n -> n.scripts.map { it.className } } }
            .toSet().filter { IqScripts.create(it) == null }
        assertTrue("no port for $missing", missing.isEmpty())
    }

    @Test
    fun `the front end runs`() {
        val host = LogHost()
        val rt = IqRuntime(pack, host)
        rt.run(300)
        assertEquals("mainData", rt.world.scene.name)
        assertTrue(host.logs.joinToString("\n"), host.logs.isEmpty())
    }

    /**
     * Play Now, Classic, one player, by taps inside the ARM's own button rects, ends in the
     * game scene with one player. Each menu has to reach its resting state before a tap.
     */
    @Test
    fun `the front end reaches the game`() {
        val host = LogHost()
        val rt = IqRuntime(pack, host)
        val menu = rt.world.objects.first { it.script<mainmenu>() != null }.script<mainmenu>()!!
        fun tap(x: Float, y: Float) {
            val p = V2(x, IqRuntime.SCREEN_H - y)
            rt.frame(IqRuntime.FRAME_DT, listOf(IqTouch(0, p, V2.ZERO, IqRuntime.FRAME_DT, IqTouch.BEGAN)))
            rt.frame(IqRuntime.FRAME_DT, listOf(IqTouch(0, p, V2.ZERO, IqRuntime.FRAME_DT, IqTouch.ENDED)))
        }
        // State to wait for, then a point inside Play Now, Classic and 1 Player.
        for ((state, x, y) in listOf(Triple(101, 115f, 355f), Triple(105, 120f, 263f), Triple(121, 180f, 225f))) {
            var f = 0
            while (menu.feStateCurrent != state && f < 600) { rt.run(1); f++ }
            assertEquals(state, menu.feStateCurrent)
            tap(x, y)
        }
        var f = 0
        while (rt.world.scene.name == "mainData" && f < 600) { rt.run(1); f++ }
        assertEquals(IqRuntime.LEVEL_GAME, rt.world.scene.name)
        assertEquals(1, GameManagerScript.totPlayers)
        assertTrue(host.logs.joinToString("\n"), host.logs.isEmpty())
    }

    @Test
    fun `the game reaches its first shot`() {
        val host = LogHost()
        val rt = IqRuntime(pack, host, IqRuntime.LEVEL_GAME)
        var f = 0
        while (QuarterTrigger.state != QuarterTrigger.stateWaitForShot && f < 900) { rt.run(1); f++ }
        assertEquals("stuck in ${QuarterTrigger.state}", QuarterTrigger.stateWaitForShot, QuarterTrigger.state)
        assertEquals(0, GameManagerScript.curRound)
        assertTrue(host.logs.joinToString("\n"), host.logs.isEmpty())
    }

    /**
     * [IqInterp] at alpha 1 is the last step exactly; halfway through a step a coin in flight
     * is halfway between its two positions; and the coin's reset between shots is snapped,
     * not drawn flying back across the table.
     */
    @Test
    fun `drawn frames interpolate between steps`() {
        val rt = IqRuntime(pack, LogHost(), IqRuntime.LEVEL_GAME)
        val interp = IqInterp()
        fun step() { rt.run(1); interp.capture(rt.world) }
        var f = 0
        while (QuarterTrigger.state != QuarterTrigger.stateWaitForShot && f < 900) { step(); f++ }
        val coin = rt.world.objects.first { it.name == "a_quarter5" }
        val start = coin.position
        var sawFlight = false
        var sawSnap = false
        for (i in 0 until 400) {
            val touches = when (i) {
                0 -> listOf(IqTouch(0, V2(160f, 60f), V2.ZERO, IqRuntime.FRAME_DT, IqTouch.BEGAN))
                in 1..4 -> listOf(IqTouch(0, V2(160f, 60f + 40f * i), V2(0f, 40f), IqRuntime.FRAME_DT, IqTouch.MOVED))
                5 -> listOf(IqTouch(0, V2(160f, 260f), V2.ZERO, IqRuntime.FRAME_DT, IqTouch.ENDED))
                else -> emptyList()
            }
            val before = coin.position
            rt.frame(IqRuntime.FRAME_DT, touches)
            interp.capture(rt.world)
            val after = coin.position
            interp.begin(1f)
            for (o in rt.world.objects) assertTrue(o.name, interp.worldMatrix(o).m.contentEquals(o.worldMatrix().m))
            interp.begin(0.5f)
            val mid = interp.worldMatrix(coin).point(V3.ZERO)
            val moved = (after - before).length()
            if (moved in 0.05f..IqInterp.SNAP_DISTANCE) {
                assertEquals(0f, (mid - (before + after) * 0.5f).length(), 1e-3f)
                sawFlight = true
            }
            if (moved > IqInterp.SNAP_DISTANCE && (after - start).length() < 1e-3f) {
                assertEquals(0f, (mid - after).length(), 1e-4f)
                sawSnap = true
            }
        }
        assertTrue("never saw the coin fly", sawFlight)
        assertTrue("never saw the coin reset", sawSnap)
    }

    /**
     * A ricochet shot's instant replay starts once, plays and ends in the rack-up. The
     * decompiled C# drops both the state change that starts the replay (it re-entered every
     * frame, a new camera each time) and the block that counts the replay's ricochets (the
     * bonus digit went to -1 and RicochetExciter threw every frame).
     */
    @Test
    fun `a ricochet replay plays once and racks up`() {
        val host = LogHost()
        val rt = IqRuntime(pack, host, IqRuntime.LEVEL_GAME)
        val states = ArrayList<Int>()
        fun step(t: List<IqTouch> = emptyList()) {
            rt.frame(IqRuntime.FRAME_DT, t)
            if (states.lastOrNull() != QuarterTrigger.state) states += QuarterTrigger.state
        }
        // No touch may reach the replay: its full-screen skip button would end it early.
        fun replaying() = QuarterTrigger.stateShotResult in states
        var shot = 0
        while (!replaying() && shot < 40) {
            var g = 0
            while (QuarterTrigger.state != QuarterTrigger.stateWaitForShot && g < 3000 && !replaying()) { step(); g++ }
            repeat(20) { if (!replaying()) step() }
            if (replaying()) break
            val power = 40f + (shot % 3) * 6f
            for (i in 0..5) {
                val y = 60f + power * i
                step(listOf(when (i) {
                    0 -> IqTouch(0, V2(160f, y), V2.ZERO, IqRuntime.FRAME_DT, IqTouch.BEGAN)
                    5 -> IqTouch(0, V2(160f, y), V2.ZERO, IqRuntime.FRAME_DT, IqTouch.ENDED)
                    else -> IqTouch(0, V2(160f, y), V2(0f, power), IqRuntime.FRAME_DT, IqTouch.MOVED)
                }))
            }
            shot++
        }
        val from = states.lastIndexOf(QuarterTrigger.stateShotResult)
        assertTrue("no ricochet in $shot shots", from >= 0)
        var g = 0
        while (QuarterTrigger.stateRackup !in states.subList(from, states.size) && g < 900) { step(); g++ }
        val after = states.subList(from, states.size)
        // The intro lasts one fixed step, so a 30Hz frame can step over it.
        assertEquals(QuarterTrigger.stateWaitForShot, after.first { it != QuarterTrigger.stateShotResult && it != QuarterTrigger.stateWaitForShotIntro })
        assertEquals("the replay restarted: $after", 1, after.count { it == QuarterTrigger.stateShotResult })
        assertTrue("replay never racked up: $after", QuarterTrigger.stateRackup in after)
        val errors = host.logs.filter { it != "Quarter Data full" }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun `a flick launches the coin`() {
        val host = LogHost()
        val rt = IqRuntime(pack, host, IqRuntime.LEVEL_GAME)
        var f = 0
        while (QuarterTrigger.state != QuarterTrigger.stateWaitForShot && f < 900) { rt.run(1); f++ }
        // Up the screen, 40 points a frame for four frames, from the bottom middle.
        rt.run(6) { i ->
            val y = 60f + 40f * i
            listOf(
                when (i) {
                    0 -> IqTouch(0, V2(160f, y), V2.ZERO, IqRuntime.FRAME_DT, IqTouch.BEGAN)
                    5 -> IqTouch(0, V2(160f, y), V2.ZERO, IqRuntime.FRAME_DT, IqTouch.ENDED)
                    else -> IqTouch(0, V2(160f, y), V2(0f, 40f), IqRuntime.FRAME_DT, IqTouch.MOVED)
                },
            )
        }
        rt.run(10)
        assertTrue("still ${QuarterTrigger.state}", QuarterTrigger.state != QuarterTrigger.stateWaitForShot)
        assertTrue(host.logs.joinToString("\n"), host.logs.isEmpty())
    }

    /**
     * The glass flash: `GlassFlash`'s emitter, animator and renderer come out of the pack
     * as read off the raw components, at rest, and one `Emit(1)` makes a 1.2-unit particle that
     * starts at alpha 158/255, brightens, and is gone after its 0.1s.
     */
    @Test
    fun `the glass flash emits and fades`() {
        val node = pack.scene(IqRuntime.LEVEL_GAME).nodes.first { it.name == "GlassFlash" }
        val src = node.particles!!
        assertEquals(1.2f, src.minSize, 1e-6f)
        assertEquals(0.1f, src.maxEnergy, 1e-6f)
        // Nothing pushes a flash anywhere: read one field early, maxEmission's 1 became
        // worldVelocity.x and every flash drifted right.
        for (v in listOf(src.worldVelocity, src.localVelocity, src.rndVelocity)) {
            assertEquals(V3(0f, 0f, 0f), v)
        }
        assertTrue(src.worldSpace)
        assertEquals(-0.5f, src.animator!!.sizeGrow, 1e-6f)
        assertEquals("flash_00", pack.materials[src.renderer!!.material].name)
        assertEquals("iPhone Particles Additive Culled", pack.materials[src.renderer!!.material].shader)

        val rt = IqRuntime(pack, LogHost(), IqRuntime.LEVEL_GAME)
        rt.run(1)
        val ps = rt.world.find("GlassFlash")!!.particles!!
        ps.emit = false
        ps.particles.clear()
        ps.emit(1)
        val p = ps.particles.single()
        assertEquals(1.2f, p.size, 1e-6f)
        assertEquals(158 / 255f, p.color[3], 1e-6f)
        rt.run(1)
        assertTrue("alpha ${p.color[3]}", p.color[3] > 158 / 255f)
        rt.run(3)
        assertTrue(ps.particles.isEmpty())
    }
}
