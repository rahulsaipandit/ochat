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

## 9. On-device AI: Apple Intelligence and local GGUF models

Status: proposal. Facts about third-party frameworks are marked **(verify)**.

### Goals
- A1. Optional on-device AI features that work offline and send no message content off the device.
- A2. One shared Kotlin API for all providers, consistent with G1 (one codebase).
- A3. Fail closed: no provider available means the feature is hidden, never a silent cloud fallback.

### Non-goals
- No cloud LLM calls, and no sending mesh or Nostr message content to any remote service.
- No AI features that run by default; everything is opt-in.
- No agent or tool-calling behavior that can send messages, change settings or read other data.

### Candidate use cases
- Summarize a busy channel or catch up after being offline.
- Translate incoming messages.
- Draft or rewrite an outgoing message (user reviews and sends).
- Classify or triage (for example flag likely spam). Advisory only.

### Providers

| Provider | Platform | Model | Notes |
|---|---|---|---|
| Apple Foundation Models (Apple Intelligence) | iOS 26+, Apple Intelligence-capable devices **(verify models and regions)** | System on-device model | Swift-only API. No model download or bundling. Availability depends on device, OS and user settings, so it must be queried at runtime. |
| Local GGUF via llama.cpp | iOS and Android | User-chosen GGUF file | Works on any device with enough RAM. Cross-platform C/C++ core. Needs model management. |
| Android system AI (for example AICore / Gemini Nano) | Select Android devices **(verify)** | System model | Optional equivalent of Apple's provider on Android. Evaluate later; not required for the first release. |

GGUF is the only provider that gives identical behavior on both platforms and does not depend on vendor availability, so it is the baseline. Apple Foundation Models is an opportunistic add-on on supported iPhones.

### Architecture
- `commonMain` defines an interface along the lines of `LocalLlm`: `availability(): Availability`, `generate(prompt, options): Flow<Token>`, `cancel()`, plus a small capability description (context length, supported tasks).
- Providers implement it behind `expect`/`actual` or injection, the same pattern as `Transport`:
  - **Apple Foundation Models:** a Swift implementation in the iOS shell, exposed to Kotlin through the shared interface (Swift implements the Kotlin interface, no bridging header).
  - **llama.cpp:** compiled for iOS and Android and wrapped for Kotlin (Kotlin/Native cinterop on iOS, NDK/JNI on Android), or via an existing KMP wrapper **(verify maintained options before adopting one)**.
- A capability check decides which providers are offered. Provider selection is a user setting; no automatic cloud or network fallback exists.
- Inference runs off the main thread with structured concurrency, cancellation, and a memory ceiling.

### Security and privacy requirements
- **Untrusted input.** Mesh and Nostr messages come from strangers and are untrusted. Treat them as hostile prompt input (prompt injection). The model gets no tools, cannot send messages, and its output is shown to the user as text only. Keep untrusted content clearly delimited from system instructions.
- **Data stays local.** No message content, prompts or outputs leave the device. No telemetry. Do not log prompts or outputs. Keep any debug logs local (see `AGENTS.md`).
- **Model files are a supply-chain risk.** GGUF files are large downloads from the internet.
  - Download only on explicit user action, over a user-chosen source.
  - Verify a pinned SHA-256 for any model the app lists. For user-imported files, warn that the file is unverified.
  - Parse GGUF defensively; malformed files must fail closed and must not crash the app or the mesh service.
  - Reproducible-build expectations (`docs/reproducible-builds.md`) apply to bundled native code such as llama.cpp: pin versions and checksums.
- **Model licenses.** Many models carry their own terms (use restrictions, attribution). Show license information in the model picker and do not bundle models in the repo.
- **Privacy of AI-derived data.** Summaries and embeddings of private messages are as sensitive as the messages. Do not persist them outside the existing encrypted storage, and wipe them with the emergency-wipe flow.

### Resource and mesh interaction
- **Memory.** Models are 1 to 4+ GB. Loading one on a low-RAM phone can get the app killed, including the BLE foreground service on Android and background relaying on iOS. Load on demand, unload promptly, and refuse to load when memory is low.
- **Battery and thermals.** Inference competes with BLE duty cycling. Do not run inference while the app is relaying in the background, and show an indicator while it runs.
- **Offline.** The feature set must work with no network, so the model must already be on the device. Downloading a model is the only online step and is optional.
- **Wear OS.** Out of scope.

### Phasing
1. Define the `LocalLlm` interface and availability model in `commonMain`, with a fake provider for tests.
2. Spike llama.cpp on one Android and one iOS device: load a small GGUF (under 2 GB), measure memory, tokens per second, battery and thermals, and interaction with BLE.
3. Spike Apple Foundation Models on a supported iPhone behind the same interface, in Swift.
4. Ship one opt-in feature (for example channel summary) behind a settings toggle.
5. Evaluate Android system AI as a third provider.

### Settings and defaults

