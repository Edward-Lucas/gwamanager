# GWA Manager

GWA Manager is a Gecko-based PWA launcher for Android. It brings the
app-like model of Progressive Web Apps (PWAs) to websites selected by the
user, using Mozilla GeckoView instead of Android WebView.

GWA Manager does not turn websites into native applications. It manages each
site as an independent WebApp with its own name, URL, launcher entry, and
recent-apps identity, while using shared Gecko browser storage. The result is
a focused "Gecko version of a PWA manager" rather than a general-purpose
browser.

## Why GWA Manager?

- Use Mozilla's Gecko engine for modern web compatibility.
- Keep frequently used websites separate and easy to launch.
- Give each WebApp an app-like identity without duplicating browser data.
- Extend WebApps with notifications, media controls, fullscreen, and
  WebExtensions.

## Features

- Register WebApps with custom names, URLs, and mobile or desktop User-Agent
  modes.
- Run WebApps in separate Android activities and recent-apps entries.
- Add WebApps to the launcher with icons obtained from the site or its PWA
  manifest.
- Share GeckoView browsing storage across WebApps.
- Support web notifications, media playback controls, and web fullscreen.
- Install WebExtensions from local `.xpi` files or Mozilla Add-ons.
- Include a built-in WebExtension for common WebApp behavior.

## How it works

1. `ManagerActivity` stores and manages the WebApp definitions.
2. `WebAppActivity` opens a selected definition in its own Android task.
3. A shared Gecko runtime provides browser services while each WebApp keeps
   its own session and User-Agent configuration.
4. Launcher shortcuts, notifications, media actions, and PWA metadata are
   routed back to the correct WebApp.

## Project direction

The first release focuses on a reliable PWA-like experience built on GeckoView.
Future work is centered on lifecycle stability, stronger PWA metadata support,
better WebApp isolation and recovery, and a predictable release process. The
project intentionally avoids advertising and analytics SDKs.

## Requirements

- Android 12 or later (API 31+).
- Internet access for websites and WebApp metadata.
- Several hundred megabytes of free storage for the GeckoView runtime and
  WebApp data. The release APK is large because it bundles native Gecko
  runtime components.

## Download

The current GitHub release includes the signed APK:

[Download `gwa_manager.apk`](https://github.com/Edward-Lucas/gwamanager/releases/download/v1.0.1/gwa_manager.apk)

The application ID is `io.github.edwardlucas.gwamanager`.

## Building

The project uses Gradle and the Android command-line toolchain. Use Java 17
and run these commands from the project root:

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
gradlew.bat :app:assembleRelease --no-daemon --console=plain
```

The release output is written to
`app/build/outputs/apk/release/app-release.apk`. The GitHub release asset is
published as `gwa_manager.apk`.

Release builds use the local signing properties file
`%USERPROFILE%\.gradle\gwa-manager-signing.properties` when present. Without
that file, Gradle produces an unsigned release APK. Keep the keystore and its
password outside the repository.

## License

GWA Manager source code and original project assets are available under the
Mozilla Public License 2.0. See [`LICENSE`](LICENSE) for the full text.

Third-party dependency notices are listed in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
