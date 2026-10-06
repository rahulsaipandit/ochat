# ochat Design Notes: One Codebase for iOS and Android

Status: draft for discussion. Claims marked **(verify)** have not been checked against a primary source.

## 1. Context

`ochat` is a personal fork of [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android), the Kotlin / Jetpack Compose Android (and Wear OS) client. It is **not** a fork of [permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat), the Swift / SwiftUI iOS and macOS client. The two are sibling implementations of the same wire protocol from the same organization, with unrelated codebases.

| | ochat / bitchat-android | bitchat (iOS/macOS) |
|---|---|---|
| Language | Kotlin + some Java | Swift |
| UI | Jetpack Compose (Material 3) | SwiftUI |
| Platforms | Android, Wear OS | iOS, macOS |
| Build | Gradle | Xcode / `swift test` |
| License | GPL-3.0 (`LICENSE.md`) | Public domain |

Feature differences (per each README):
- **Wi-Fi Aware transport:** higher-bandwidth local transport on supported Android devices. iOS/macOS relies on BLE only **(verify: iOS 26 added Wi-Fi Aware support)**.
- **Tor (Arti):** built-in, for private internet connectivity (`tools/arti-build/`).
- **Channel passwords:** Argon2id + AES-256-GCM.
- **Wear OS:** dedicated build in `wear/`.
- **Shared:** BLE mesh (offline, max 7 hops, Noise) + Nostr (geohash channels, private-message fallback), IRC-style commands, emergency wipe, cross-client protocol compatibility.

## 2. Goals and non-goals

**Primary goal: one codebase for iOS and Android.** Every option below is judged first on whether it delivers a single codebase (logic and UI) that builds for both platforms. Anything that needs a second UI or a second implementation of the core is out of scope unless it is a thin platform shim (for example the CoreBluetooth transport).

**Goals**
- G1. One codebase (logic and UI) that builds for iOS and Android. This is the primary goal.
- G2. Keep wire compatibility with existing bitchat clients (golden-vector tests).
- G3. Keep security-critical code (Noise, channel crypto, protocol) in one implementation.
- G4. Preserve offline BLE mesh behavior on both platforms.

**Non-goals**
- Replacing the existing Swift bitchat iOS client for its users.
- Wear OS parity on iOS (the Wear client stays Android-only in every option).
- Rewriting the protocol.

**Decisions**
- **One codebase is the goal,** which is why a new iOS app is built rather than relying on the existing Swift client.
- **Licensing: accept the constraint.** The repo stays GPL-3.0 and the iOS build inherits it. App Store distribution of GPL code is a known friction point; it is accepted, and distribution channels (for example TestFlight, sideloading or alternative marketplaces) are decided later.
- **iOS background BLE will be prototyped** before extraction work starts (section 5, item 1).

## 3. Options

### A. KMP + Compose Multiplatform (CMP)
Shared Kotlin core in `commonMain`, shared Compose UI, thin platform shells.
- Reuses the existing, tested Kotlin protocol, Noise, mesh and Nostr code (after extraction, see risks).
- One UI codebase on both platforms. Compose Multiplatform for iOS reached stable in 2025 **(verify current status)**.
- iOS BLE (CoreBluetooth, both central and peripheral roles) is written in Swift against a Kotlin `Transport` interface defined in `commonMain`. Swift implements the exported Kotlin interface; no bridging header is involved.
- Wear OS remains an Android module sharing the same core.

### B. Expo / React Native (Dev Client + Expo Modules API)
Shared TypeScript for UI and logic, with custom Swift and Kotlin modules for BLE.
- Expo Go cannot load custom native Bluetooth code, so a development build (`expo-dev-client`, Xcode / Android Studio or EAS) is required. This is expected, not a failure of Expo.
- Existing RN BLE libraries are central-role only; a mesh also needs the peripheral/GATT-server role, so custom native code is required on both platforms.
- Recent SDKs add experimental *inline modules* (Swift/Kotlin next to app files) and `expo-type-information` (generates TypeScript types from Swift modules). **(verify: SDK version, status, and that the cited changelog covers them; the SDK 58 beta changelog reviewed did not mention `expo-type-information`.)** They reduce glue-code friction only. They do not remove the need to rewrite the core, and they are experimental.
- Noise sessions, Argon2id / AES-GCM channel crypto, and the binary protocol would be rewritten in TypeScript and re-verified byte-for-byte against both existing clients. The existing Kotlin code is discarded.

