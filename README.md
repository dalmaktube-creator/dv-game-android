# DV Game Android

Android WireGuard client for **per-game split tunneling**. The user imports a subscription link, selects installed games, and only those Android packages enter the WireGuard tunnel.

## MVP scope

- Kotlin + Jetpack Compose
- Detect launchable apps classified by Android as `CATEGORY_GAME`
- Import a raw, JSON-wrapped, or Base64 WireGuard config from a subscription URL
- Inject `IncludedApplications` into the WireGuard interface configuration
- Connect with the official embeddable WireGuard Android tunnel library
- Game Split mode: non-selected apps use the phone's normal network
- Game Lock guidance: opens Android VPN settings for Always-on/Lockdown
- Does not persist the subscription response or private key

## Build

Requirements: JDK 17, Android SDK 35, Gradle 8.10.2.

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Important behavior

Android implements package-level routing through `VpnService.Builder.addAllowedApplication()`. The WireGuard tunnel library maps the `IncludedApplications` interface field to that API. This means IP, domain, CDN, port, and region lists are not required for the base product.

Game Lock still requires the user to enable **Always-on VPN** and **Block connections without VPN** in Android settings. Device-vendor behavior must be tested before presenting this as a guaranteed kill switch.

## Next milestones

1. Match the exact WG Gaming Panel subscription response schema.
2. Add QR import and multiple exits.
3. Add app icons, search, favorites, ping and location selection.
4. Add signed release builds and private-key storage backed by Android Keystore.
5. Add device tests for split routing and lockdown behavior.

## Security note

Cleartext HTTP is temporarily enabled because existing panel installations may expose local HTTP subscription URLs. Production deployments should use HTTPS; a later milestone will make cleartext an explicit per-profile opt-in.
