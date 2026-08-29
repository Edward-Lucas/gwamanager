# GWA Manager

**GWA Manager** is an Android application for running frequently
used websites as independent WebApps with Mozilla GeckoView.

## Features

- Register multiple WebApps with their own names, URLs, and User-Agent modes.
- Keep WebApp browsing data in the shared GeckoView browser storage.
- Add WebApps to the launcher with their website icons.
- Show WebApps as separate entries in Android's recent-apps screen.
- Support web notifications, media playback controls, and web fullscreen.
- Install WebExtensions from local `.xpi` files or Mozilla Add-ons.
- Use a built-in WebExtension for common WebApp behavior.

The application does not include advertising or analytics SDKs. Websites opened
by the user can still make their own network requests.

## Requirements

- Android 12 or later
- A device with sufficient storage for the GeckoView runtime and WebApp data

## Building

The project uses Gradle and the Android command-line toolchain. From the
project root, run:

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

The application ID is `io.github.edwardlucas.gwamanager`.
Release builds use the local signing properties file
`%USERPROFILE%\.gradle\gwa-manager-signing.properties` when present; otherwise
the release APK is unsigned. Keep the keystore and its password outside the
repository.

## License

GWA Manager source code and original project assets are available under the
Mozilla Public License 2.0. See [`LICENSE`](LICENSE) for the full text.

Third-party dependency notices are listed in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