### C. Flutter
Not evaluated. It would also require a full rewrite (Dart) and custom BLE peripheral code. Listed so it is explicitly ruled out unless there is a reason to revisit.

### Comparison

| | A. KMP + CMP | B. Expo / RN |
|---|---|---|
| One codebase (G1) | Yes (logic + UI) | Yes (logic + UI) |
| Reuses existing Kotlin core | Yes, after extraction | No, full rewrite |
| Wire compatibility (G2) | Existing golden vectors run against the shared module | Re-derive and re-test in TypeScript |
| One security-critical implementation (G3) | Yes | Yes, but a new, unaudited one |
| Native BLE | Swift (iOS) + existing Kotlin (Android) | New Swift + new Kotlin modules |
| Maturity risk | CMP iOS, Kotlin/Native binary size and interop | Experimental inline modules, rewrite risk |
| Wear OS | Shares core | Separate Kotlin project |

## 4. Recommendation

**Option A: KMP + Compose Multiplatform**, conditional on the gating checks in section 6.

Reasons:
1. It meets G1 without discarding a mature, tested core. Option B meets G1 by rewriting the security-critical code, which is the highest-risk part of the project.
2. "Tested" is not "audited". Neither option has an audit; A at least keeps the existing test and golden-vector coverage.
3. The Expo inline-module and type-generation features address the thin native shim, which is the smallest part of the work.
4. The earlier idea of native SwiftUI on iOS is dropped, because it would be a second UI codebase and violate G1.

Reconsider Option B only if the team is TypeScript-first and willing to fund a crypto rewrite plus an independent review.

### Proposed shape
- New `:shared` KMP module with `commonMain` containing `protocol/`, `noise/`, `crypto/`, `mesh/` logic (routing, dedup, TTL, fragmentation), `identity/`, `nostr/`.
- `commonMain` defines `Transport` (and storage / secure-key interfaces). `androidMain` keeps the existing BLE GATT and Wi-Fi Aware transports. `iosMain` / Swift supplies a CoreBluetooth implementation.
- Shared Compose Multiplatform UI. Android and Wear OS shells stay in Gradle; a thin iOS shell hosts the CMP view controller.
- Validate with the existing protocol / golden-vector tests and Mesh Lab on both an Android and an iOS device before trusting interop.

## 5. Risks and gating checks

Do these before committing to extraction work.

1. **iOS background BLE (gating, will be prototyped).** Backgrounded iOS apps have restricted advertising and scanning, and background mesh relaying is hard. The protestchat project documents the same unresolved problem. Prototype a CoreBluetooth central + peripheral that survives backgrounding and interoperates with an Android device. Exit criteria: discovery, connection and a message round trip with the iOS app foregrounded, backgrounded and screen-locked, plus measured battery cost. Record what degraded behavior is acceptable.
   **Does KMP + Compose Multiplatform limit iOS Bluetooth access? No.** The limits come from iOS, not from the language, and a Swift-only app has the same ones.
   - The app is still a native iOS app: an Xcode project with a Swift entry point (`AppDelegate`, `Info.plist`). Compose Multiplatform only renders UI inside it. Background modes (`UIBackgroundModes`), entitlements and permission strings are configured in that shell exactly as in a Swift app.
   - Kotlin/Native ships bindings for Apple frameworks, so `CBCentralManager`, `CBPeripheralManager` and their delegates can be called from Kotlin, and Kotlin code runs when iOS wakes the app in the background.
   - Swift remains an option: the shared `Transport` interface lets the iOS BLE layer be written in Swift with everything else in Kotlin.

   Real friction to expect:
   - State restoration must be set up at launch (restore identifier, `willRestoreState`); this belongs in the thin Swift shell.
   - Delegate protocols, Objective-C nullability and dispatch-queue threading are more awkward from Kotlin than from Swift.
   - Debugging Kotlin/Native in Xcode is weaker than debugging Swift.
   - iOS rules that bind every implementation, notably restricted background advertising and scanning.

   Plan to de-risk:
   1. Write the iOS BLE transport in Swift behind the `Transport` interface. Evaluate reusing code from the existing bitchat iOS client (public domain); check how separable it is before relying on it.
   2. Run the background-BLE prototype in Swift first, to isolate the OS question from the Kotlin question.
   3. Move it into Kotlin later only if there is a reason.