Each former open question becomes a user setting with a conservative default. Defaults are starting proposals and should be revised from the spike measurements in Phasing steps 2 and 3. Settings live under a dedicated "On-device AI" screen, apply on both platforms, and fail closed: when a limit is not met the feature is unavailable, with a plain explanation.

| Setting | Default | Options | Covers |
|---|---|---|---|
| On-device AI | Off | Off / On | Master switch. Nothing is loaded, downloaded or run while Off. |
| Allowed features | None enabled | Per feature: channel summary, translation, draft assist, spam triage | Which tasks justify the cost. The user opts in to each one; each shows a one-line note on its memory and battery cost. |
| Provider | Automatic among installed local providers | Automatic / Apple Foundation Models / Local GGUF | Never includes a cloud option. |
| Minimum free RAM to load a model | Model size plus a 1.5 GB headroom, minimum 3 GB total device RAM | User can raise it, not lower it below a hard floor of 2 GB free | Minimum device RAM. Below the limit the model does not load and the feature shows as unavailable. |
| Maximum model size | 2 GB | 1 / 2 / 4 GB / Custom | Limits which GGUF files can be added and recommended. |
| Unload model after idle | 2 minutes | 30 s / 2 min / 10 min / Never | Memory pressure and interaction with BLE relaying. |
| Run only when charging or battery above | Battery above 30% | Always / Battery above 30% / Charging only | Battery cost. |
| Pause while relaying in background | On | On / Off | Protects the BLE foreground service and iOS background relaying. |
| Model storage location | App-private storage | App-private storage / User-selected folder | Model files are never written to shared media or the repository. |
| Exclude models from backups | On | On / Off | Model files are large and reproducible, so they are excluded from device and cloud backups by default. |
| Delete models on emergency wipe | On | On / Off | AI data is wiped with the rest of the app's data. |
| Summaries of direct messages | Off | Off / On, per conversation | Direct messages are the most sensitive content. Channel and public-message summaries are unaffected. |
| Persist AI output (summaries, drafts) | Off | Off / On | When Off, results are shown and discarded. When On, they are stored only in the existing encrypted storage and covered by emergency wipe. |
| Allow model download | Off | Off / On | Downloads only happen on explicit user action and verify a pinned SHA-256 where the model is listed. Importing a local file is always available. |

Notes:
- The settings are stored with the app's other preferences. Defaults live in one shared Kotlin definition (`commonMain`), with no per-platform divergence.
- The hard floors (2 GB free RAM, no cloud provider, no tool access) are not exposed as settings.
- Backup exclusion is implemented per platform (Android backup rules, iOS excluded-from-backup resource attribute **(verify)**), but the setting and its default are shared.
- Validate each default against the Phasing spikes. Record measured numbers (memory, tokens per second, battery, thermals) next to the default they justify.

### Remaining open questions
- Which spike measurements are enough to confirm or change each default?
- Whether the master switch should be hidden entirely on devices below the hard RAM floor.

## 10. Migration progress (Option A)

Windows is deferred. Packages keep their `com.bitchat.android.*` names so `:app` and `:wear` need no import changes during extraction.

