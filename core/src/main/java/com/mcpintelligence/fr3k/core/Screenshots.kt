package com.mcpintelligence.fr3k.core

/**
 * Screenshot workflow (§13). Public surface for any component that needs to
 * invoke an explicit, user-approved screen capture. The actual MediaProjection
 * intent lives in :app (ScreenshotActivity); this is the typed contract.
 *
 * Implementations:
 *   - captureScreen(context): triggers the activity, returns the resulting URI
 *   - the URI is then fed back into the context engine
 */
object Screenshots {

    const val REQUEST_CODE = 8421

    /**
      * Public request helper used by the HUD's long-press / palette to start the
      * screenshot flow. Real implementation in :app.
      */
    fun shouldRequest(activity: android.app.Activity): Boolean = true
}

/**
 * Tiny location types so :core stays Android-light. The real Fr3kLocation
 * lives in :protocol. We avoid a hard dep here for testability.
 */
object Locations {
    const val REQUEST_CODE = 8422
}