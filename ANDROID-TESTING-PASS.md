# FR3K ANDROID APP TESTING PASS

> Status: OPEN campaign — end-to-end integration, usability and regression pass
> Scope: HUD, agent chat, browser, Termux integration, BLACKWAVE app
> Owner reference doc for the main agent.

The core components are beginning to work, but the Android apps now need a
proper integration, usability and regression-testing pass.

Do not simply patch the individual UI problems below. Treat this as an
end-to-end test of how the HUD, agent chat, browser, Termux integration and
BLACKWAVE app work together.

The intended experience is that the AI agent can actively operate these tools
on the user's behalf while making its actions visible and understandable.

---

## 1. BROWSER PANEL

The browser is working, but the UI currently wastes too much screen space.
Fix the layout so that:
- Browser content receives the maximum practical screen area.
- Toolbars, headers and controls are compact.
- Unnecessary padding, margins and blank areas are removed.
- The browser remains fully usable on smaller Android screens.

More importantly, the browser is not intended to be merely a user-controlled
WebView. The agent must be able to control the browser on the user's behalf.

Implement or verify agent-accessible browser actions including:
- open URL
- navigate backward/forward
- reload
- click elements
- scroll
- enter text
- submit forms
- inspect current URL/page state
- extract visible page text where appropriate
- report what action it performed
- return useful errors when an action fails

Add a collapsible agent chat panel within or alongside the browser.
The user should be able to:
- expand chat when interacting with the agent
- collapse it almost completely when maximum browser space is required
- continue the same conversation while the agent operates the browser
- see concise explanations of what the agent is doing

The browser, chat and agent-control layer should feel like one integrated tool
rather than separate features.

---

## 2. TERMUX POPUP

The Termux popup appears to work, but pinch resizing currently does not.
Investigate whether resizing can be implemented reliably.

At minimum provide:
- sensible default size
- draggable/resizable window if Android permits it
- maximise/restore control
- compact/minimised state
- correct orientation handling
- reliable keyboard behaviour

The purpose of the Termux popup is not simply to display a terminal.
It should become the agent's visible execution console.

When the agent needs to perform local shell operations, the ideal flow is:

    USER → CHAT AGENT → TERMUX EXECUTION → VISIBLE OUTPUT → AGENT EXPLANATION

The agent should be able to issue authorised commands through the Termux
integration while the user can watch:
- command being executed
- stdout
- stderr
- exit status
- current working directory where relevant
- progress for longer operations

The chat should explain important operations as they happen.
Do not fake terminal output. What appears in the terminal must correspond to
the command actually executed.

---

## 3. CONNECT CHAT, TERMUX AND BROWSER

These currently feel too independent. Create a shared tool-control architecture
so the agent can move between them.

Example:
User says: "Find the latest firmware for this device, download it and inspect it."

The agent should be able to:
1. Explain its intended action in chat.
2. Open/search using the browser.
3. Navigate the browser.
4. Download the required file.
5. Hand the file/path to Termux.
6. Execute inspection commands.
7. Display the real terminal output.
8. Explain the results in chat.
9. Return the relevant file/result to the user.

Browser and Termux actions should therefore appear as tools available to the
same agent session. Maintain one shared task/session context across:
- HUD chat
- browser
- Termux
- BLACKWAVE where appropriate

---

## 4. FIX FALSE "TERMUX OFFLINE" STATUS

The Quick HUD currently reports `Termux Offline` even though Termux
functionality works. This must be fixed properly. Do not simply hide the
warning.

Determine what constitutes actual Termux availability and expose a reliable
health/status check. Suggested states:
- CONNECTED
- CONNECTING
- UNAVAILABLE
- PERMISSION REQUIRED
- SERVICE STOPPED
- ERROR

The HUD status must reflect reality.

Test:
- app cold start
- Termux already running
- Termux not running
- Termux restarted
- Android process killed/recreated
- permission revoked
- reconnect after temporary failure

---

## 5. OPENROUTER SETUP

The app needs a straightforward first-run AI configuration. Start with an
OpenRouter API key entry field.

Requirements:
- secure storage of the key
- masked key display
- ability to replace/remove key
- connection validation
- useful error reporting
- never leak the key into logs, terminal history or normal UI output

Default model behaviour should use OpenRouter's available free-model routing
option where supported. The user must also be able to select a different model.

Provide:
- default/free option
- model selector
- currently active model
- provider/model status
- ability to refresh available choices where practical

The app should remain usable enough to reach configuration screens if the key
is missing or invalid.

---

## 6. BLACKWAVE APP

BLACKWAVE currently reports that features including Live Apply, OTA and
Identity are disabled. At present there is no obvious workflow for turning
BLACKWAVE into a functioning connected system. This makes the app effectively
unusable for a new user.

The BLACKWAVE main screen needs a prominent **SET UP BLACKWAVE** (or
equivalent) button. This launches a guided configuration wizard.

The setup workflow should cover:
1. BLACKWAVE identity/profile creation.
2. Required Android permissions.
3. Local networking requirements.
4. Bluetooth requirements where applicable.
5. Wi-Fi/LAN discovery.
6. Connecting the FR3K HUD.
7. Discovering compatible BLACKWAVE devices.
8. Adding a device manually.
9. Pairing/authentication.
10. Device capability discovery.
11. Device identity verification.
12. Enabling supported features.
13. OTA configuration.
14. Live Apply configuration.
15. Agent-control permissions.
16. Testing connectivity.
17. Saving configuration.
18. Showing the user a final system-health summary.

