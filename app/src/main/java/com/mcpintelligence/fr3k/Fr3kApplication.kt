package com.mcpintelligence.fr3k

import android.app.Application
import android.content.Intent
import android.util.Log
import com.mcpintelligence.fr3k.core.CapabilityRegistry
import com.mcpintelligence.fr3k.core.CommandRegistry
import com.mcpintelligence.fr3k.core.DeviceIdentity
import com.mcpintelligence.fr3k.core.DeviceRegistry
import com.mcpintelligence.fr3k.core.Fr3kCore
import com.mcpintelligence.fr3k.core.PluginManager
import com.mcpintelligence.fr3k.core.SecureStore
import com.mcpintelligence.fr3k.core.AppSettings
import com.mcpintelligence.fr3k.core.AutomationEngine
import com.mcpintelligence.fr3k.core.ContextEngine
import com.mcpintelligence.fr3k.core.DeviceHandoffAdapter
import com.mcpintelligence.fr3k.core.GpsPlugin
import com.mcpintelligence.fr3k.core.ShareCommandsPlugin
import com.mcpintelligence.fr3k.core.SystemPlugin
import com.mcpintelligence.fr3k.core.VoiceIntentPlanner
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveBridgeClient
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwavePlugin
import com.mcpintelligence.fr3k.integrations.hermes.AiProviderRegistry
import com.mcpintelligence.fr3k.integrations.hermes.HermesAskCommand
import com.mcpintelligence.fr3k.integrations.hermes.HermesPlugin
import com.mcpintelligence.fr3k.integrations.hermes.HermesProvider

/**
 * Application class — process entry point. Assembles the FR3K core and
 * starts plugins. Held as a singleton in [fr3kCore].
 *
 * V2 adds: Meshtastic / MeshCore adapters, GPS, Termux job runner.
 * V3 adds: Rootless LSPatch/NPatch and rooted Vector adapters.
 */
class Fr3kApplication : Application() {

    private val capabilityRegistryImpl by lazy { CapabilityRegistry() }
    private val commandRegistryImpl by lazy { CommandRegistry() }
    private val deviceRegistryImpl by lazy { DeviceRegistry() }
    private val settingsImpl by lazy { AppSettings() }
    private val contextEngineImpl by lazy { ContextEngine() }
    private val automationEngineImpl by lazy { AutomationEngine() }

    val identity by lazy { DeviceIdentity(this) }
    val secureStore by lazy { SecureStore(this) }
    val aiProviders by lazy { AiProviderRegistry() }
    val termuxBridge by lazy { com.mcpintelligence.fr3k.integrations.termux.TermuxBridge(this) }

    val fr3kCore: Fr3kCore by lazy {
        val pluginManager = PluginManager(
            capabilityRegistry = capabilityRegistryImpl,
            commandRegistry = commandRegistryImpl,
        )
        Fr3kCore(
            appContext = applicationContext,
            identity = identity,
            secureStore = secureStore,
            capabilityRegistry = capabilityRegistryImpl,
            commandRegistry = commandRegistryImpl,
            deviceRegistry = deviceRegistryImpl,
            settings = settingsImpl,
            contextEngine = contextEngineImpl,
            automationEngine = automationEngineImpl,
            deviceHandoff = DeviceHandoffAdapter(),
            pluginManager = pluginManager,
        )
    }

    val capabilityRegistry get() = capabilityRegistryImpl
    val commandRegistry get() = commandRegistryImpl
    val deviceRegistry get() = deviceRegistryImpl
    val settings get() = settingsImpl
    val contextEngine get() = contextEngineImpl
    val automationEngine get() = automationEngineImpl

    val voiceIntentPlanner by lazy { VoiceIntentPlanner { deviceRegistryImpl } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        bootstrap()
        // Register the Shizuku listeners at process scope so the binder /
        // permission state is tracked once, not per Activity. Without this
        // the Integrations panel's Shizuku section renders "manager not
        // installed" even when the binder is live but the callback hasn't
        // arrived yet.
        com.mcpintelligence.fr3k.integrations.shizuku.ShizukuBridge.start(this)
    }

