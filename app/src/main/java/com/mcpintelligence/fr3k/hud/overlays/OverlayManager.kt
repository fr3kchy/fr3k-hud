package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.view.View
import android.view.WindowManager
import com.mcpintelligence.fr3k.hud.overlays.FrappeOverlayRegistry

/**
 * Owns every overlay window for a FR3K session. Mirrors how
 * `HedgehogOverlayService` keeps references to hedgehog / bubble / browser /
 * solana / terminal / exitTargetView / edgeTabView / particleLinkView.
 */
class OverlayManager(context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val host = OverlayHost(context, wm)

    val chatBubble by lazy { Fr3kChatBubble(host) }
    val browser by lazy { Fr3kMiniBrowserOverlay(host) }
    val terminal by lazy { Fr3kTerminalOverlay(host) }
    val miniBrowser get() = browser
    val exitTarget by lazy { Fr3kExitTarget(host) { closeAll() } }
    val edgeArc by lazy { Fr3kEdgeArc(host) { restoreOrb() } }
    val particle by lazy { Fr3kParticleLink(host) }

    private val registry = FrappeOverlayRegistry(chatBubble, browser, terminal, exitTarget, edgeArc, particle)

    fun openChat() { chatBubble.show(); lightParticleTo(chatBubble.rootView()) }
    fun openBrowser(url: String? = null) {
        if (url != null) browser.openUrl(url) else browser.show()
        lightParticleTo(browser.rootView())
    }
    fun openTerminal() { terminal.show(); lightParticleTo(terminal.rootView()) }
    fun hideAll() {
        registry.all().forEach { it.hide() }
    }
    fun closeAll() {
        registry.all().forEach { it.hide() }
    }

    private fun lightParticleTo(target: View) {
        // Anchor A = orb (caller supplies via [OrbPosition]); B = center of target.
        // For now we just leave anchors at 0,0; Fr3kHudOrb sets them when present.
        particle.show()
    }

    fun updateParticleAnchors(orbX: Int, orbY: Int, targetView: View) {
        val loc = IntArray(2)
        targetView.getLocationOnScreen(loc)
        particle.setAnchors(orbX, orbY, loc[0] + targetView.width / 2, loc[1] + targetView.height / 2)
    }

    private fun restoreOrb() {
        // The orb service listens for "edge-arc tap" via the manager.
    }

    fun shutdown() {
        registry.all().forEach {
            when (it) {
                is Fr3kChatBubble -> it.shutdown()
                is Fr3kMiniBrowserOverlay -> it.shutdown()
                is Fr3kTerminalOverlay -> it.shutdown()
                else -> it.hide()
            }
        }
    }
}

/** Tiny helper to iterate all overlays. */
private class FrappeOverlayRegistry(vararg overlays: Fr3kOverlay) {
    private val list = overlays.toList()
    fun all(): List<Fr3kOverlay> = list
}