2. **Licensing (decided: accept).** This repo is GPL-3.0 and the iOS build inherits it. Residual risk is App Store distribution; track it but it no longer blocks the design.
3. **Extraction cost.** The shared packages use JVM and Android APIs (JCA / BouncyCastle-style crypto, OkHttp networking, JNI for Arti, Android types in `mesh/`). Moving to `commonMain` means swapping crypto and networking libraries (the security-critical part) and separating BLE from routing logic. This is a refactor, not a move. Estimate it before committing.
4. **Tor on iOS.** Arti is currently built for Android. An iOS build is separate work, or Tor is Android-only at first.
5. **Coroutines / Flow in `iosMain`.** Confirm `mesh/` and `service/` code ports cleanly, or add an abstraction layer.
6. **CMP iOS maturity and binary size.** Verify current stability and measure app size.
7. **Wi-Fi Aware on iOS** could enable a cross-platform high-bandwidth transport; see the spike in section 7.
8. **Rust core via UniFFI** is a common alternative for sharing crypto and protocol across platforms. It is not evaluated here; note it if option A's extraction cost proves too high.

## 6. Comparison: ochat vs protestchat

[ni5arga/protestchat](https://github.com/ni5arga/protestchat) (MIT) is an unrelated, early-stage project with overlapping goals. Facts below are from its README at time of writing **(verify before relying on them)**.

| | ochat | protestchat |
|---|---|---|
| Stack | Kotlin / Compose, Android + Wear OS | ~85% shared TypeScript (React Native / Expo) with Swift and Kotlin BLE modules |
| Native layer | Full Android client | Intended "dumb byte pipe": advertise, discover, connect, send and receive bytes. No chat logic or crypto |
| Routing | TTL-limited flooding (max 7 hops) with gossip sync | Epidemic carry-and-forward of unexpired sealed envelopes; recipient is whoever can decrypt |
| Metadata | Packet header carries plaintext 8-byte sender and recipient IDs (`docs/file_transfer.md`) | Claims a captured phone reveals nothing about who talked to whom; check against its threat model |
| Channels | IRC-style, passphrase-protected option | Passphrase-only, no owner / admin / kick, by design |
| Groups | Not a distinct fan-out model | Fan-out, one sealed copy per member, capped at 15 |
| Transports | BLE, Wi-Fi Aware, Nostr, optional Tor | BLE; Wi-Fi Direct mentioned; LoRa / gateway planned |
| iOS status | Not applicable (Android-only) | Android builds; iOS blocked on native integration |
| Maturity | Release gate, Mesh Lab, golden vectors, security review notes (`docs/security-review-jul-27.md`) | ~50 commits; no independent audit; no reproducible builds; iOS background relaying unresolved; scrypt (N=2^14), Argon2id pending |
| License | GPL-3.0 | MIT |

Takeaways for ochat:
- **Their critique of channels:** protestchat says bitchat channel commands were validated only by the issuing client, so any member could seize a channel. ochat inherits that design; review `docs/` and the channel code against the claim before dismissing or adopting it.
- **"One implementation to audit"** is a good principle that Option A also satisfies.
- **Their design is intent, not shipped outcome.** Judge both projects by what is built and tested, not by what is planned.
- **iOS background BLE** is unsolved there too, which supports treating it as gating risk 1.

## 7. Transport decision: Google Nearby Connections

Decision: do not use Nearby Connections, as a primary or fallback transport.

- **iOS reach.** Per Google's documentation, Nearby on iOS has only brought up the Wi-Fi LAN medium, which requires both phones to be on the same network, with BLE listed as "in development" **(verify current status and date)**. That gives no path in an infrastructure-less scenario.
- **Fallback value.** A fallback exists to cover what the primary transport cannot. Where shared Wi-Fi exists, peers still need app-level discovery and transport, and AP client isolation is common; where it does not, Nearby adds nothing on iOS.
- **Advertising identifier.** Nearby gives no control over the advertised identifier, which can act as a tracking beacon **(verify)**. Owning the advertisement allows rotation.
- **Dependencies.** It requires Google Play Services on Android, and iOS distribution is via Swift Package Manager only, with no CocoaPods support (open request since May 2023), which affects React Native / Flutter plugin systems.
- **Local high-bandwidth link.** On supported Android devices this repo's Wi-Fi Aware transport already covers it, without a shared network or Play Services.

### Spike: is iOS 26 Wi-Fi Aware usable as a cross-platform transport?

Background (from Apple and press coverage, **verify against Apple documentation**): iOS 26 ships a `WiFiAware` framework, supported on iPhone 12 and later. It needs the `com.apple.developer.wifi-aware` entitlement and services declared under the `WiFiAwareServices` Info.plist key, with Publishable / Subscribable roles. Devices are paired through system UI (DeviceDiscoveryUI or AccessorySetupKit). Android has had Wi-Fi Aware APIs since Android 8, but only on devices with hardware support. Apple developer-forum threads report cross-platform interoperability challenges, and a recent third-party write-up shows an embedded device connecting to an iPhone.

How to check:
1. **Read the primary sources.** Apple's WiFiAware framework documentation and WWDC25 session 228; Android's `android.net.wifi.aware` docs. Note the supported roles, pairing requirements, data-path security, and any background restrictions.
2. **Check the pairing model against the mesh.** If iOS requires user-mediated pairing per peer, it cannot auto-connect to arbitrary strangers, which conflicts with open mesh relay. It might still suit a "paired contacts" fast path (file and voice transfer).
3. **Run a two-device test.** An iPhone 12 or later on iOS 26 and a Wi-Fi Aware-capable Android phone. Confirm `PackageManager.FEATURE_WIFI_AWARE` on the Android device. Test discovery, pairing, a data path, and throughput, with the app foregrounded and backgrounded.
4. **Compare against BLE.** Measure throughput, latency, range and battery against the BLE mesh.
5. **Decide.** Adopt it as an optional transport behind the shared `Transport` interface only if interoperability works and pairing is acceptable. Otherwise record it as not viable and stay BLE-first on iOS.

Do not use device-identifying details or real location data in test notes; keep logs local (see `AGENTS.md`).

## 8. Comparison: Briar's transports vs ochat

ochat already has internet transports: Nostr (public relays, geohash channels, private-message fallback) plus optional Tor (Arti). The question is whether Briar's design suggests anything missing. Facts below come from Briar's documentation **(verify and add links for each)**.

Briar:
- **Short-range sync is contact-to-contact.** Briar's sync (BSP over BTP) runs directly between devices that already trust each other, over Bluetooth, Wi-Fi Direct or Wi-Fi LAN. There is no relay through strangers.
- **Offline delivery uses Briar Mailbox,** a small, often self-hosted server reached over Tor to drop off and pick up messages.
- **Own radio plugins.** Wi-Fi Direct and LAN are Briar's own plugins, not a third-party SDK. This is consistent with, but only weak evidence for, owning the radio layer.
- **Tor is mandatory** for all internet sync, with no public-relay equivalent.

Differences and decisions:
- **Relay through strangers vs contact-only.** Briar limits who holds your ciphertext but has no courier effect for contacts who are never in range or online together. ochat relays through nearby peers to reach a scattered crowd. Because the packet header carries plaintext sender and recipient IDs, do not claim a larger anonymity set without analysis; document the metadata exposure.
- **Mailbox.** A self-hosted, Tor-reachable store-and-forward box is worth evaluating as an additive feature for guaranteed delivery when offline. It needs a design: threat model, trust, and fit with the Nostr fallback. It is not a replacement for BLE relay or Nostr.
- **Mandatory vs optional Tor.** Briar's approach removes the relay operator from the trust picture but fails where Tor is blocked. ochat keeps public Nostr relays with optional Tor. Public relays see client IP and message metadata unless Tor is on; document this clearly.