| Piece | State |
|---|---|
| `:shared` module (android + iosArm64 + iosSimulatorArm64) | Done; Android host tests run, iOS Kotlin compiles |
| `Transport` interface | Done (`commonMain`); not yet implemented by BLE code |
| `protocol/` (`BinaryProtocol`, `MessagePadding`, `CompressionUtil`, `DecompressionResourcePool`) | In `commonMain`; `:app` and `:wear` consume it; existing golden-vector tests still pass |
| iOS actuals (zlib, permit gate, logging) | Written, compile as klib on Windows, **not run**: needs macOS and the golden-vector tests on an iOS target |
| `BitchatPacket` Parcelable | Dropped (nothing parceled it) |
| Crypto primitives via `cryptography-kotlin` 0.6.0 (Phase 1 spike) | 9 RFC/NIST vectors pass on the JDK 21 provider and with BouncyCastle first in the provider list (approximating Android below API 33). Needs `bcprov` for public-key derivation. Not run on a real Android API 26-32 device or on iOS |
| Noise XX handshake and transport cipher in `commonMain` (Phase 2) | Done. Byte-identical to Noise-Java for fixed keys (`NoiseXXInteropTest`), live interop both directions, golden transcript in `commonTest` for every target. `NoiseSession` still uses Noise-Java; not switched over yet |
| Channel crypto | Uses PBKDF2-HMAC-SHA256 (100,000 iterations), not Argon2id as the README and privacy policy say. Decision: keep PBKDF2 for wire compatibility; fix the documentation separately |
| `NoiseSession`, `NoiseSessionManager`, `NoisePeerIdentity`, `NoiseChannelEncryption` | In `commonMain`, running on the shared Noise XX handshake and `cryptography-kotlin` (PBKDF2, AES-GCM). Existing session-manager tests, a JCA compatibility test and PBKDF2 reference vectors pass. `PlatformLock`, `ConcurrentMap` and a coroutine sweeper replace JVM locks, `ConcurrentHashMap` and the executor. Android uses BouncyCastle through an explicit provider (no global JCA change). Noise-Java now lives in `app/src/test` as the interop reference only |
| `model/` (messages, announcements, file and fragment payloads, sync filters) | In `commonMain`; `Date` is `expect`/`actual` (java.util.Date on Android); `Parcelable` removed (nothing parceled these) |
| Ed25519 signing (`crypto/Ed25519`) | In `commonMain` on `cryptography-kotlin`. RFC 8032 vectors 1 and 2 pass, malformed keys and signatures return false. Same 32-byte seed encoding as before, so stored keys still load. `EncryptionService` and `NoiseEncryptionService` no longer import BouncyCastle |
| `NoiseEncryptionService`, `PeerFingerprintManager`, `IdentityKeyStore` | In `commonMain`. Key storage is the `IdentityKeyStore` interface (`SecureIdentityStateManager` implements it on Android; Keychain on iOS later). The favorites/npub index update is an injected hook. End-to-end tests with an in-memory store: handshake, messaging, replay rejection, identity persistence and panic rotation, wrong-claimed-peer-ID rejection |
| `mesh/` core: `MeshCore`, `MessageHandler`, `SecurityManager`, `PacketProcessor`, `PacketRelayManager`, `PeerManager`, `FragmentManager`, `StoreForwardManager`, `AuthenticatedPeerStateCoordinator` and helpers; `sync/` (`GossipSyncManager`, `GCSFilter`, `PacketIdUtil`); `services/meshgraph/`, `service/TransportBridgeService`, `util/AppConstants` | In `commonMain`. Device services are ports: `MeshEncryption`, `MeshCorePlatform` / `MessageHandlerPlatform` (file storage, live voice, favorites, app state, verification, peer-state store) and `MeshDebugHooks`; Android implementations live in `app/`. New common helpers: lock-guarded collections, `PlatformLock`, `ConcurrentMap`, `IoDispatcher`, `runBlockingCompat`. `RoutePlanner` now uses a FIFO queue (unit edge cost), so tie-breaking between equal-length routes may differ from the old priority queue |
| BLE transports (`BluetoothGatt*`, `BluetoothConnection*`, `BluetoothPacketBroadcaster`, `BluetoothMeshService`, `UnifiedMeshService`, `PowerManager`, permissions), Wi-Fi Aware, `crypto/EncryptionService`, `SecureIdentityStateManager` | Stay Android-only (`androidMain` later); iOS needs a CoreBluetooth `Transport` |
| `nostr/NostrCrypto` (secp256k1 keys, BIP-340 Schnorr, bitchat NIP-44 v2 via `secp256k1-kmp` and an `XChaCha20Poly1305` built on HChaCha20 + ChaCha20-Poly1305) | In `commonMain`, pinned by `NostrCryptoVectorTest` (vectors captured from the old BouncyCastle/Tink code, plus BIP-340 vector 0). iOS actuals compile but have not run |
| `nostr/` data layer: `NostrEvent`, `NostrFilter`, `NostrRequest`/`NostrResponse`, `NostrProtocol` (NIP-17), `NostrProofOfWork`, `NostrIdentity` | In `commonMain`; JSON is written by hand (`NostrJson`) to reproduce Gson escaping so event ids are unchanged, and parsed with kotlinx.serialization trees. Proof-of-work preference is the `NostrPowSettingsProvider` port. Pinned by `NostrWireVectorTest` |
| `nostr/NostrRelayManager` (connections, reconnect backoff, subscription restore and validation, queued publishes, de-duplication, live-location privacy teardown), `NostrEventDeduplicator`, `NostrPendingEventQueue`, `RelayReconnectPolicy` | In `commonMain`, behind ports: `RelayConnector` / `RelaySocket` (sockets), `LiveLocationGate` (location consent), `RelaySelector` (geohash relay choice) plus injected dispatchers and validation cadence. Android wires OkHttp (`OkHttpRelayConnector`, Tor-aware client unchanged), `LiveLocationPrivacyGate` and `RelayDirectory`. Pinned by `NostrRelayManagerTest` (fake sockets, virtual time) and `OkHttpRelayConnectorTest` (local WebSocket server). iOS needs a `RelayConnector` over the platform stack or Ktor Darwin; Ktor is not on Android, so okhttp stays at 5.4.0 |
| rest of `nostr/` (transport, handlers), `geohash/`, `services/` (AppStateStore, VerificationService, ContactDirectory, favorites persistence), `features/` | Not started; they use Context, OkHttp, Gson, Uri/Base64 and Android types. Nostr needs a secp256k1 and WebSocket library decision |
| `:shared-ui` Compose Multiplatform module | Scaffold only: Android + iOS targets, placeholder `OChatApp()` root, builds on both. No screens, no Android/iOS shell wiring yet |
| iOS shell, Swift CoreBluetooth transport | Not started |
