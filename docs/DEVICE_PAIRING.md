# Device pairing & fleet

FR3K devices discover each other through a signed manifest exchange. LAN
presence alone is never trusted.

## Discovery mechanisms

| Mechanism | V | Trust |
|-----------|---|-------|
| HTTPS / mDNS | V1 | LAN + bearer token (V3 adds signed manifests) |
| QR code pairing | V1 | Highest trust — out-of-band channel |
| WebSocket | V2 | LAN + bearer |
| BLE | V2 | LAN + bearer |
| MQTT | V2 | Optional bridge |
| MeshCore / Meshtastic | V2 | Out-of-band key fingerprint |

V1 ships the registry, HTTPS push/pull, and a placeholder for QR pairing. V2
adds the remaining transports.

## Manifest

```json
{
  "device_id": "fr3k-phone-01",
  "name": "Pixel 8 Pro",
  "platform": "android",
  "version": "0.1.0",
  "capabilities": [...],
  "transports": ["https", "websocket", "lan"],
  "status": "online",
  "last_seen": 1735862345123,
  "public_key": "...",
  "signature": "...",
  "metadata": {}
}
```

## Pairing

V3 will implement QR pairing: each device displays its current public key as
a QR code. The other device scans and stores the fingerprint. Future
manifests must include a signature verifiable against the stored fingerprint.

## Revocation

A device can be removed from the fleet via:

```kotlin
deviceRegistry.remove(deviceId)
```

The next manifest exchange does not restore it; an explicit re-pair is
required.