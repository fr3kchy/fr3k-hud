package com.mcpintelligence.fr3k.ui.integrations

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.adapters.morphe.MorphePatchRepository
import com.mcpintelligence.fr3k.adapters.lspatch.LspatchAdapter
import com.mcpintelligence.fr3k.integrations.shizuku.ShizukuAdapter
import com.mcpintelligence.fr3k.integrations.vector.VectorAdapter
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Integrations panel — single surface that shows the live status of every
 * integration FR3K HUD supports, with one-tap actions to:
 *   - install/launch the partner app
 *   - grant the runtime permission
 *   - open the relevant Settings screen for special permissions
 *   - run a smoke-test command through each adapter
 *
 * Five sections:
 *   1. Termux         — Tier 1 (user grants RUN_COMMAND inside Termux)
 *   2. Shizuku        — Tier 2 (Shizuku permission grant)
 *   3. LSPatch        — Tier 3 (READ_EXTERNAL_STORAGE on <= 32; manager install)
 *   4. Morphe         — Tier 3 (READ_MEDIA_*; ships example patches in assets)
 *   5. Vector / root  — Tier 4 (probeRoot())
 */
class IntegrationsActivity : ComponentActivity() {

    private val termux by lazy { Fr3kApplication.get().termuxBridge }
    private val shizuku by lazy { ShizukuAdapter(this) }
    private val lspatch by lazy { LspatchAdapter(this) }
    private val morphe by lazy { MorphePatchRepository() }
    private val vector by lazy { VectorAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        setContentView(buildContent())
        // Register a single lifecycle-scoped collector on the ShizukuBridge
        // state so the Shizuku section live-updates as the binder arrives.
        // Guarded so rebuild() (called by setContentView below on every
        // state change) does not stack unlimited collectors.
        if (!shizukuObserverStarted) {
            shizukuObserverStarted = true
            lifecycleScope.launch {
                com.mcpintelligence.fr3k.integrations.shizuku.ShizukuBridge.get().state
                    .collect { setContentView(buildContent()) }
            }
        }
    }