Do not expose controls such as Live Apply or OTA as unexplained disabled
buttons. If something is unavailable, show why.

Example: `OTA unavailable: no authenticated OTA-capable device connected.`

Where appropriate, provide a direct action such as **Configure →**.

---

## 7. ADD DEVICE WORKFLOW

BLACKWAVE needs a proper Add Device system. Support the discovery mechanisms
already implemented or planned by the project, potentially including:
- BLE
- local Wi-Fi/LAN
- device AP
- USB
- serial
- QR provisioning
- manual IP/hostname
- manual device ID

Each discovered device should expose its real capabilities rather than relying
solely on a hard-coded device type.

After pairing, display information such as:
- device name
- BLACKWAVE ID
- model/platform
- firmware version
- IP/address
- transport
- connection state
- capabilities
- radios
- sensors
- screen
- GPS/GNSS
- OTA support
- Live Apply support
- command/control support

---

## 8. HUD ↔ BLACKWAVE INTEGRATION

There must be a clear path for connecting BLACKWAVE with the FR3K HUD. Once
connected, the HUD should understand that BLACKWAVE devices are available tools.

Example agent requests should eventually work through the same interaction model:
- "Show me my BLACKWAVE devices."
- "Which devices are online?"
- "Open the terminal."
- "Check the T-Deck firmware."
- "Open the BLACKWAVE device page."
- "Run diagnostics on the connected node."
- "Use the browser to find the latest upstream release."
- "Compare it with the installed firmware."

The architecture should avoid building separate isolated agents for each screen.
Prefer:

    ONE AGENT SESSION → MULTIPLE AUTHORISED TOOLS → SHARED DEVICE AND TASK CONTEXT

---

## 9. FIRST-RUN EXPERIENCE

A fresh installation should no longer drop the user into several partially
configured screens. Build a coherent onboarding flow.

Suggested sequence:

    WELCOME → AI PROVIDER → OPENROUTER KEY → MODEL → TERMUX CONNECTION →
    BROWSER CONTROL TEST → BLACKWAVE SETUP → DEVICE DISCOVERY →
    HUD INTEGRATION → SYSTEM TEST → READY

Allow steps that genuinely are optional to be skipped. Show completion/status
clearly.

---

## 10. DIAGNOSTICS PAGE

Add a central diagnostics screen covering:

Agent
- provider
- model
- API connectivity
- tool availability

Termux
- installed
- service state
- command execution test
- permissions
- last error

Browser
- WebView status
- agent-control bridge
- navigation test

BLACKWAVE
- service state
- connected devices
- discovery transports
- identity
- Live Apply
- OTA

HUD
- HUD service state
- BLACKWAVE link
- agent link

Provide a **RUN FULL DIAGNOSTIC** button. Produce clear PASS / WARN / FAIL
results and enough technical detail for debugging.

---

## 11. TEST THE COMPLETE SYSTEM

Do not consider this finished because individual buttons appear to work.
Test the actual workflows. At minimum perform:

**Fresh install** — no existing configuration, no API key, no BLACKWAVE
devices configured.

**Returning installation** — saved OpenRouter configuration, previously paired
devices, existing HUD configuration.

**Offline behaviour** — no internet, Termux available, BLACKWAVE local devices
available.

**Broken configuration** — invalid OpenRouter key, unreachable device, Termux
unavailable, permissions denied, disconnected Wi-Fi/Bluetooth.

**Recovery** — verify the system can recover when each dependency becomes
available again without requiring an app reinstall.

---

## 12. UX REQUIREMENT

Screen space on Android is precious.

Audit every major screen for:
- wasted vertical space
- oversized headers
- oversized cards
- unnecessary padding
- duplicate status text
- excessive permanent controls
- things that should instead collapse into drawers, sheets or expandable panels

The interface should remain information-dense without becoming unreadable.
Browser and terminal views in particular need to dedicate most of the screen
to their actual content.

---

## 13. IMPLEMENTATION RULE

Do not paper over integration failures with static UI changes.

- For every status displayed by the app, determine the authoritative
  underlying state.
- For every agent action shown to the user, ensure a corresponding real
  operation occurred.
- For every disabled capability, explain the dependency preventing it from
  becoming active.

The target architecture is:

    FR3K AGENT
    ↓
    SHARED TOOL / CAPABILITY LAYER
    ↓
    BROWSER | TERMUX | BLACKWAVE | HUD
    ↓
    REAL DEVICES / SERVICES

The goal of this pass is to move the Android suite from a collection of
partially functioning screens into a coherent agent-operated system.

---

## Completion Criteria

Do not mark this task complete until:

- Browser works and can be agent-controlled.
- Browser chat is collapsible.
- Browser space usage is substantially improved.
- Termux works reliably.
- Termux status in the HUD is accurate.
- Agent-triggered terminal commands can be visibly executed.
- Chat can explain browser and terminal operations.
- OpenRouter configuration is functional.
- Free/default model routing works where supported.
- Models can be changed.
- BLACKWAVE provides a usable first-run setup flow.
- Devices can actually be discovered/added.
- HUD and BLACKWAVE integration works.
- Disabled capabilities expose their missing prerequisites.
- Diagnostics identify broken components correctly.
- Cold-start, restart, offline and recovery tests pass.
- The complete Android workflow has been tested on a real device, not only
  an emulator.

Finally, document `BUG → ROOT CAUSE → FIX → TEST → RESULT` for every issue
found during this pass.