# PrivacyGuard

An Android app that blocks ad and tracker domains on the whole phone, using a local VPN that filters DNS lookups on the device. No root, no account, no server: nothing leaves the phone.

<p>
  <img src="screenshots/home.png" width="200" alt="Home screen with protection on">
  <img src="screenshots/blocked.png" width="200" alt="Blocked requests list">
  <img src="screenshots/filters.png" width="200" alt="Filters screen">
</p>

Screenshots are from an Android 17 emulator after browsing cnn.com with protection on.

## Download

**[Download the latest APK](https://github.com/elwyndraker/test-guard/releases/latest)**: open this link on your Android phone, tap `PrivacyGuard-v….apk` under **Assets**, then open the downloaded file to install. See [Install the app without Android Studio](#install-the-app-without-android-studio) for details.

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Install the app without Android Studio](#install-the-app-without-android-studio)
- [Build from source](#build-from-source)
- [Run it on a phone or an emulator](#run-it-on-a-phone-or-an-emulator)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Code walkthrough](#code-walkthrough)
- [Known limits](#known-limits)
- [Publishing on Google Play](#publishing-on-google-play)
- [Blocklist sources](#blocklist-sources)
- [Credits](#credits)

## Features

| Feature | Where |
| --- | --- |
| Blocks about 1 million ad and tracker domains, merged from [17 public lists](#blocklist-sources), in every app and browser | Runs in the background as a local VPN |
| One switch to turn protection on and off, plus a "blocked today" counter | **Home** screen |
| Live list of blocked domains, newest first; repeats are grouped ("Blocked 4 times") | **Blocked** screen |
| Blocklist update on demand, automatically when older than 7 days, and right after an app update that changes the sources | **Filters** screen |
| Works offline on first launch (a copy of the anti-AD list ships inside the app; the full merged list downloads on first launch) | `app/src/main/assets/hosts.txt` |
| Follows Wi-Fi ↔ mobile data changes (reads the current network for every lookup) | `UpstreamDns.resolve()` |
| Explains the VPN before Android's permission popup (required by Google Play) | First time the switch is turned on |
| Diagnostics (lookups answered/failed, DNS route, network, last problem) and a **Copy details** button for bug reports | **Home** screen |
| Safety switch: turns itself off and explains why if lookups keep failing for 30 s on the same network | `PrivacyVpnService.giveUp()` |

Minimum Android version: 8.0 (API 26). Target: API 36.

## How it works

Before an app loads anything from the internet, it asks a DNS server for the address of a name such as `ads.example.com`. PrivacyGuard sits in the middle of those lookups:

```
 App asks for "ads.example.com"
          │
          ▼
 Android sends the lookup to the VPN's DNS server, 10.111.222.2
          │   (only this one address is routed into the tunnel;
          │    web pages, videos and downloads never enter the app)
          ▼
 PrivacyVpnService reads the packet from the tunnel
          │
   ┌──────┴────────────────────────┐
   │ on the blocklist?             │
   ├── yes ────────────────────────┼── no ──────────────────────────────┐
   ▼                               │                                    ▼
 Reply "does not exist" (NXDOMAIN)  │        Forward the lookup unchanged to the
 → the ad or tracker never loads    │        real network's DNS (Android's resolver;
 → shown on the Blocked screen      │        public DNS as a last resort) and pass the
                                    │        real answer back
```

Key design choices:

- **DNS-only tunnel.** The VPN routes only the fake DNS address (`10.111.222.2/32`), so normal traffic goes straight to the internet. This keeps the app fast and light on battery, and the packet code only has to handle small IPv4/UDP DNS packets.
- **No DNS library.** Reading a domain name and building an NXDOMAIN reply takes about 40 lines (`DnsUtils.kt`), which keeps the code easy to read and explain.
- **The app excludes itself from its own VPN** (`addDisallowedApplication`), so forwarded lookups and blocklist downloads go directly to the real network.
- **Parent-domain matching.** If `doubleclick.net` is listed, `stats.g.doubleclick.net` is blocked too.

## Install the app without Android Studio

You don't need USB debugging or Developer options to try the app:

1. Download the APK from the [latest release](https://github.com/elwyndraker/test-guard/releases/latest), or build it yourself (see below).
2. Copy the `.apk` file to the phone (USB file transfer, Google Drive, Telegram "Saved messages", email, …).
3. Open the file on the phone with its file manager and tap **Install**. Android asks you to allow "Install unknown apps" for that file manager once; allow it.
4. Open PrivacyGuard and turn the switch on.

On some phones (Xiaomi, Oppo, Vivo, Realme, Huawei), the system may also show a security scan or ask you to confirm again. That's normal for apps installed from outside the store. These phones also tend to stop background apps aggressively. If protection turns off by itself, open the phone's battery settings for PrivacyGuard and choose "No restrictions" / "Allow background activity".

## Build from source

These steps are for Linux (Ubuntu, Fedora and similar). Windows works too: use `gradlew.bat` instead of `./gradlew` and Windows paths such as `C:/Users/<you>/...`.

Requirements:

- [Android Studio](https://developer.android.com/studio) 2026.2 or newer (it includes the right JDK and Android SDK).
- About 3 GB free disk space for Gradle and the SDK, plus 6–12 GB for an emulator.
- `git`.

### Install Android Studio on Linux

Either of these:

```bash
# Ubuntu and other distros with snap
sudo snap install android-studio --classic

# Or download the .tar.gz from developer.android.com/studio, then:
tar -xzf android-studio-*-linux.tar.gz -C ~/
~/android-studio/bin/studio.sh
```

On first start, the setup wizard downloads the Android SDK to `~/Android/Sdk`. Accept the defaults.

Then get the code:

```bash
git clone https://github.com/elwyndraker/test-guard.git
cd test-guard
```

### With Android Studio

1. **File → Open** and choose the `test-guard` folder. Wait for the Gradle sync to finish.
2. Press **Run ▶** to install on a connected phone or an emulator.
3. To get an APK file: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The file appears at `app/build/outputs/apk/debug/app-debug.apk`.

### From the command line

```bash
./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # runs the unit tests
./gradlew lintDebug              # runs Android Lint
```

If Gradle can't find the Android SDK, create `local.properties` in the project folder (Android Studio does this for you when you open the project):

```properties
sdk.dir=/home/<you>/Android/Sdk
```

If `./gradlew` says "Permission denied", run `chmod +x gradlew` once.

### Release builds and signing

`./gradlew assembleRelease` (APK) and `./gradlew bundleRelease` (AAB for Google Play) sign the app when a `keystore.properties` file exists in the project folder:

```properties
storeFile=/home/<you>/PrivacyGuard-signing/privacyguard-upload.jks
storePassword=...
keyAlias=privacyguard
keyPassword=...
```

The key file and `keystore.properties` are excluded by `.gitignore` and must never be committed. Without `keystore.properties`, release builds are produced unsigned. Every published release must be signed with the same key, otherwise phones refuse to install it over the previous version.

To create a new key (keep it outside the project folder):

```bash
mkdir -p ~/PrivacyGuard-signing && cd ~/PrivacyGuard-signing
keytool -genkeypair -v -keystore privacyguard-upload.jks -alias privacyguard \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` comes with Java; if the command isn't found, use the copy inside Android Studio (`~/android-studio/jbr/bin/keytool`, or `/snap/android-studio/current/jbr/bin/keytool` for the snap).

**Back up the `.jks` file and its passwords in at least two places** (for example a password manager and a USB stick). If the key is lost, phones can't update the app from a newer APK, and users have to uninstall and reinstall.

Versions used: Android Gradle Plugin 9.4.1, Gradle 9.7.1, Kotlin 2.4.21, Compose BOM 2026.09.00, compileSdk 37. All are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Run it on a phone or an emulator

### Emulator

1. On Linux, the emulator needs KVM. Check with `ls -l /dev/kvm`; if you get "permission denied" when starting the emulator, run `sudo usermod -aG kvm $USER` and log out and back in.
2. In Android Studio, open **Device Manager** (phone icon on the right) → **Create device** → pick a phone → pick a system image (API 26 or newer) → **Finish**.
3. Select the emulator in the toolbar and press **Run ▶**.
4. In the app, turn the switch on and accept Android's "Connection request" popup.

The emulator needs about 6–12 GB free disk space. To keep it on another disk, set the environment variable `ANDROID_AVD_HOME` (for example `export ANDROID_AVD_HOME=/mnt/data/avd` in `~/.profile`) before creating the device.

### Real phone over USB

1. On the phone: **Settings → About phone**, tap **Build number** 7 times, then turn on **Developer options → USB debugging**.
2. Connect the cable and accept the "Allow USB debugging?" popup on the phone.
3. Check with `~/Android/Sdk/platform-tools/adb devices`. If the phone shows as "no permissions" on Ubuntu/Debian, run `sudo apt install android-sdk-platform-tools-common` (it adds the USB rules), then unplug and replug.
4. Select the phone in Android Studio's toolbar and press **Run ▶**.

## Testing

### Unit tests (no phone needed)

```bash
./gradlew testDebugUnitTest
```

| Test class | What it checks |
| --- | --- |
| `PacketUtilsTest` | Reading the domain from IPv4/UDP DNS packets, ignoring non-DNS traffic, NXDOMAIN replies keep the id and question, answer packets swap addresses and ports with a valid IPv4 checksum |
| `BlocklistTest` | Parsing every list format (hosts files with `0.0.0.0`/`127.0.0.1`/`::`, plain domains, `*.` wildcards, adblock `||domain^` rules; comments, localhost and other adblock rules skipped) and parent-domain matching |
| `DomainSetTest` | The packed domain set: no duplicates, growth to 50,000 entries, case-insensitive suffix lookups, saving one domain per line |
| `BlockedLogTest` | Repeated blocks within a minute share one row; later ones get a new row |
| `FirstSuccessTest` | The DNS route race: fast route wins, a failed route starts the next at once, a slow route is overtaken, all-fail and timeout return quickly |

### Manual test on a device or emulator

With protection on, run these from a computer with `adb` (or just open the sites in Chrome):

```bash
adb shell ping -c 1 doubleclick.net   # expected: "unknown host"  (blocked)
adb shell ping -c 1 google.com        # expected: "PING google.com (…)"  (allowed)
```

Results from the Android 17 emulator run on Oct 10, 2026:

| Check | Result |
| --- | --- |
| Switch on → explanation dialog → notification permission → Android VPN popup | Pass |
| Key icon and "PrivacyGuard is protecting you" notification appear | Pass |
| `doubleclick.net`, `googleadservices.com`, `stats.g.doubleclick.net` blocked | Pass |
| `google.com`, `wikipedia.org` resolve normally | Pass |
| theguardian.com and cnn.com load; their trackers and ad networks appear on the Blocked screen | Pass |
| Update now downloads a fresh list; the date changes | Pass |
| Wi-Fi off → mobile data → Wi-Fi on: blocking and browsing keep working | Pass |
| Switch off → tunnel and notification removed, `doubleclick.net` resolves again | Pass |
| No crashes in the crash log | Pass |

**Real-network DNS test (v1.1).** v1.0 failed on a real phone: no website opened. The cause was that v1.0 forwarded lookups only to the *first* DNS server of the network and waited 5 s before falling back. Real phones often list an unreachable server first (commonly IPv6). Reproduced on the emulator with `-dns-server 10.255.255.1,8.8.8.8`, which makes the first server dead:

| Version | Time per lookup with the VPN on | Websites |
| --- | --- | --- |
| v1.0 | 8,088 ms | Too slow; apps give up |
| v1.1 | ~200 ms (Android resolver for the real network) | Load normally |
| No VPN (reference) | ~420 ms | Load normally |

With *all* of the network's DNS servers dead, the app falls back to public DNS and keeps answering.

**Network switching (v1.2).** A tester's Xiaomi phone stopped resolving after switching mobile data → Wi-Fi in v1.1 (could not be reproduced on the emulator). v1.2 reads the current network for every lookup, races several routes, and restarts the safety-switch clock on every network change. Emulator results for v1.2:

| Test | Result |
| --- | --- |
| Wi-Fi → mobile data → Wi-Fi with a lookup every 3–5 s | 39 answered, 0 failed |
| 4 rapid Wi-Fi off/on flips | 46 answered, 0 failed; protection stayed on |
| Dead first DNS server | ~0.6 s per lookup (v1.0: 8 s), 0 failed |

Still to test on real phones: 30 minutes with the screen off, another VPN app taking over, and a full day of normal use. See the testing checklist in the project plan.

## Project structure

```
app/src/main/
├── AndroidManifest.xml          permissions + the VPN service declaration
├── assets/hosts.txt             built-in copy of the anti-AD list (offline fallback)
├── java/com/diu/privacyguard/
│   ├── MainActivity.kt          entry point, sets up Compose
│   ├── BuildConfigInfo.kt       reads the app version for the About dialog
│   ├── data/
│   │   ├── Blocklist.kt         the list sources; loads, parses, merges, updates and checks domains
│   │   ├── DomainSet.kt         memory-saving set of ~1 million domains (packed bytes + hash table)
│   │   ├── BlockedLog.kt        in-memory list of blocked domains + today's count
│   │   └── Settings.kt          DataStore: last update time, explanation accepted
│   ├── vpn/
│   │   ├── PrivacyVpnService.kt the VPN: tunnel, packet loop, forwarding, notification
│   │   ├── VpnController.kt     start/stop + the running state the UI observes
│   │   ├── PacketUtils.kt       parse IPv4/UDP packets, build answer packets
│   │   └── DnsUtils.kt          read DNS questions, build NXDOMAIN replies
│   └── ui/
│       ├── PrivacyGuardApp.kt   bottom navigation + weekly auto-update
│       ├── HomeScreen.kt        switch, status, counter, permissions flow
│       ├── BlockedScreen.kt     blocked domains list
│       ├── FiltersScreen.kt     list size, Update now, About, links
│       └── theme/Theme.kt       colors
└── res/                         strings, icons, theme
app/src/test/                    unit tests
docs/index.html                  privacy policy page (for GitHub Pages)
screenshots/                     images used in this README
```

## Code walkthrough

A good reading order for new developers:

1. **`VpnController.kt`**: the small API the UI uses: `start`, `stop`, and `isRunning`. Only the service changes `isRunning`, so the switch always shows the real state.
2. **`PrivacyVpnService.startTunnel()`**: loads the blocklist, reads the real DNS server, then builds the tunnel with `VpnService.Builder`. Note `addRoute("10.111.222.2", 32)`: that single route is what makes the tunnel DNS-only.
3. **`PrivacyVpnService.runLoop()`**: the tunnel file descriptor is non-blocking, so the loop waits with `Os.poll` and a 1-second timeout instead of spinning. The timeout also lets it notice a stop request.
4. **`handlePacket()`**: `PacketUtils.parseDnsQuery` → `Blocklist.isBlocked` → either `DnsUtils.nxdomain` (blocked) or `UpstreamDns.resolve()` in its own coroutine (allowed) → `PacketUtils.buildResponse` → write back to the tunnel.
5. **`UpstreamDns.kt`** + **`FirstSuccess.kt`**: how allowed lookups are answered, as a staggered race. First Android's own resolver for the real network (`DnsResolver.rawQuery`, Android 10+; it already knows which servers work and handles IPv6 and Private DNS). After 0.6 s, the network's DNS servers directly, over that network. After 1.2 s, the public fallbacks 1.1.1.1 and 8.8.8.8. If one route fails, the next starts at once; the first answer wins. The network is read fresh for every lookup. Answers must be fast: apps give up on a lookup within a few seconds.
6. **`PacketUtils.buildResponse()`**: builds a fresh IPv4 + UDP packet with the addresses and ports swapped. The UDP checksum is 0, which IPv4 allows.
7. **`Blocklist.kt`** + **`DomainSet.kt`**: a hash lookup that walks up parent domains. An update downloads every source in `Blocklist.SOURCES` and merges them, skipping sources that fail (the Filters screen says how many). The merged list is saved as one domain per line and only replaces the current list if it has more than 10,000 domains, so a broken download never wipes the list. About a million domains would need well over 100 MB as a `HashSet<String>`, so `DomainSet` packs them into one byte array plus an int hash table (~35 MB); the manifest also sets `largeHeap` because an update builds the new list while the old one is still in use.

Things that look odd but are deliberate:

- `android:foregroundServiceType="systemExempted"` with `tools:ignore="ForegroundServicePermission"`: VPN apps may use this type without the exact-alarm permission Lint asks for. Adding that permission would trigger extra Google Play review. Verified working on Android 17.
- `setMetered(false)`: Android otherwise treats every VPN as metered, which makes other apps delay downloads.
- A `generation` counter in the service: it discards a slow start that finishes after the user already turned protection off.

## Known limits

- **Private DNS:** if Settings → Network → Private DNS is set to a provider hostname (for example `dns.google`), Android sends lookups around the VPN and nothing is blocked. "Off" and "Automatic" both work. The Home screen shows this tip.
- **Ads served from the content's own domain** (YouTube, Facebook, Instagram) can't be blocked by DNS filtering.
- **IPv4 DNS only.** Android sends lookups to the VPN's IPv4 DNS address, so this covers normal use. DNS over TCP (rare, used for very large replies) isn't handled.
- **No allow-list.** If a site breaks because one of its domains is on the list, turn protection off for that site.
- **The Blocked list lives in memory** and empties when the app is fully closed.

## Publishing on Google Play

Before the first upload:

1. The privacy policy is live at <https://elwyndraker.github.io/test-guard/> (GitHub Pages, from `docs/index.html`); the app links to it from the Filters screen.
2. Build the bundle with `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`, signed with the key described in [Release builds and signing](#release-builds-and-signing). Use that key as the Play upload key and keep it backed up.
3. Raise `versionCode` in `app/build.gradle.kts` before every upload.

Play Console answers (data safety, VpnService declaration, store listing) are in the project plan document.

### In-app updates

`AppUpdateBanner.kt` uses Google Play's [in-app updates](https://developer.android.com/guide/playcore/in-app-updates) (flexible flow). When Play has a newer version, a card at the top of every screen offers **Update**; Play shows its own update screen and downloads in the background, then the card offers **Restart** to install. The app never downloads or installs APKs itself, which Play policy forbids.

The card only appears for copies installed from Google Play. Until the app is on Play, and for APKs installed from GitHub releases, it simply never shows. To try it before the public release:

1. Upload a build (for example version 1.3, code 4) to the **Internal testing** track and add yourself as a tester.
2. Install it on a phone from the tester link in the Play Store.
3. Upload a build with a higher `versionCode` to the same track.
4. Open the Play Store page once (so Play notices the new version), then open PrivacyGuard: the update card appears.

Google's [internal app sharing](https://support.google.com/googleplay/android-developer/answer/9844679) also works for this and doesn't need a review.

## Blocklist sources

The app downloads and merges these lists (see `Blocklist.SOURCES`). Each list keeps its own license; the app only downloads them on the phone and doesn't redistribute them, except the anti-AD copy in `assets/` (MIT License, Copyright (c) 2017-2019 gently).

| List | Format |
| --- | --- |
| [jdlingyu/ad-wars](https://github.com/jdlingyu/ad-wars) | hosts |
| [r-a-y/mobile-hosts](https://github.com/r-a-y/mobile-hosts) (AdguardMobileSpyware) | hosts |
| [FadeMind/hosts.extras](https://github.com/FadeMind/hosts.extras) (add.2o7Net) | hosts |
| [privacy-protection-tools/anti-AD](https://github.com/privacy-protection-tools/anti-AD) | domains |
| [AdroitAdorKhan/antipopads-re](https://github.com/AdroitAdorKhan/antipopads-re) | domains |
| [hagezi/dns-blocklists](https://github.com/hagezi/dns-blocklists) (Pro, wildcard) | wildcard |
| [EasyPrivacy](https://easylist.to/) | adblock (domain rules only) |
| [hBlock](https://hblock.molinero.dev/) | domains |
| [rentianyu/Ad-set-hosts](https://github.com/rentianyu/Ad-set-hosts) | hosts |
| [TG-Twilight/AWAvenue-Ads-Rule](https://github.com/TG-Twilight/AWAvenue-Ads-Rule) | adblock (domain rules only) |
| [jerryn70/GoodbyeAds](https://github.com/jerryn70/GoodbyeAds) | hosts |
| [lightswitch05/hosts](https://www.github.developerdan.com/hosts/) (ads and tracking extended) | hosts |
| [NoTrack trackers](https://gitlab.com/quidsup/notrack-blocklists) | domains |
| [notracking/hosts-blocklists](https://github.com/notracking/hosts-blocklists) | hosts |
| [FLOSSBOXIN hosts](https://hosts.flossboxin.org.in/) | hosts |
| [bongochong/CombinedPrivacyBlockLists](https://github.com/bongochong/CombinedPrivacyBlockLists) | wildcard |
| [IndianList](https://easylist.to/) | adblock (domain rules only) |

Adblock lists also contain rules for paths, element hiding and exceptions (`@@`); DNS filtering can't apply those, so only plain `||domain^` rules are used. Some of these lists are aggressive: if an app or site breaks, check the **Blocked** screen to see which domain was blocked.

## Credits

- Blocklists: see [Blocklist sources](#blocklist-sources).
- Design ideas from [DNS66](https://github.com/julian-klode/dns66) (GPL-3.0; no code copied).
