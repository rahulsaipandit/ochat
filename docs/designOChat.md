This is a fork of the Android implementation of bitchat, fully protocol-compatible with the [iOS version](https://github.com/permissionlesstech/bitchat) for cross-platform mesh communication.

`ochat` (this workspace) is a personal fork of the Android implementation of bitchat **permissionlesstech/bitchat-android** — the iOS/macOS Swift client — not related code-wise to `bitchat`. They're sibling implementations of the same protocol, built by the same org but in entirely different languages/platforms. 

Here's the breakdown:
## Relationship
- **This repo (ochat)**: a fork of [permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat), the **iOS/macOS** client, written in **Swift** (SwiftUI/Xcode project).
- **bitchat-android**: a separate, independent repo from the same organization, written in **Kotlin** (Jetpack Compose/MVVM), targeting **Android** (and Wear OS).
- They are **not forks of each other** — both implement the same bitchat wire protocol so they can talk to each other over Bluetooth mesh and Nostr, but the codebases are unrelated (different language, different architecture, different maintainers' decisions diverge over time).

## Platform & stack differences

| | This repo (ochat / bitchat-iOS) | bitchat-android |
|---|---|---|
| Language | Swift | Kotlin + some Java |
| UI | SwiftUI | Jetpack Compose (Material 3) |
| Platforms | iOS, macOS | Android, Wear OS |
| Build | Xcode / `swift test` / `just` | Gradle (`./gradlew assembleDebug`) |
| Architecture pattern | MVVM-ish with Views/ViewModels/Services | MVVM with `MeshForegroundService`, `UnifiedMeshService`, `MessageRouter`, etc. |
| License | Public domain | GPL-3.0 (per its LICENSE.md) |

## Feature differences (per each README)
- **Wi-Fi Aware transport**: bitchat-android has a higher-bandwidth local mesh transport via Wi-Fi Aware on supported devices; this repo relies on Bluetooth LE mesh only.
- **Tor support**: bitchat-android advertises built-in Tor (Arti) for private internet connectivity. (Note: this repo does have `ChatViewModelTorTests.swift`, so Tor support may exist here too — worth confirming, but it's not called out in this README's feature list.)
- **Channel passwords**: bitchat-android explicitly lists password-protected channel chats (Argon2id + AES-256-GCM).
- **Wear OS app**: Android has a dedicated Wear OS build; iOS/macOS has none (no wearable equivalent).
- Both share the core dual-transport design: BLE mesh (offline, max 7 hops, Noise Protocol encryption) + Nostr (geohash location channels, private envelopes as fallback), IRC-style commands, emergency wipe, and cross-platform protocol compatibility.

If you want, I can check this repo's code (e.g., `Noise/`, `Sync/`, Tor-related files) to see which of these android-only-sounding features (Tor, Wi-Fi Aware equivalent, password-protected channels) actually exist here too, since the README feature list may not be exhaustive.