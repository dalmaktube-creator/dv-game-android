# DV Game Android

Android game-only VPN client controlled by WG Gaming Panel.

## Alpha 10 data path

```text
approved game package -> Android VpnService -> sing-tun mixed stack
(system TCP + gVisor UDP) -> sing-box/libbox WireGuard endpoint
-> existing Iran peer -> panel-selected route/location
```

Alpha 10 keeps the field-tested Alpha 8 packet path, fixes Alpha 9's VPN-as-underlying reconnect loop, and adds a connection state machine, bounded jittered reconnect, endpoint re-resolution with A-record rotation, Android physical-network handover and deterministic cleanup. Only the package approved by the panel is admitted to Android's VPN; there is no arbitrary app picker.

The previous HEV/Xray and WireGuard GoBackend execution paths have been removed. Production has one pinned engine: `libbox` v1.13.19; the WireGuard Android dependency is temporarily retained only for its battle-tested config parser. The release workflow verifies the upstream archive SHA-256 before every build.

## Baseline and stability notes

- `docs/BASELINE-alpha08-fa.md`: immutable identity and packet parameters of the successful Alpha 8 device test.
- `docs/PHASE-1-STABILITY-fa.md`: state machine, reconnect, network handover and packet-quality acceptance criteria.

## Device acceptance

Validate subscription, Mobile Legends loading/lobby/match, Wi-Fi ↔ Cellular handover, idle NAT survival, panel RX/TX accounting and per-app leak isolation on Redmi Note 14 / Android 14. Browser and Speedtest must stay outside the tunnel.

## Licensing

The APK bundles the pinned `proother/sing-box-lib` v1.13.19 prebuilt AAR. Distribution and corresponding-source obligations must comply with GPLv3 before production release.
