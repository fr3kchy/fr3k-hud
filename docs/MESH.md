# Mesh (V2)

V2 implements a **mesh transport abstraction** that doesn't tie FR3K HUD to
any single mesh protocol. The same `Fr3kCommand` flows work over MeshCore,
Meshtastic, Reticulum, or a future protocol — only the adapter changes.

## Interface

```kotlin
interface MeshTransport {
    suspend fun status(): MeshStatus
    suspend fun send(message: MeshMessage): Result
    suspend fun nodes(): List<MeshNode>
    suspend fun sendLocation(location: Fr3kLocation): Result
}
```

Each adapter implements this interface against its SDK.

## Adapters (V2)

- **MeshCore** — uses MeshCore BLE API (current official protocol).
- **Meshtastic** — uses Meshtastic Android client API (current official).
- **Reticulum** — uses LXMF over the Reticulum mesh stack.

## Feature detection

The mesh plugin registers only the capabilities its adapter actually delivers.
If Meshtastic isn't installed, `meshtastic.send` is not registered; the palette
hides the action. No capability → no button.

## Radio configuration

FR3K HUD does **not** configure radio frequencies, region, or hardware. The
user configures their mesh network out-of-band; FR3K interacts only with the
already-configured client. Australian regulations and ISM band compliance are
the user's responsibility.

## Mesh first

When the internet disappears, FR3K HUD continues to offer mesh commands. The
quick HUD panel re-renders to surface mesh availability, not break.