    private fun bootstrap() {
        Log.i(TAG, "FR3K booting on deviceId=${identity.deviceId} android=${identity.androidId} v${identity.appVersion}")
        registerHermes()
        registerOpenCode()
        registerBlackwave()
        fr3kCore.pluginManager.register(GpsPlugin())
        fr3kCore.pluginManager.register(ShareCommandsPlugin(
            contextEngineProvider = { contextEngineImpl },
            deviceRegistryProvider = { deviceRegistryImpl },
        ))
        fr3kCore.pluginManager.register(SystemPlugin())
        fr3kCore.pluginManager.startAll()
        seedAutomations()
    }

    private fun registerHermes() {
        val hermesProvider = HermesProvider(
            endpointProvider = { settingsImpl.settings.value.hermesEndpoint },
            authTokenProvider = { secureStore.get(settingsImpl.settings.value.hermesAuthTokenKey) },
            deviceIdProvider = { identity.deviceId },
        )
        val askCommand = HermesAskCommand(provider = { hermesProvider })
        fr3kCore.pluginManager.register(
            HermesPlugin(
                provider = hermesProvider,
                aiProviderRegistry = aiProviders,
                commandFactory = { askCommand },
            )
        )
    }

    /**
     * Register OpenCode Zen as a second AI provider that defaults to
     * [OpenCodeZenProvider.DEFAULT_MODEL] = `big-pickle`. This is the
     * "no API key" free path: we talk to opencode.ai/zen with the public
     * bearer key, fetch the live free-model list, and let the user pick.
     */
    private fun registerOpenCode() {
        val openCodeProvider = com.mcpintelligence.fr3k.integrations.opencode.OpenCodeZenProvider()
        val askCommand = com.mcpintelligence.fr3k.integrations.opencode.AskOpenCodeCommand(
            provider = { openCodeProvider },
        )
        fr3kCore.pluginManager.register(
            com.mcpintelligence.fr3k.integrations.opencode.OpenCodePlugin(
                provider = openCodeProvider,
                aiProviderRegistry = aiProviders,
                commandFactory = { askCommand },
            )
        )
    }

    private fun registerBlackwave() {
        val bridgeClient = BlackwaveBridgeClient(
            endpointProvider = { settingsImpl.settings.value.blackwaveEndpoint },
            credentialProvider = { secureStore.get(settingsImpl.settings.value.blackwaveCredentialKey) },
            clientIdProvider = { settingsImpl.settings.value.blackwaveClientId },
        )
        fr3kCore.pluginManager.register(
            BlackwavePlugin(bridgeClient = bridgeClient)
        )
    }

    private fun seedAutomations() {
        val owner = "fr3k.seeds"
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "share-received-ask",
                ownerId = owner,
                title = "Share received → Ask Hermes",
                trigger = AutomationEngine.Automation.Trigger(type = AutomationEngine.TriggerType.SHARE_RECEIVED),
                action = AutomationEngine.Action.OpenAskAboutThis(),
            )
        )
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "url-shared-clean",
                ownerId = owner,
                title = "URL shared → clean URL",
                trigger = AutomationEngine.Automation.Trigger(type = AutomationEngine.TriggerType.URL_SHARED),
                action = AutomationEngine.Action.RunCommand(
                    commandId = "share.url.clean",
                    args = emptyMap(),
                ),
            )
        )
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "url-shared-ask",
                ownerId = owner,
                title = "URL shared → Ask Hermes",
                trigger = AutomationEngine.Automation.Trigger(type = AutomationEngine.TriggerType.URL_SHARED),
                action = AutomationEngine.Action.OpenAskAboutThis(),
            )
        )
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "browser-foreground-ask",
                ownerId = owner,
                title = "Browser foreground → Ask Hermes",
                trigger = AutomationEngine.Automation.Trigger(
                    type = AutomationEngine.TriggerType.FOREGROUND_APP,
                    packageMatch = "com.android.chrome",
                ),
                action = AutomationEngine.Action.OpenAskAboutThis(),
                enabled = false,  // off by default; user opts in
            )
        )
    }

    companion object {
        const val TAG = "FR3K.app"
        private lateinit var instance: Fr3kApplication
        fun get(): Fr3kApplication = instance
    }
}