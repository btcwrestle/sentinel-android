# Sentinel — Dojo Bay Edition

A watch-only Bitcoin wallet for Android: track xpubs, addresses and cold-storage
wallets, derive receive addresses on the go, and broadcast pre-signed
transactions — entirely over Tor, paired exclusively with a Bitcoin Dojo you
control (or one from the community directory; visit https://DojoBay.org for more info).

This is a community continuation, not a new project. Credit where it belongs:

- **Samourai Wallet** — original Sentinel (code preserved in the [Samourai-Wallet GitHub org](https://github.com/Samourai-Wallet); their web domains were seized in 2024 and later hosted phishing — treat any samouraiwallet.* site as hostile)
- [btcwrestle/sentinel-android](https://github.com/btcwrestle/sentinel-android) —
  the Dojo Bay integration that gave the project its post-2024 purpose, and the
  Dojo-or-nothing direction after upstream infrastructure was seized
- fixes adopted from wanderingking072's fork (authored by @MightyMercurian)

## Lineage

Upstream Samourai Sentinel went dark in 2024. btcwrestle revived it as the Dojo
Bay edition; this fork began at btcwrestle:master commit 36a7e27 ("Dojo
onboarding, BIP84 default, UTXO labels, staging CI, and Network screen fixes",
2026-09-06) and continues the maintenance arc here. Work flows back upstream
via pull requests — our BIP-329 work was offered upstream (btcwrestle#5) and
closed unmerged while upstream has been quiet since September 2026, so this
repository is the live continuation as of October 2026. The histories share a
common root; upstream holds nothing we lack.

## What this fork has done since the fork point

| Area | Status |
|---|---|
| kmp-tor 2.x migration (in-process TorRuntime) | done (#2, #10) |
| Tor watchdog: bounded auto-recovery from boot=0 wedges | done (#11, #14) |
| Boltzmann entropy/linkability per tx | done (#7) |
| Whirlpool autolabels: TX0/mix/remix detection | done (#40, #43) |
| Bad Bank deduction + lineage propagation | done (phase 1 via #53; umbrella #46, phase 2 = #54) |
| BIP-329 label management (import/export/edit/render) | done (#24; upstream offer btcwrestle#5) |
| Dojo-or-nothing: dead Samourai API key + build plumbing removed | done (#22, #23) |
| QR pairing payload guard, string extraction, directory name+flag | done (#25, #21, #17) |
| Dependency bumps one-family-per-PR (okhttp, coroutines, hummingbird) | done (#28, #29, #30) |
| Build deprecation sweep + CI modernization (checkout v5, setup-java v5, ubuntu-26) | done (#33, #59) |
| minSdk 21 -> 23 (Android 5.0/5.1 dropped) | done (#60) |
| minSdk 23 -> 24 (Android 6.0 dropped) | done (#236) |
| Tag-triggered release pipeline (APK + SHA-256) | done (#39, #63, #65, #67) |
| Ordered catch arms for escape-class errors (ApiNotConfigured et al.) | done, partial (#103, #125, #131) |
| Live tx-facts row retired; Confirmation renamed Confirmations | done (#119, #127) |
| Connection light GREEN only at full bootstrap; staged climb UX | done (#99, #134) |
| Dojo boot gating: sync/websocket wait on boot=100 + SOCKS listener | done (#135 arc: #136-#139) |
| Entropy kill switch (nothing displays pending the re-enable design) | done (#106) |

## Currently open (backlog as of 2026-10-11, every line verified against the tracker)

- Bad Bank Tainted — mixed-input propagation, phase 2 of #46 — #54
- Bad Bank chips in tx details and tx list — #55
- Per-tx entropy re-enable, tiered: local <=5x5, am-i.exposed deep link for
  wide rounds, boltzmann-rs port for 6x6-9x9 — #107
- CancellationException laundering at BroadcastTx and ImportSegWitService — #129
- Mid-sync tx-table flicker / tx-sheet kick-out on fresh re-pair — #124
- "Sync interrupted." flash on routine refresh — #132
- Tor connection streamline (dormancy wake, post-bootstrap onion latency) — #135
- Dead-code sweep — sweep/ package, WebUtil stubs, send/PushTx trustedNode,
  LBC/BFX constants — #79
- Mask sensitive data — dojo onion hostnames in logcat traces, XPUB/pairing
  payload handling in shared storage — #96
- 16 KB page-size for native libs (camera libs compliant; Tor leg pending
  kmp-tor-resource#192; benign while sideloaded) — #20
- Remaining on-device smoke debt: Dojo pairing/sync, websocket reconnect,
  animated-UR scan — #36
- Bip329Parser network classification unification with NetworkClassifier — #64
- BBQR (BQR:) label import alongside animated UR — #70
- Animated-UR label scan UX (minutes-long scans on small exports) — #71
- Fee defaults 1-4 s/vb unusable on congested mempool — #77
- Analytics provider chooser (local-first, separate PR) — #8
- OXT seen-backend successor when an API lands — #9
- Vendored bitcoinj suite fails locally on Kali JDK (locale data; CI green) — #75
- Fork version line: stay v5.1.3-dojobay.N or major-bump to v6.0.0 — #66

## Label engine

Labels are a first-class subsystem:

- **Whirlpool autolabels** classify TX0/mix/remix transactions from account
  identity alone — no explorer lookups, never implying anonymity gain.
- **Bad Bank** pins the toxic change output ("badbank") of Whirlpool Tx0s and
  propagates the label to descendant spends of wallet-derived UTXOs; the
  mixed-input (Tainted) class is the queued next phase.
- **BIP-329** is the storage format: import/export/edit/render with total
  parser semantics (absent label = do not alter, empty = explicit clear,
  cross-network refs rejected, origin and createdAt preserved), verified
  round-trip against Sparrow in both directions. Labels are privacy-sensitive
  metadata: stored locally only, network-scoped, hidden under street mode.

Manual and imported labels always win over automatic ones.

## Tor and Dojo behavior

- Embedded Tor runs in-process (kmp-tor 2.x TorRuntime) with a watchdog that
  recovers boot=0 wedges: stop first, wipe tor state, rebuild, restart —
  bounded, with a retryable error when the recovery budget is exhausted.
- The connection light is GREEN only at boot=100 with a live SOCKS listener;
  the post-ON climb renders as legible stage summaries on the Network screen
  and during Dojo import.
- The first sync round and websocket connect are gated on full bootstrap;
  directory-fetch probe failures explain themselves in plain language with
  one auto-retry.
- Directory and Dojo traffic goes only through the app's SOCKS proxy to onion
  services; the Dojo Bay directory itself is an onion service. No clearnet
  Samourai endpoints are contacted. Sentinel never holds your private keys.

## Releases

Tagging a `v*` tag runs the release workflow. The tag job builds the staging
release variant (`assembleStagingRelease`), signs it with the CI release
keystore, and publishes that APK plus a SHA256SUMS.txt to GitHub Releases.
The release variant is minified with R8. It installs as **Sentinel Beta**
(applicationId `com.samourai.sentinel.staging`) and coexists with a production
Sentinel install.

The staging debug APK is still built for internal testing and uploaded as a
short-lived Actions artifact. It is not attached to Releases.

First pipeline release: v5.1.3-dojobay.1 (staged as a prerelease; the app reports
5.1.2 internally — the release-line decision is tracked in #66). Packages
signed with a different certificate cannot update an existing install; uninstall
that package before installing one signed by the release key.

### Release signing secrets

Tag builds read these Actions repository secrets
(Settings → Secrets and variables → Actions). Do not commit the keystore.

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Base64 of the release keystore (PKCS12 or JKS), one line |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore store password |
| `ANDROID_RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_RELEASE_KEY_PASSWORD` | Key password (same as the store password for PKCS12) |

Encode the keystore with `base64 < release.keystore | tr -d '\n'`. The workflow
decodes it to a temp file and points Gradle at it with `CI_RELEASE_KEYSTORE`.
Debug artifacts still use `ANDROID_DEBUG_KEYSTORE_BASE64`.

A local release build does not need those secrets. With `CI_RELEASE_KEYSTORE`,
`ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and
`ANDROID_RELEASE_KEY_PASSWORD` unset, Gradle writes an unsigned APK.

## For testers

Install the APK from the
[Releases](https://github.com/tetragrammalol/sentinel-android-dojobay/releases)
page. That file is the signed staging release build.

Build the same variant locally:

    ./gradlew :app:assembleStagingRelease

The debug variant is for development. CI keeps it as an artifact on the
[build-staging-apk](https://github.com/tetragrammalol/sentinel-android-dojobay/actions)
workflow, not on the Releases page:

    ./gradlew :app:assembleStagingDebug

Requires a JDK 25+ compatible toolchain (Room runs via KSP; kapt is gone).
minSdk is 24 (Android 7.0+).

## Disclaimer

Experimental community software, provided as-is, with no affiliation to
Samourai Technologies or Dojo Bay operators. Read the code; that is the point
of a wallet like this.
