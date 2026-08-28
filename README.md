# DV Game Android

Android game-only VPN client controlled by the existing WG Gaming Panel.

## Alpha 6 data path

```text
approved game package -> Android VpnService -> sing-tun mixed stack
(system TCP + gVisor UDP) -> sing-box WireGuard endpoint -> existing Iran peer
-> existing panel-selected Pure Hysteria route/location
```

Alpha 6 does not add a test location and does not change the panel, server tunnels, `/sub/`, QR codes, normal WireGuard configs, quota, expiry, or existing clients. It consumes the already-available `/dvgame/<token>` JSON and converts the same single-peer WireGuard profile locally on Android.

The failed Alpha 5 HEV -> SOCKS -> Xray chain has been removed. The replacement is one pinned upstream core (`sing-box/libbox` v1.13.19), one TUN, and one WireGuard endpoint. Only the package approved by the panel is admitted to Android's VPN. There is no arbitrary app picker.

## First device test

Use the existing personal client and any already-configured location. Validate subscription, connection, Mobile Legends loading, lobby, match start, UDP continuity, and panel RX/TX accounting on the Redmi Note 14 / Android 14.

## Licensing

sing-box/libbox is built from the unmodified upstream v1.13.19 source in CI. Distribution must comply with its GPLv3 license; upstream source and exact build command are recorded in the workflow.
