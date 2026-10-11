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

# Design Next Version
I want to explore solutions that let me compile for iOS, Android. One option is the latest Expo SDK that supports native swift and kotlin code. The other option is KMP (Kotlin Multi Platform).

However we have to see if that will work because earlier **Expo Go failed to work** - it could NOT load custom native Bluetooth code and required a development build, which means Xcode and/or Android Studio.

## Option comparison: Expo (custom native modules) vs. KMP

Both options end up needing a custom native build either way — Expo Go was a dead end specifically because this app's core requirement (custom BLE GATT code) cannot run in Expo's prebuilt sandbox. That removes Expo Go's main selling point (no Xcode/Android Studio, no dev build) and leaves two "write native BLE, share the rest" architectures to choose between.

Yes — the Expo Modules API (current SDK) does compile real native Swift and Kotlin, not an interpreted bridge, and it can wrap this repo's existing BLE/GATT code directly on Android. The nuance is *what* that native compilation buys you: it still only solves the JS-to-native call boundary, not cross-platform code reuse. The Kotlin code compiled via an Expo Module runs on Android only — reaching iOS still requires a from-scratch Swift reimplementation of anything you didn't keep in the shared TypeScript layer. That is the opposite of KMP, where Kotlin/Native lets the *same* `commonMain` Kotlin (not a port of it) compile for both targets. So the real decision isn't "can Expo compile native code" (it can) — it's "do you want the security-critical logic (`noise/`, `crypto/`, `protocol/`, `mesh/`) to live once in Kotlin and run on both platforms (KMP), or live once in TypeScript with thin per-platform native shims (Expo), or get reimplemented per-platform in both Swift and Kotlin native modules (Expo, if you push logic into the native layer instead of TS) — the last option recreates the exact two-implementation problem this doc is trying to avoid.

