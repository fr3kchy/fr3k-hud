# Hermes bridge

Hermes is FR3K's primary AI endpoint. The bridge wraps every Hermes call as
an envelope envelope of type `agent.ask`.

## Endpoint

The default endpoint is `https://hermes.local/api/v1/agent` and is configurable
in `AppSettings.hermesEndpoint`. The user can override to a LAN address, a
custom domain, or a private Ollama-style server.

## Authentication

A bearer token is stored in `SecureStore` under the key
`hermes.auth.token` (configurable via `AppSettings.hermesAuthTokenKey`).
Tokens are never logged.

## Flow

```
share → context → askAboutThis → HermesAskCommand
  ↓
HermesProvider.ask(request)
  ↓ builds envelope of type agent.ask
HttpsTransport.send(envelope)
  ↓
Hermes replies with envelope of type "result" / "agent.reply"
  ↓
AgentAskResponse parsed
  ↓
Rendered in AskAboutThisActivity / returned to CommandResult
```

## Local fallback

If Hermes is unreachable, the provider returns a structured
`AgentAskResponse` with a "saved locally" message. The UI still renders a useful
acknowledgement. When the endpoint becomes reachable again, the saved request
is retried (V3).

## Routing

`AiPolicy` selects the right provider based on:

- `profile` field of the request (PRIVATE → local only, RESEARCH → cloud, etc.)
- Currently available capabilities
- Provider health (`online`, `latencyMs`)

The UI is provider-agnostic. Adding a new AI provider is a single `AiProvider`
implementation.

## Profiles

| Profile | Behaviour |
|---------|-----------|
| `NORMAL` | Default — Hermes routing |
| `FAST` | Short replies |
| `PRIVATE` | Local AI only |
| `OFFLINE` | Local first, offline-tolerant |
| `RESEARCH` | Web tools allowed |
| `CODE` | Code-focused |
| `CHEAP` | Cheapest viable |