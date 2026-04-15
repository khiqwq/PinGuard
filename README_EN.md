# PinGuard

LSPosed module that requires fingerprint/password verification to unpin screen-pinned apps.

[简体中文](README.md)

## Features

- Intercepts screen unpin and requires system credential verification (fingerprint/PIN/pattern)
- Goes directly to home screen after verification — **no lock screen**
- Only protects apps in LSPosed scope; other apps unpin normally
- Optional: suppress the system "To unpin this app..." toast
- Debug logging toggle

## Requirements

- Android 15+ (API 35+)
- LSPosed / LSPosed Fork
- Root (Magisk / KernelSU / APatch)

## Installation

1. Install PinGuard APK
2. Enable module in LSPosed Manager
3. Select scope:
   - **System Framework (android)** — required
   - **Apps to protect** — select as needed
4. Reboot
5. Open PinGuard and confirm the green status indicator

## Usage

1. Open a protected app and pin it (Screen Pinning)
2. When trying to unpin, the system credential dialog appears
3. Successful verification → unpins and returns to home
4. Failed/cancelled → stays pinned

## Settings

| Option | Description |
|--------|-------------|
| Enable Protection | Master switch; disabling allows normal unpin |
| Hide Exit Toast | Suppress the system "To unpin..." toast |
| Debug Log | Output verbose logs to logcat (filter: `PinGuard`) |

## How It Works

1. Hooks `stopSystemLockTaskMode()` in `system_server` to intercept unpin requests
2. Sends broadcast to the pinned app to show `KeyguardManager` credential verification
3. On success, manually dismantles `LockTaskController` internal state, bypassing the system lock screen

## 📄 License

This project is open-sourced under the [MIT License](LICENSE). Issues and PRs are welcome.
