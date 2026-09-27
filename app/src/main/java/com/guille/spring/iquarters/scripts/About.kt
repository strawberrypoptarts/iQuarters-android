package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GuiLabel
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `About`: the front end's About button and its text page on `ui_about`. [mainmenu] drives it
 * through the four `Trigger*` flags: the button shows with the main menu, a click plays
 * `Click` and then dims the scene and draws the ITme text, the web address and the version.
 * The iPad skin and margins are dropped.
 */
class About : Behaviour() {
    var aboutGameObject: GObj? = null
    var aboutDimPlaneRenderer: GObj? = null
    var aboutButtonRenderer: GObj? = null
    var aboutTextRenderer: GObj? = null

    private val stateIdle = 100
    private val stateButtonOnScreen = 101
    private val stateTriggerText = 102
    private val stateTextOnScreen = 103
    private var stateCurrent = 100
    private val versionInfo = "V 1.1.0    06/21/2010"
    private val leftRightMargin = 30
    private val topBottomMargin = 30

    /** The phone string literal loaded at 0x23ad68 (the iPad one has triple newlines). */
    private val aboutText = "For 25 years, Incredible Technologies has forged a name in the entertainment " +
        "industry as the innovative thinkers behind some of the world's most popular arcade games.  " +
        "You probably know us better as the Golden Tee Golf and Silver Strike Bowling guys and now " +
        "we're pleased to introduce you to our more personal side, ITme.\n\n" +
        "ITme - IT mobile entertainment - is the newest division of the company, and the first one " +
        "designed to entertain in the palm of your hand.  This new brand of IT will adorn our " +
        "collection of mobile applications and we guarantee that they'll live up to our name.\n\n" +
        "Our super-creative developers are going to be busy in the coming months unleashing games, " +
        "utilities, productivity apps, you name it and you can count on all of them to look and " +
        "feel great, be easy to use, and, most importantly, work as advertised."

    override fun onBind() {
        aboutGameObject = obj("aboutGameObject")
        aboutDimPlaneRenderer = obj("aboutDimPlaneRenderer")
        aboutButtonRenderer = obj("aboutButtonRenderer")
        aboutTextRenderer = obj("aboutTextRenderer")
    }

    override fun start() {
        aboutDimPlaneRenderer!!.renderer!!.enabled = false
        aboutButtonRenderer!!.renderer!!.enabled = false
        aboutTextRenderer!!.renderer!!.enabled = false
        TriggerButtonOn = false
        TriggerButtonOff = false
        TriggerTextOn = false
        stateCurrent = stateIdle
        val dimPlane = find("/ui_about/dimplane")!!
        dimPlane.localPosition = dimPlane.localPosition.copy(z = -3.1f)
        dimPlane.localScale = dimPlane.localScale.copy(z = 2.9f)
    }

    /**
     * ARM `About::Update` (0x23a500): one if/else-if chain in the order button-on,
     * button-off (0x23a5a4), text-on (0x23a634), text-off (0x23a6a0), then the wait for
     * `Click` to end. Each trigger clears only itself.
     */
    override fun update() {
        if (TriggerButtonOn) {
            TriggerButtonOn = false
            stateCurrent = stateButtonOnScreen
            aboutDimPlaneRenderer!!.renderer!!.enabled = false
            aboutButtonRenderer!!.renderer!!.enabled = true
            aboutTextRenderer!!.renderer!!.enabled = false
        } else if (TriggerButtonOff) {
            TriggerButtonOff = false
            hideAll()
        } else if (TriggerTextOn) {
            TriggerTextOn = false
            stateCurrent = stateTriggerText
            animation!!.play("Click")
        } else if (TriggerTextOff) {
            TriggerTextOff = false
            hideAll()
        } else if (stateCurrent == stateTriggerText && !animation!!.isPlaying("Click")) {
            aboutDimPlaneRenderer!!.renderer!!.enabled = true
            aboutButtonRenderer!!.renderer!!.enabled = false
            aboutTextRenderer!!.renderer!!.enabled = true
            stateCurrent = stateTextOnScreen
        }
    }

    /** Both "off" branches of [update] (0x23a5cc and 0x23a6c8 are the same code). */
    private fun hideAll() {
        stateCurrent = stateIdle
        aboutDimPlaneRenderer!!.renderer!!.enabled = false
        aboutButtonRenderer!!.renderer!!.enabled = false
        aboutTextRenderer!!.renderer!!.enabled = false
        aboutGameObject!!.setActiveRecursively(false)
    }

    /**
     * ARM `About::OnGUI` (0x23a7c8): nothing while the button is on screen (0x23a81c);
     * with the text on screen, [DrawAboutText] and then [versionInfo] at (8,460,200,20)
     * (0x23a848) in the current skin's own style. No skin is set, so it is the default one.
     */
    override fun onGUI() {
        if (stateCurrent == stateButtonOnScreen) return
        if (stateCurrent == stateTextOnScreen) {
            DrawAboutText()
            guiLabel(Rect(8f, 460f, 200f, 20f), versionInfo, "default")
        }
    }

    /**
     * ARM `About::DrawAboutText` (0x23a958), iPad branch (`fontSkin_iPad`, the `_iPad`
     * margins, `GetiPadRect`) dropped. The phone path sets no skin:
     *
     * - the ITme text in `Rect(lr, tb, 320 - lr*2, 480 - tb*2)` (0x23ac30-0x23ad18), with
     *   both margins 30 from the `.ctor` (0x23a198), so (30,30,260,420), in the skin's own
     *   label alignment;
     * - then the label style's alignment and normal text colour are saved, alignment set to
     *   4 = MiddleCenter (0x23ae08) and the colour's r and g to 255 and b to 0 (0x23ae7c,
     *   0x23afc4, 0x23b10c; alpha untouched), which Unity clamps to yellow;
     * - "Visit www.ITMobileEntertainment.com" at (0, 424, 320, 20) (0x23b1e4);
     * - alignment and colour restored (0x23b318, 0x23b35c).
     */
    fun DrawAboutText() {
        val textRect = Rect(
            leftRightMargin.toFloat(), topBottomMargin.toFloat(),
            (320 - leftRightMargin * 2).toFloat(), (480 - topBottomMargin * 2).toFloat()
        )
        guiLabel(textRect, aboutText, "default")
        guiLabel(Rect(0f, 424f, 320f, 20f), "Visit www.ITMobileEntertainment.com", "default", center = true, color = GuiLabel.YELLOW)
    }

    companion object : IqStatic {
        @JvmField var TriggerButtonOn = false
        @JvmField var TriggerButtonOff = false
        @JvmField var TriggerTextOn = false
        @JvmField var TriggerTextOff = false

        override fun reset() {
            TriggerButtonOn = false
            TriggerButtonOff = false
            TriggerTextOn = false
            TriggerTextOff = false
        }
    }
}
