# DV Game Android

Android game-only VPN client controlled by WG Gaming Panel.

## Alpha 5 data path

Alpha 5 keeps the panel WireGuard peer, keys, endpoint, AllowedIPs, MTU and DNS, but adds an opt-in-compatible game data path based on the successful Redmi Note 14 trace:

```text
game UID -> Android VpnService -> hev-socks5-tunnel -> Xray WireGuard outbound -> existing panel peer
```

Only the selected game and installed Google identity/game-save packages are admitted to the Android TUN. DV Game itself is not admitted, so the Xray/WireGuard transport socket uses the underlying network and cannot loop into the VPN. Other apps, including browsers, remain direct.

The original official WireGuard Android tunnel dependency remains pinned in the application for config validation and compatibility. Alpha 5's UDP compatibility engine is used for the game path because the official `wireguard-go` TUN path reproduced multi-minute loading and lobby drops whenever Android per-app routing was enabled on the test device.

## Security and DNS

- Subscription fetch remains HTTPS-only with bounded responses and redirect validation.
- WireGuard configurations are validated by the official parser before use.
- The private key is retained only in memory and in the existing AES-GCM/Android-Keystore short restore lease.
- Panel DNS addresses are carried through the WireGuard outbound; no public DNS is injected.
- Exactly one panel peer is accepted by the compatibility engine.
- Android application allowlisting remains fail-closed.
- Xray and HEV binary inputs are pinned by release and SHA-256 in CI.
- Alpha 5 is arm64-v8a only.

## Build

CI fetches the pinned `AndroidLibXrayLite v26.8.20` AAR and the pinned HEV native library before building. The SHA-256 checks in `.github/workflows/android.yml` are mandatory.

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

This is an alpha build and must be validated on the target device before production use.
