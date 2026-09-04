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
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.adapters.morphe.MorphePatchRepository
import com.mcpintelligence.fr3k.adapters.lspatch.LspatchAdapter
import com.mcpintelligence.fr3k.integrations.shizuku.ShizukuAdapter
import com.mcpintelligence.fr3k.integrations.vector.VectorAdapter
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher

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
class IntegrationsActivity : Activity() {

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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ALL_PERMS) {
            val granted = grantResults.count { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            val total = permissions.size
            showToast("Granted $granted of $total runtime permissions — re-opening integrations to refresh")
            // Rebuild the content so the status text updates
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

        fun makeButton(label: String, onClick: () -> Unit): TextView = TextView(this@IntegrationsActivity).apply {
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
            setOnClickListener { onClick() }
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
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (!termuxAvail) {
                    addView(makeButton("OPEN PLAY STORE → TERMUX") {
                        openPlayStore("com.termux")
                    })
                } else if (!termuxGranted) {
                    addView(makeButton("SHOW GRANT INSTRUCTIONS") {
                        showToast(termux.grantInstructions())
                    })
                } else {
                    addView(makeButton("SMOKE-TEST: echo hi from fr3k") {
                        val r = termux.runJob("network.ping", mapOf("host" to "127.0.0.1"))
                            .let { termux.runRaw("echo hi-from-fr3k-${System.currentTimeMillis() % 1000}", 6000) }
                        showToast("rc=${r.exitCode}\nstdout=${r.stdout}\nstderr=${r.stderr}")
                    })
                }
            }
            container.addView(section("TERMUX (TIER 1)", termuxBody))
            container.addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- Shizuku -----
            val shIn = shizuku.isInstalled()
            val shAuth = shizuku.isAuthorized()
            val shPing = shizuku.pingBinder()
            val shBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("tier: 2 (Shizuku permission grant)", if (shPing) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv("Shizuku installed: ${if (shIn) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("Shizuku authorised: ${if (shAuth) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("IPC link live: ${if (shPing) "yes" else "no"}", 0xFF9ca3af.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (!shIn) {
                    addView(makeButton("OPEN PLAY STORE → SHIZUKU") {
                        openPlayStore("moe.shizuku.api")
                    })
                } else if (!shAuth) {
                    addView(makeButton("OPEN SHIZUKU GRANT SCREEN") {
                        shizuku.openGrantScreen()
                    })
                } else {
                    addView(makeButton("PING SHIZUKU BINDER") {
                        showToast("ping=${shizuku.pingBinder()}")
                    })
                }
            }
            container.addView(section("SHIZUKU (TIER 2)", shBody))
            container.addView(this@IntegrationsActivity.spacer(density, 12))

            // ----- LSPatch -----
            val lspModules = lspatch.scan()
            val lspMgr = lspatch.hasManager()
            val lspBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("tier: 3 (LSPatch module installed)", if (lspModules.isNotEmpty()) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv("manager installed: ${if (lspMgr) "yes" else "no — install JingMatrix LSPosed manager"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("modules: ${lspModules.size} discovered", 0xFF9ca3af.toInt(), 11f))
                for (m in lspModules.take(3)) {
                    addView(tv("  • ${m.id}  v${m.version}  (${if (m.isEnabled) "enabled" else "DISABLED"})", 0xFF9ca3af.toInt(), 10f))
                }
                addView(this@IntegrationsActivity.spacer(density, 8))
                if (!lspMgr) {
                    addView(makeButton("OPEN LSPATCH MANAGER") {
                        lspatch.openManager()
                    })
                } else {
                    addView(makeButton("ANNOUNCE FR3K TO MODULES") {
                        val n = lspatch.announceToModules()
                        showToast("announced to $n modules")
                    })
                }
                if (PermissionRegistry.firstMissing(this@IntegrationsActivity, PermissionRegistry.Feature.LSPATCH) != null) {
                    addView(this@IntegrationsActivity.spacer(density, 6))
                    addView(makeButton("GRANT STORAGE PERMISSION") {
                        PermissionRegistry.requestRuntime(
                            this@IntegrationsActivity,
                            PermissionRegistry.Feature.LSPATCH,
                            9001,
                        )
                    })
                }
            }
            container.addView(section("LSPATCH (TIER 3)", lspBody))
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
                    addView(makeButton("GRANT MEDIA PERMISSIONS") {
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
            val v = vector.status()
            val vBody = LinearLayout(this@IntegrationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(
                    "tier: 4 (rooted / Vector)",
                    if (v.rootAvailable || v.vectorPackage != null) 0xFF4ade80.toInt() else 0xFFfbbf24.toInt(), 11f,
                ))
                addView(this@IntegrationsActivity.spacer(density, 4))
                addView(tv("root available: ${v.rootAvailable}  (su at ${v.suPath ?: "n/a"})", 0xFF9ca3af.toInt(), 11f))
                addView(tv("Vector pkg: ${v.vectorPackage ?: "none"}", 0xFF9ca3af.toInt(), 11f))
                addView(tv("LSPatch modules: ${v.lspatchPackages.size}", 0xFF9ca3af.toInt(), 11f))
                addView(this@IntegrationsActivity.spacer(density, 8))
                addView(makeButton("SMOKE-TEST: su -c 'id'") {
                    val out = vector.runRootedShell("id", 4000)
                    showToast(out.take(400))
                })
            }
            container.addView(section("VECTOR / ROOT (TIER 4)", vBody))
            container.addView(this@IntegrationsActivity.spacer(density, 24))

            addView(makeButton("CLOSE") { finish() })
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

    private fun showToast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val REQ_ALL_PERMS = 8001
    }
}
