This is a fork of the Android implementation of bitchat, fully protocol-compatible with the [iOS version](https://github.com/permissionlesstech/bitchat) for cross-platform mesh communication.

`ochat` (this workspace) is a personal fork of **permissionlesstech/bitchat-android**, the Kotlin/Jetpack Compose Android (and Wear OS) client — not a fork of `permissionlesstech/bitchat`, the Swift/SwiftUI iOS and macOS client. They are sibling implementations of the same wire protocol, built by the same org but with entirely different, unrelated codebases.

## Relationship
- **This repo (ochat)**: a fork of [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android), written in **Kotlin** (Jetpack Compose/MVVM), targeting **Android** (and Wear OS).
- **bitchat (iOS/macOS)**: a separate, independent repo from the same organization, written in **Swift** (SwiftUI/Xcode project).
- They are **not forks of each other** — both implement the same bitchat wire protocol so they can talk to each other over Bluetooth mesh and Nostr, but the codebases are unrelated (different language, different architecture, different maintainers' decisions diverge over time).

## Platform & stack differences

| | This repo (ochat / bitchat-android) | bitchat (iOS/macOS) |
|---|---|---|
| Language | Kotlin + some Java | Swift |
| UI | Jetpack Compose (Material 3) | SwiftUI |
| Platforms | Android, Wear OS | iOS, macOS |
| Build | Gradle (`./gradlew assembleDebug`) | Xcode / `swift test` / `just` |
| Architecture pattern | MVVM with `MeshForegroundService`, `UnifiedMeshService`, `MessageRouter`, etc. | MVVM-ish with Views/ViewModels/Services |
| License | GPL-3.0 (per this repo's LICENSE.md) | Public domain |

## Feature differences (per each README)
- **Wi-Fi Aware transport**: this repo has a higher-bandwidth local mesh transport via Wi-Fi Aware on supported devices; the iOS/macOS client relies on Bluetooth LE mesh only.
- **Tor support**: this repo has built-in Tor (Arti) for private internet connectivity (see `tools/arti-build/`).
- **Channel passwords**: this repo supports password-protected channel chats (Argon2id + AES-256-GCM).
- **Wear OS app**: this repo has a dedicated Wear OS build (`wear/`); iOS/macOS has no wearable equivalent.
- Both share the core dual-transport design: BLE mesh (offline, max 7 hops, Noise Protocol encryption) + Nostr (geohash location channels, private envelopes as fallback), IRC-style commands, emergency wipe, and cross-platform protocol compatibility.

## How it Works
Off-grid messaging for internet shutdowns and jammed protests. Phones talk directly to each other over Bluetooth — no cell tower, no ISP, no server, no account.

┌──────────────── shared TypeScript (~85%) ────────────────┐
│  UI · SQLite · sealing · dedup · relay · expiry          │
├──────────────────────────┬───────────────────────────────┤
│  Swift (iOS)             │  Kotlin (Android)             │
│  CoreBluetooth           │  BLE GATT                     │
└──────────────────────────┴───────────────────────────────┘
              BLE / Wi-Fi Direct — no tower, no ISP

              he native layer is a dumb byte pipe: advertise, discover, connect, send bytes, receive bytes. It contains no chat logic, no storage, and no crypto, so there is exactly one implementation of the security-critical code to audit rather than three that drift apart.

Routing is epidemic, not addressed. There are no routing tables, because a routing table is a map of who talks to whom. Every phone carries every unexpired envelope it has seen and offers it to every peer it meets; the recipient is simply whoever can decrypt it. This costs battery and bandwidth and buys the property that a captured phone reveals nothing about who was talking to whom.

It also means a phone is a courier. Someone who walks out of a jammed zone carries queued messages with them and delivers them on the other side.

Four ways to send
Who reads it	Use it for
Everyone nearby	Anyone in range, including police running this app	Crowd warnings: "exit blocked at gate 4"
Channel	Anyone with the passphrase	An affinity group that needs to grow by word of mouth
Group	Only the people you added	Your actual crew
Direct	One person	Everything sensitive
Channels have no owner, no admin, no kick — a channel is a passphrase and nothing else. That is deliberate: BitChat's channel commands were validated only by the issuing client, so any member could seize a channel or strip its encryption. A construct with no privileged operations has none to forge. The cost is that a leaked passphrase ends the channel; start a new one.

Groups are fan-out — one separately sealed copy per member, no shared group key, so there is no rekeying problem and no group cryptography to get wrong. Capped at 15 people because each message costs N transmissions over a radio Google documents as low-bandwidth.

Layout
src/lib/
  crypto-core.ts    sealing + channel keys — pure, no RN imports, fully tested
  crypto.ts         keystore wrapper around crypto-core
  protocol.ts       wire format, padding, expiry
  db.ts             SQLite: messages, contacts, channels, groups, envelope cache
  store.ts          MeshStore interface — lets the engine run against memory in tests
  mesh.ts           the engine — sealing, dedup, store-and-forward, relay
  transport.ts      radio abstraction (our BLE GATT today, LoRa/gateway later)
  conversation.ts   derives the mode and its warning, in ONE place
  app-state.tsx     React bindings
src/app/            home, chat/[id], add, verify/[id], join-channel, new-group, settings
modules/nearby-mesh/    the Swift + Kotlin native module
scripts/doctor.sh       checks your build toolchain and names the fix for anything missing
docs/THREAT-MODEL.md
mesh.ts takes its transport and store by injection, so the whole engine — relaying, dedup, hop limits, channels, fan-out — is exercised by npm test with no radio and no phone.