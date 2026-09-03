# Protocol

## Envelope

```json
{
  "protocol": "fr3k/1",
  "id": "01JABC...",
  "source": "fr3k-phone-01",
  "destination": "fr3k-dell-01",
  "type": "agent.ask",
  "timestamp": 1735862345123,
  "ttlMs": 60000,
  "replyTo": null,
  "correlationId": null,
  "auth": {
    "deviceId": "fr3k-phone-01",
    "scheme": "ed25519-blake3",
    "signature": "...",
    "publicKey": "...",
    "nonce": "...",
    "issuedAt": 1735862345000
  },
  "payload": { ... }
}
```

Unversioned messages MUST be rejected. The current version is `fr3k/1`.

## Result codes

| Code | Name | Meaning |
|------|------|---------|
| 0 | OK | Success |
| 1 | UNAUTHORIZED | Bad signature / missing token |
| 2 | FORBIDDEN | Policy denied |
| 3 | NOT_FOUND | Unknown device / capability |
| 4 | CAPABILITY_MISSING | Capability not currently available |
| 5 | POLICY_DENIED | User hasn't granted required permission |
| 6 | TIMEOUT | Transport timed out |
| 7 | CANCELLED | User cancelled |
| 8 | BAD_REQUEST | Malformed payload |
| 9 | RATE_LIMITED | Back off |
| 10 | OFFLINE | No transport reachable |
| 11 | INTERNAL | Plugin bug; log it |
| 12 | UNSUPPORTED | Feature not implemented |

## Capability IDs

The canonical list lives in `protocol/src/main/java/.../Capability.kt`. New IDs
must be namespaced (e.g. `ai.deepseek.chat`, `mesh.meshtastic.send`).

Categories:
- **agent** — `agent.ask`, `agent.research`, `agent.code`, `agent.translate`, `agent.summarise`
- **context** — `context.selection`, `context.url`, `context.screen`, `context.notification`, `context.clipboard`
- **location** — `location.current`, `location.waypoint`, `location.share`
- **mesh** — `mesh.send`, `mesh.broadcast`, `mesh.nodes`, `mesh.status`, `mesh.location`
- **meshtastic** — `meshtastic.send`, `meshtastic.nodes`, `meshtastic.position`
- **meshcore** — `meshcore.send`, `meshcore.contacts`, `meshcore.status`
- **termux** — `termux.job`, `termux.script`, `termux.ssh`
- **device** — `device.list`, `device.status`, `device.open`, `device.send`, `device.command`
- **browser** — `browser.current_url`, `browser.clean_url`
- **share** — `share.text`, `share.url`, `share.file`
- **system** — `system.battery`, `system.network`, `system.bluetooth`, `system.storage`
- **ai** — `ai.local.chat`, `ai.local.vision`, `ai.local.embedding`

## Agent protocol

`agent.ask` is the universal operation. Request payload:

```json
{
  "prompt": "Explain this error",
  "profile": "NORMAL",
  "model": null,
  "context": {
    "sourcePackage": "com.android.chrome",
    "sourceActivity": "ChromeActivity",
    "url": "https://…",
    "selectedText": "...",
    "fullText": "...",
    "location": null,
    "screenshotUri": null,
    "extras": {}
  },
  "attachments": []
}
```

Response payload:

```json
{
  "text": "Hermes reply…",
  "format": "MARKDOWN",
  "actions": [
    {
      "id": "open-in-dev",
      "title": "Open in dev agent",
      "command": "device.open",
      "arguments": {"deviceId": "fr3k-dell-01"},
      "requiresCapabilities": ["device.open"],
      "requiresConfirmation": true
    }
  ],
  "followUps": ["Explain further", "Save notes"],
  "tokensUsed": 412,
  "model": "hermes-1"
}
```

Agent-suggested actions are **proposals**. FR3K core runs policy checks before
executing any of them.

## Profiles

| Profile | Behaviour |
|---------|-----------|
| `NORMAL` | Default — Hermes routing |
| `FAST` | Short replies, prefer fast models |
| `PRIVATE` | Local AI only, never leaves device |
| `OFFLINE` | Local first, offline-tolerant |
| `RESEARCH` | Web tools allowed |
| `CODE` | Code-focused, tools + code interpreters preferred |
| `CHEAP` | Cheapest viable model |

## Device manifest

```json
{
  "device_id": "fr3k-phone-01",
  "name": "Pixel 8 Pro",
  "platform": "android",
  "version": "0.1.0",
  "capabilities": [
    {"id": "agent.ask", "displayName": "Agent ask", "tier": "TIER_0", "version": 1, ...}
  ],
  "transports": ["https", "websocket", "lan"],
  "status": "online",
  "last_seen": 1735862345123,
  "public_key": "...",
  "signature": "...",
  "metadata": {}
}
```

Manifests are signed in V3. V1 sends unsigned manifests over LAN HTTPS.