| | Expo (Dev Client + Expo Modules API) | KMP (Kotlin Multiplatform) |
|---|---|---|
| Shared logic language | TypeScript (new) | Kotlin (**already written** — this repo's `crypto/`, `mesh/`, `noise/`, `protocol/`, `nostr/`, `identity/` packages) |
| Native BLE | New Swift module + new Kotlin module via Expo Modules API | Android: reuse existing `mesh/` BLE GATT code. iOS: new Swift, called from Kotlin/Native via `expect`/`actual` |
| Build requirement | `expo-dev-client` + EAS Build (or local Xcode/Gradle) — Expo Go cannot load it | Xcode (iOS app shell) + Gradle (Android app shell + shared module) |
| UI | One shared UI (React Native) across iOS/Android | Native UI per platform (Compose on Android, SwiftUI on iOS), or Compose Multiplatform (iOS target still beta) |
| Crypto/protocol risk | Full rewrite of Noise sessions, Argon2id/AES-GCM channel crypto, and the binary wire protocol in TypeScript — new, unaudited implementation of security-critical code | Existing, already-tested Kotlin crypto/protocol code moves into a shared module largely unchanged |
| Cross-platform wire compatibility | Must re-derive byte-for-byte compatibility with the iOS Swift client and this Android client from scratch | Shared module can be unit-tested directly against this repo's existing protocol/golden-vector tests |

## Recommendation

**KMP**, not Expo, for this project specifically — because the "shared core" already exists in Kotlin. Rewriting `noise/`, `crypto/`, `protocol/`, and `mesh/` in TypeScript to fit Expo would mean re-implementing and re-auditing security-critical code in a second language, which directly fights the "one implementation of the security-critical code to audit" principle in [How it Works](#how-it-works). Expo would make sense if this project were starting from a TypeScript codebase; it is not — it is starting from a mature Kotlin one.

Proposed shape:
- Extract `crypto/`, `noise/`, `protocol/`, `mesh/` (routing, dedup, TTL, fragmentation), `identity/`, and `nostr/` from `app/` into a new `:shared` KMP module (`commonMain`), keeping Android's existing BLE transport as the `androidMain` `actual` implementation.
- Add an `iosMain` `actual` BLE transport backed by a small CoreBluetooth Swift layer (the "dumb byte pipe" from How it Works), exposed to Kotlin/Native through a Swift/Objective-C bridging header or Kotlin/Native cinterop.
- Keep per-platform UI native (Jetpack Compose on Android/Wear OS, SwiftUI on iOS) rather than adopting Compose Multiplatform for iOS immediately, since that target is still less mature than Android/Desktop.
- Validate with the existing protocol/golden-vector and Mesh Lab tests against both the Android app and a new thin iOS shell before trusting cross-platform interop.

## Open risks to validate before committing

- Kotlin/Native interop overhead and binary size impact on the iOS app.
- Whether `kotlinx.coroutines`/`Flow` usage in `mesh/` and `service/` ports cleanly to `iosMain`, or needs an abstraction layer.
- Foreground-service-equivalent background BLE behavior on iOS (background modes are far more restrictive than Android's foreground service) — this changes duty-cycling and reconnection logic, not just the transport.

# Why we do not use Google Nearby Connections
The obvious choice for cross-platform device-to-device is Google's Nearby Connections. We built on it first and then removed it, for two independent reasons:

On iOS it only ever brings up the Wi-Fi LAN medium — both phones must already be joined to the same Wi-Fi network. In a jammed square with no infrastructure that is not a degraded path, it is no path at all. Google lists iOS BLE as "in development."
It ships for iOS via Swift Package Manager only, with no CocoaPods support — an open request since May 2023, filed precisely because React Native and Flutter plugin systems depend on CocoaPods.
So even solving the packaging would have produced an iPhone that cannot reach an Android phone at a protest. We now own the radio outright in modules/ble-mesh/: CoreBluetooth on iOS, BLE GATT on Android, no third-party dependency on either side.

The migration is an upgrade rather than a workaround. Nearby gave us no control over the advertising identifier, and a stable BLE identifier is a tracking beacon — open problem #2 in the threat model. Owning the advertisement is the only way to rotate it, which we now do every 15 minutes from fresh CSPRNG bytes, with no name or key material advertised at all.

## Could Nearby Connections work as a fallback instead of a primary transport?

No, for the same two reasons, not just one of them:

- **It doesn't help the target scenario.** The whole point of a fallback is to cover the case our primary transport (BLE mesh) can't — a jammed square with no infrastructure. On iOS, Nearby's only real medium there is Wi-Fi LAN, which *requires* an existing Wi-Fi network. If a shared Wi-Fi network existed, that same network already gives both phones a route to each other without Nearby; if it doesn't exist (the actual protest/shutdown case), Nearby degrades to BLE "in development" on iOS — i.e. it contributes nothing in exactly the moment a fallback is supposed to matter.
- **The tracking-identifier problem isn't a primary-transport-only risk.** Any time Nearby is active — even as a rarely-used fallback — it advertises a device identifier we don't control and can't rotate. A fallback that is "usually off" still opens that window whenever it turns on, which reintroduces open threat-model problem #2 instead of closing it.

The one scenario where Nearby could plausibly add value — two Android phones already sharing a Wi-Fi/hotspot network wanting a higher-bandwidth local link — is already served by this repo's own Wi-Fi Aware transport, without taking on Google Play Services as a dependency or the SPM/CocoaPods packaging friction on iOS. That leaves no case where adding Nearby, even opt-in, is worth the added attack surface and dependency weight.

# Comparison: Briar's transports vs. ours

Do we already support internet-based connections? **Yes.** This repo's Nostr layer (public relays, geohash channels, private-message fallback when the mesh is unreachable) plus optional built-in Tor (Arti) is already our internet transport — see [Platform & stack differences](#platform--stack-differences) and the Nostr Protocol section of the README. The question below is really "does [Briar](https://github.com/briar/briar)'s *design* for combining internet and short-range transports suggest anything we're missing," not "do we have one at all."

Briar's model, confirmed from its wiki and site:

- **Bluetooth/Wi-Fi is contact-to-contact only, not a mesh.** Briar's sync protocol (BSP, over a delay-tolerant transport-security layer called BTP) runs directly between two devices that already trust each other as contacts. There is no routing table *and* no epidemic relay through strangers either — a message only moves when the sender's device and the recipient's device are directly connected, over Bluetooth, Wi-Fi Direct, or Wi-Fi LAN.
- **Asynchronous delivery when contacts are never both online is solved by Briar Mailbox**, not by other users' phones carrying your data. Mailbox is a small server (commonly self-hosted, e.g. on a Raspberry Pi) that a user reaches over Tor to drop off and pick up messages for their contacts. Briar's own "social/public mesh" research explores delay-tolerant forwarding through non-contacts, but that is a research track, not the shipped Bluetooth/Wi-Fi plugin behavior.
- **Wi-Fi Direct and Wi-Fi LAN are both implemented as Briar's own plugins** (in `bramble-android`), not via a third-party SDK like Nearby Connections — independent confirmation that owning the radio layer directly, as this repo already does in `mesh/` and `wifi-aware/`, is the path a comparable privacy-first project converged on too.
- **Tor is mandatory for all of Briar's internet sync**, with no public-relay equivalent — every internet message goes over a Tor circuit either straight to the contact or to their Mailbox. This repo instead defaults to public Nostr relays (visible to the relay operator unless Tor is also enabled) and treats Tor as an added privacy layer rather than the only internet path.

## What's actually different from our design, and why we keep ours

- **Contact-only direct sync vs. epidemic relay through strangers.** Briar's no-relay-through-strangers rule shrinks the set of devices that ever hold your ciphertext, but it means two contacts who are never in range of each other *and* never both reach a Mailbox simply don't sync — there's no "courier" effect. This repo's mesh deliberately relays through any nearby peer (the "Four ways to send" model in [How it Works](#how-it-works)), trading a larger anonymity set of devices briefly holding undecryptable envelopes for reach across a scattered, disconnected crowd — which is the scenario (jammed square, moving crowd) this project is built for.
- **Mailbox is worth a second look as a design input, not a reason to switch models.** A self-hosted, Tor-reachable store-and-forward box is a reasonable complement to Nostr relays for guaranteed-delivery-when-offline — but it's an additive feature (an optional, self-hosted drop box), not a replacement for either the epidemic BLE relay or the public Nostr fallback already in place.
- **Mandatory Tor vs. optional Tor over public relays** is a real trade-off worth flagging, not copying wholesale: Tor-only, as Briar does it, removes the public-relay operator from the trust picture entirely, at the cost of requiring Tor connectivity (which is itself blockable/fingerprintable in some jammed/censored environments) for *any* internet sync. Keeping public Nostr relays as a non-Tor fallback, with Tor as an optional hardening layer, keeps us working in places where Tor itself is the thing being blocked.