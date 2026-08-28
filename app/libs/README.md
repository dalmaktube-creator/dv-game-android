# libbox AAR

This directory must contain `libbox.aar` — the Android AAR for sing-box's libbox library.

## How to obtain

1. Go to [proother/sing-box-lib releases](https://github.com/proother/sing-box-lib/releases)
2. Download `libbox-android.aar.zip` from the latest release (currently v1.13.19)
3. Unzip and copy `libbox.aar` into this directory (`app/libs/libbox.aar`)
4. Build the project

## Version

Current: sing-box v1.13.19 (from proother/sing-box-lib)

The AAR provides the `io.nekohasekai.libbox` package with:
- `Libbox` — static setup/constant methods
- `CommandServer` — service lifecycle management
- `CommandServerHandler` — callback interface
- `PlatformInterface` — Android platform interface (~25 methods)
- `TunOptions` — TUN configuration
- `OverrideOptions` — per-app routing (includePackage/excludePackage)
- `SetupOptions` — one-time initialization

## Why local AAR?

libbox is not published to Maven Central or JitPack. It must be downloaded manually from the releases page.
