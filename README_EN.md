<div align="center">

<img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="80" alt="icon" />

# PinGuard

**LSPosed module — require fingerprint / password to unpin screen-pinned apps**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 15+](https://img.shields.io/badge/Android-15%2B-green.svg)](#requirements)
[![LSPosed](https://img.shields.io/badge/LSPosed-Module-orange.svg)](#installation)

[简体中文](README.md)

</div>

---

## Features

- Intercepts screen unpin and requires system credential verification (fingerprint / PIN / pattern)
- Goes directly to home screen after verification — **no lock screen**
- Only protects apps in LSPosed scope; other apps unpin normally
- Optional: suppress the system "To unpin this app..." toast
- Debug logging toggle

## Requirements

- Android 15+ (API 35+)
- LSPosed / LSPosed Fork
- Root (Magisk / KernelSU / APatch)

## Installation

1. Download and install APK from [Releases](https://github.com/khiqwq/PinGuard/releases)
2. Enable module in LSPosed Manager
3. Select scope:
   - **System Framework (android)** — required
   - **Apps to protect** — select as needed
4. Reboot
5. Open PinGuard and confirm the green status indicator

## Usage

1. Open a protected app and enable Screen Pinning
2. When trying to unpin, the system credential dialog appears
3. Successful verification → unpins and returns to home
4. Failed / cancelled → stays pinned

## Settings

| Option | Description |
|--------|-------------|
| Enable Protection | Master switch; disabling allows normal unpin |
| Hide Exit Toast | Suppress the system "To unpin..." toast |
| Debug Log | Output verbose logs to logcat (filter: `PinGuard`) |

## How It Works

```
User attempts to unpin
        │
        ▼
  stopSystemLockTaskMode()  ← Hooked
        │
        ▼
  Is app in protected list? ──No──▶ Allow normally
        │Yes
        ▼
  Broadcast to pinned app
        │
        ▼
  Show KeyguardManager credential dialog
        │
    ┌───┴───┐
    ▼       ▼
  Pass     Fail
    │       │
    ▼       ▼
 Manually   Stay
 dismantle  pinned
 LockTask
 internals
    │
    ▼
 Return to home
 (no lock screen)
```

## Related

- [ImagePicker (钉图)](https://github.com/khiqwq/ImagePicker) — Minimal image viewer with screen pinning support

## 📄 License

This project is open-sourced under the [MIT License](LICENSE). Issues and PRs are welcome.
