# DV Game — Android

Per-game split tunneling for Android using sing-box/libbox.

## Architecture

```
User selects games → DvVpnService (VpnService)
  → sing-tun (mixed stack: system TCP + gVisor UDP)
  → WireGuard outbound → Iran server → existing tunnels
```

- **VPN engine**: sing-box libbox (v1.13.19) via local AAR
- **TUN stack**: mixed (system for TCP, gVisor for UDP)
- **Per-app routing**: `OverrideOptions.includePackage` → `VpnService.Builder.addAllowedApplication()`
- **Config format**: Panel provides WireGuard INI → app converts to sing-box JSON via `SingBoxConfigBuilder`
- **State management**: Application-scoped `BoxController` with `StateFlow<TunnelState>`

## Setup

1. Download `libbox.aar` — see [app/libs/README.md](app/libs/README.md)
2. Place it at `app/libs/libbox.aar`
3. Build with Android Studio or `./gradlew assembleDebug`

## Panel API

The app fetches a DV Game JSON from `/dvgame/<token>`:

```json
{
  "apiVersion": 1,
  "account": { "name": "...", "state": "active", "usedBytes": 0, "totalBytes": 0, "expiryMs": null },
  "catalog": { "games": [{ "id": "mobile-legends", "name": "Mobile Legends", "packages": ["com.mobile.legends"], "enabled": true }] },
  "configs": [{ "id": "1", "name": "Germany", "config": "[Interface]\nPrivateKey=...\nAddress=...\n[Peer]\nPublicKey=...\nEndpoint=...\nAllowedIPs=0.0.0.0/0" }]
}
```

## Key files

| File | Purpose |
|------|---------|
| `DvApplication.kt` | Libbox.setup() initialization |
| `vpn/DvVpnService.kt` | VpnService + PlatformInterface + CommandServerHandler |
| `vpn/BoxController.kt` | Application-scoped state management |
| `vpn/SingBoxConfigBuilder.kt` | WireGuard INI → sing-box JSON |
| `vpn/PlatformInterfaceImpl.kt` | Default PlatformInterface implementations |
| `vpn/TunnelState.kt` | Sealed state class |
| `net/SubscriptionClient.kt` | DV Game JSON parser |