    @Volatile private var shizukuObserverStarted = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ALL_PERMS) {
            val granted = grantResults.count { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            val total = permissions.size
            showToast("Granted $granted of $total runtime permissions — re-opening integrations to refresh")
            // Rebuild the content so the status text updates
            setContentView(buildContent())
        } else if (requestCode == ShizukuAdapter.REQ_SHIZUKU_PERMISSION) {
            shizuku.onRequestPermissionsResult(requestCode, grantResults)
            showToast(if (shizuku.isAuthorized()) "Shizuku permission granted" else "Shizuku permission still denied")
            setContentView(buildContent())
        }
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0a0a14.toInt())
            cornerRadius = 12f * density
        }
        val sectionBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF11111c.toInt())
            setStroke((1.5f * density).toInt(), 0xFF2b2b40.toInt())
            cornerRadius = 10f * density
        }

        fun tv(text: String, color: Int = 0xFFcdd1e0.toInt(), sp: Float = 12f, bold: Boolean = false): TextView =
            TextView(this@IntegrationsActivity).apply {
                this.text = text
                setTextColor(color)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
                typeface = android.graphics.Typeface.MONOSPACE
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

        fun makeButton(label: String, onClick: (TextView) -> Unit): TextView = TextView(this@IntegrationsActivity).apply {
            text = label
            setTextColor(0xFF05060A.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(0xFF7d3cff.toInt())
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt(),
            )
            setOnClickListener { onClick(this) }
        }

        fun makeRow(label: String, onClick: () -> Unit): TextView = TextView(this@IntegrationsActivity).apply {
            text = label
            setTextColor(0xFFcdd1e0.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            val rowBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF11111c.toInt())
                setStroke((1 * density).toInt(), 0xFF2b2b40.toInt())
                cornerRadius = 8f * density
            }
            background = rowBg
            setPadding(
                (14 * density).toInt(), (12 * density).toInt(),
                (14 * density).toInt(), (12 * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            // Force a non-zero size in a vertical LinearLayout so the row
            // doesn't get collapsed to height 0 when its parent has
            // wrap_content height.
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (4 * density).toInt()
                                bottomMargin = (4 * density).toInt()
                            }
                            setOnClickListener { onClick() }
                        }

        fun section(title: String, body: View): View = LinearLayout(this@IntegrationsActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = sectionBg
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            addView(tv(title, 0xFF7d3cff.toInt(), 12f, true))
            addView(this@IntegrationsActivity.spacer(density, 6))
            addView(body)
        }

        val container = LinearLayout(this@IntegrationsActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg
            setPadding(
                (20 * density).toInt(), (32 * density).toInt(),
                (20 * density).toInt(), (32 * density).toInt(),
            )
        }

        val content = LinearLayout(this@IntegrationsActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(content)

        with(content) {
            addView(tv("FR3K ▸ INTEGRATIONS", 0xFF7d3cff.toInt(), 14f, true))
            addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- GRANT ALL (one-tap runtime perms) -----
            addView(makeRow("GRANT ALL RUNTIME PERMISSIONS", onClick = {
                val denied = PermissionRegistry.runtimeNotGranted(this@IntegrationsActivity)
                if (denied.isEmpty()) {
                    android.widget.Toast.makeText(
                        this@IntegrationsActivity,
                        "All runtime permissions already granted",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@IntegrationsActivity,
                        denied.toTypedArray(),
                        REQ_ALL_PERMS,
                    )
                }
            }))

            // ----- Termux -----
            val termuxAvail = termux.isAvailable()
            val termuxGranted = termux.hasRunCommandPermission()
            val termuxUsable = termux.isUsable()
            val termuxBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(
                    "tier: 1 (user grant in Termux)",
                    if (termuxUsable) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f,
                ))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv("installed: ${if (termuxAvail) "yes" else "no — install com.termux + com.termux.api"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("RUN_COMMAND granted: ${if (termuxGranted) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("probe (live): ${if (termuxUsable) "yes" else "no"}",
                    if (!termuxUsable && termuxAvail) 0xFFfb923c.toInt() else 0xFF9ca3af.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (!termuxAvail) {
                    addView(makeButton("OPEN PLAY STORE → TERMUX") { _ ->
                        openPlayStore("com.termux")
                    })
                } else if (!termuxUsable) {
                    addView(makeButton("OPEN FR3K APP PERMISSIONS → RUN COMMANDS") { _ ->
                        openAppInfoPermissions()
                    })
                    addView(this@IntegrationsActivity.spacer(density, 4))
                    addView(makeButton("OR: SHOW GRANT INSTRUCTIONS") { _ ->
                        showInstructionsDialog(
                            title = "Termux — grant RUN_COMMAND",
                            body = termux.grantInstructions(),
                            copyLabel = "COPY",
                        )
                    })
                } else {
                    addView(makeButton("SMOKE-TEST: echo hi from fr3k") { btn ->
                        // Dispatch on IO + suspend via lifecycleScope so
                        // the click handler returns immediately and the
                        // button shows RUNNING while the command runs.
                        btn.isEnabled = false
                        btn.text = "RUNNING…"
                        lifecycleScope.launch {
                            val r = withContext(Dispatchers.IO) {
                                termux.runRaw(
                                    "echo hi-from-fr3k-${System.currentTimeMillis() % 1000}",
                                    30_000,
                                )
                            }
                            showToast("rc=${r.exitCode}\nstdout=${r.stdout}\nstderr=${r.stderr}")
                            btn.isEnabled = true
                            btn.text = "SMOKE-TEST: echo hi from fr3k"
                        }
                    })
                }
            }
            container.addView(section("TERMUX (TIER 1)", termuxBody))
            container.addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- Shizuku -----
            // Subscribe to the application-scoped ShizukuBridge state
            // (Task 7), which tracks the real binder / permission lifecycle
            // at process scope. The bridge observes package presence plus
            // the running shizuku_server + binder callback and never
            // collapses to "not installed" while a binder is pending.
            val bridge = com.mcpintelligence.fr3k.integrations.shizuku.ShizukuBridge.get()
            val shState = bridge.state.value
            val shIn = shState !is com.mcpintelligence.fr3k.integrations.shizuku.ShizukuState.Missing
            val shManager = shState is com.mcpintelligence.fr3k.integrations.shizuku.ShizukuState.ServerStarting ||
                shState is com.mcpintelligence.fr3k.integrations.shizuku.ShizukuState.BinderLivePermissionRequired ||
                shState is com.mcpintelligence.fr3k.integrations.shizuku.ShizukuState.Ready
            val shAuth = shState is com.mcpintelligence.fr3k.integrations.shizuku.ShizukuState.Ready
            val shPing = shState is com.mcpintelligence.fr3k.integrations.shizuku.ShizukuState.Ready
            // Drive a live bind attempt on the IO thread (non-blocking).
            if (shIn) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { shizuku.bind() }
                }
            }
            val shBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("tier: 2 (Shizuku permission grant)", if (shPing) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv("Shizuku installed: ${if (shIn) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("Shizuku manager running: ${if (shManager) "yes" else "no"}",
                    if (!shManager && shIn) 0xFFfb923c.toInt() else 0xFF9ca3af.toInt(), 11f))
                addView(tv("Shizuku authorised: ${if (shAuth) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("IPC link live: ${if (shPing) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (!shIn) {
                    addView(makeButton("OPEN PLAY STORE → SHIZUKU") { _ ->
                        openPlayStore("moe.shizuku.api")
                    })
                } else if (!shManager) {
                    // Manager is installed but not running. On rooted
                    // devices SUI starts on boot; on non-rooted
                    // devices the user has to launch SUI manually
                    // and tap "start". The API package alone is
                    // not enough.
                    addView(tv(
                        "SUI Manager is installed but the service is not running. " +
                        "Open the Shizuku app and tap \"Start\" — once the " +
                        "binder is live, return here and grant permission.",
                        0xFFfb923c.toInt(), 11f,
                    ))
                    addView(this@IntegrationsActivity.spacer(density, 4))
                    addView(makeButton("OPEN SHIZUKU APP") { _ ->
                        shizuku.openGrantScreen(this@IntegrationsActivity)
                    })
                } else if (!shAuth) {
                    addView(makeButton("GRANT SHIZUKU PERMISSION") { _ ->
                        shizuku.openGrantScreen(this@IntegrationsActivity)
                    })
                } else {
                    addView(makeButton("PING SHIZUKU BINDER") { btn ->
                        btn.isEnabled = false
                        btn.text = "PINGING…"
                        lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) { shizuku.pingBinder() }
                            showToast("ping=$ok")
                            btn.isEnabled = true
                            btn.text = "PING SHIZUKU BINDER"
                        }
                    })
                }
            }
            container.addView(section("SHIZUKU (TIER 2)", shBody))
            container.addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- LSPatch -----
            // LSPatch (by JingMatrix) is a STANDALONE module loader — it
            // re-signs a host APK with the module's classes.dex merged in,
            // so it works on non-rooted devices. Root is only required if
            // you also want the LSPosed *runtime* (Vector / Zygisk) path
            // listed in the VECTOR / ROOT section below. We detect both
            // "LSPatch manager" (org.lsposed.lspatch) and the older
            // "LSPosed manager" (org.lsposed.manager) so users on either
            // fork see the right install state.
            val lspModules = lspatch.scan()
            val lspMgr = lspatch.hasManager()
            val lspMgrPkg = lspatch.managerPackage()
            val lspBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("tier: 3 (LSPatch host-app repackage — no root needed)", if (lspModules.isNotEmpty()) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv(
                    "manager installed: ${if (lspMgr) "yes (${lspMgrPkg ?: "?"})" else "no — install JingMatrix LSPatch (no root required)"}",
                    0xFF9ca3af.toInt(), 11f,
                ))
                addView(tv("modules: ${lspModules.size} discovered", 0xFF9ca3af.toInt(), 11f))
                for (m in lspModules.take(3)) {
                    addView(tv("  • ${m.id}  v${m.version}  (${if (m.isEnabled) "enabled" else "DISABLED"})", 0xFF9ca3af.toInt(), 10f))
                }
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (!lspMgr) {
                    addView(makeButton("OPEN LSPATCH GITHUB") { _ ->
                        // Open the JingMatrix LSPatch releases page —
                        // the apk is not on Play Store. We use a chooser
                        // so the user can pick a browser.
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/JingMatrix/LSPatch/releases"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { startActivity(i) }
                    })
                } else {
                    addView(makeButton("ANNOUNCE FR3K TO MODULES") { btn ->
                        btn.isEnabled = false
                        btn.text = "ANNOUNCING…"
                        lifecycleScope.launch {
                            val n = withContext(Dispatchers.IO) { lspatch.announceToModules() }
                            showToast("announced to $n modules")
                            btn.isEnabled = true
                            btn.text = "ANNOUNCE FR3K TO MODULES"
                        }
                    })
                }
                if (PermissionRegistry.firstMissing(this@IntegrationsActivity, PermissionRegistry.Feature.LSPATCH) != null) {
                    addView(this@IntegrationsActivity.spacer(density, 6))
                    addView(makeButton("GRANT STORAGE PERMISSION") { _ ->
                        PermissionRegistry.requestRuntime(
                            this@IntegrationsActivity,
                            PermissionRegistry.Feature.LSPATCH,
                            9001,
                        )
                    })
                }
            }
            container.addView(section("LSPATCH (TIER 3) — no root", lspBody))
            container.addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- Morphe -----
            val morphePatches = morphe.loadAllAvailable(this@IntegrationsActivity)
            val morpheBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("tier: 3 (Morphe patch repo)", if (morphePatches.isNotEmpty()) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv("patches: ${morphePatches.size} loaded", 0xFF9ca3af.toInt(), 11f))
                for (p in morphePatches.take(3)) {
                    addView(tv("  • ${p.id}  → ${p.targetPackage}", 0xFF9ca3af.toInt(), 10f))
                }
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (PermissionRegistry.firstMissing(this@IntegrationsActivity, PermissionRegistry.Feature.MORPHE) != null) {
                    addView(makeButton("GRANT MEDIA PERMISSIONS") { _ ->
                        PermissionRegistry.requestRuntime(
                            this@IntegrationsActivity,
                            PermissionRegistry.Feature.MORPHE,
                            9002,
                        )
                    })
                }
            }
            container.addView(section("MORPHE (TIER 3)", morpheBody))
            container.addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- Vector / root -----
            // Vector / LSPosed runtime is OPTIONAL — it only works on
            // rooted devices (Riru/Zygisk injects the LSPosed runtime
            // into every running process). Non-rooted users get a clear
            // "skip this section" message instead of being asked to
            // install a root-only package. The standalone LSPatch path
            // (TIER 3 above) covers the common no-root use case.
            val v = vector.status()
            val vBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(
                    "tier: 4 (LSPosed runtime — ROOT ONLY, OPTIONAL)",
                    if (v.rootAvailable) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f,
                ))
                addView(this@IntegrationsActivity.spacer(density, 4))
                if (!v.rootAvailable) {
                    addView(tv(
                        "Root not available. Skip this section — standalone LSPatch (above) works without root.",
                        0xFF9ca3af.toInt(), 11f,
                    ))
                } else {
                    addView(tv("root available: yes  (su at ${v.suPath ?: "?"})", 0xFF4ade80.toInt(), 11f))
                    addView(tv("Vector pkg: ${v.vectorPackage ?: "none"}", 0xFF9ca3af.toInt(), 11f))
                    addView(tv("LSPatch modules: ${v.lspatchPackages.size}", 0xFF9ca3af.toInt(), 11f))
                    addView(this@IntegrationsActivity.spacer(density, 8))
                    addView(makeButton("SMOKE-TEST: su -c 'id'") { btn ->
                        btn.isEnabled = false
                        btn.text = "RUNNING…"
                        lifecycleScope.launch {
                            val out = withContext(Dispatchers.IO) {
                                vector.runRootedShell("id", 4000)
                            }
                            showToast(out.take(400))
                            btn.isEnabled = true
                            btn.text = "SMOKE-TEST: su -c 'id'"
                        }
                    })
                }
            }
            container.addView(section("VECTOR / ROOT (TIER 4) — optional, root only", vBody))
            container.addView(this@IntegrationsActivity.spacer(density, 24))

            addView(makeButton("CLOSE") { _ -> finish() })
        }

        val scroll = android.widget.ScrollView(this@IntegrationsActivity).apply {
            isFillViewport = true
            addView(container)
        }

        return scroll
    }

    private fun spacer(density: Float, hDp: Int): View = View(this@IntegrationsActivity).apply {
        layoutParams = LinearLayout.LayoutParams(1, (hDp * density).toInt())
    }

    private fun openPlayStore(pkg: String) {
        val i = Intent(Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
            .onFailure {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setData(android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
    }

    /**
     * Launch the system App Info screen for our own package, scrolled to
     * the Permissions section. The user can then tap "Run commands in
     * Termux environment" (or any other Termux grant) under "Additional
     * permissions" without having to remember the path. On API 26+ the
     * standard "package details" Settings activity exists; on older APIs
     * the fallback opens the legacy Application Info.
     */
    private fun openAppInfoPermissions() {
        val ctx: android.content.Context = this
        val intents = listOf(
            // Modern (API 26+): Settings → App Info → Permissions
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            // Legacy Application Info activity
            Intent("android.intent.action.VIEW")
                .setClassName(
                    "com.android.settings",
                    "com.android.settings.applications.InstalledAppDetailsTop",
                )
                .putExtra("package", ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (i in intents) {
            val ok = runCatching { startActivity(i) }.isSuccess
            if (ok) return
        }
        // Last-resort: just show a toast telling the user the path.
        android.widget.Toast.makeText(
            ctx,
            "Settings → Apps → FR3K HUD → Permissions → Run commands in Termux environment",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun showToast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Pop a copyable instructions dialog. Used for Termux grant-instructions
     * (the toast is too short for a multi-line shell block) and any other
     * long shell/text the user needs to read or paste somewhere else.
     */
    private fun showInstructionsDialog(title: String, body: String, copyLabel: String = "COPY") {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF11111c.toInt())
            setPadding(pad, pad, pad, pad)
        }

        container.addView(android.widget.TextView(this).apply {
            text = title
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        container.addView(android.widget.TextView(this).apply {
            text = "\n"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 4f)
        })

        val text = android.widget.TextView(this).apply {
            text = body
            setTextColor(0xFFcdd1e0.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)   // long-press to select
            setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
        }
        // Wrap in a scroll view so long instructions don't blow up the dialog
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(text, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        container.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (300 * density).toInt(),
        ))

        // Inline a small button factory for the dialog (we can't reuse
        // the buildContent-scoped makeButton from outside its closure).
        val act = this@IntegrationsActivity
        fun dialogButton(label: String, onClick: () -> Unit): android.widget.TextView =
            android.widget.TextView(act).apply {
                setText(label)
                setTextColor(0xFF05060A.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundColor(0xFF7d3cff.toInt())
                setPadding(
                    (12 * density).toInt(), (8 * density).toInt(),
                    (12 * density).toInt(), (8 * density).toInt(),
                )
                setOnClickListener { onClick() }
            }

        // Bottom action row: [COPY] [CLOSE]
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        row.addView(dialogButton(copyLabel) {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("fr3k-hud", body))
            showToast("Copied to clipboard — paste in Termux")
        })
        row.addView(View(this).apply {
            val s = (8 * density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(s, s)
        })
        row.addView(dialogButton("CLOSE") { dlg?.dismiss() })
        container.addView(row)

        val dlg = android.app.AlertDialog.Builder(this)
            .setView(container)
            .create()
        this.dlg = dlg
        dlg.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(0xFF11111c.toInt())
        )
        dlg.show()
    }
    private var dlg: android.app.AlertDialog? = null

    companion object {
        const val REQ_ALL_PERMS = 8001
    }
}
