# High Bytes POS (Flutter)

The drivers' mobile POS of iWarehouse as an Android and iOS app. It does what the web app (`src/main/resources/static/m`) does, against the same API (`/api/v1`), offline first:

- sign in;
- download the trip;
- sell by scanning a label or tapping the vehicle's stock;
- price within the driver's limit;
- take cash and/or mobile money;
- show the receipt and share it (WhatsApp, SMS, any app; there is no printer);
- sync with backoff;
- report breakage with a photo and the position;
- declare the cash;
- English or French, light or dark like the phone.

The web app stays for phones that cannot install the app.

## Build

You need Flutter 3.44 or later (built with 3.47.7) and the Android SDK.

```bash
cd mobile
flutter pub get
python tool/texts.py                      # after changing any mobile.* text in messages*.properties
flutter test                              # the sums (the server's worked examples) and the screens
flutter test --tags screens --dart-define=SCREENS_DIR=<folder> test/screens_test.dart   # the user manual's pictures (PNG, light and dark)
flutter build apk --release --dart-define=SERVER_URL=https://pos.example.rw
```

The APK is written to `build/app/outputs/flutter-apk/app-release.apk`.

- **The Android NDK is needed.** Flutter installs `ndk;28.2.13676358` (748 MB) on the first build if it is missing. On a slow line, fetch `android-ndk-r28c-windows.zip` yourself and unpack it as `<sdk>/ndk/28.2.13676358`.
- `--target-platform android-arm,android-arm64` leaves out x86_64 (emulators only) and one engine download.
- `kotlin.incremental=false` (`android/gradle.properties`): the plugins' Kotlin sources are in the pub cache on C: and the project on H:, and Kotlin's incremental caches fail across drives.
- `path_provider_android` stays below 2.3 (`pubspec.yaml`): 2.3 builds C code through `jni`, which needs CMake as well.

| `--dart-define` | What it does |
|---|---|
| `SERVER_URL` | The address the sign-in screen starts with. The driver may change it, and the app remembers it. |
| `ALLOW_HTTP=true` | Accepts a plain `http://` address, for a test server on the office network. Never use it for the real server: the password and the token would cross the network unencrypted. |

To build a test APK for the dev server on this PC:

```bash
flutter build apk --release --dart-define=ALLOW_HTTP=true --dart-define=SERVER_URL=http://<the PC's address>:8080
```

The phone must be on the same network, and Windows' firewall must let port 8080 in.

**Signing.** The release build is signed with the debug key until a real key is set up.

- The Play Store needs an upload key. Make it once with `keytool` and keep it, with its passwords, outside the repository.
- Give Gradle its path through `android/key.properties`, which git ignores.

**iOS.** iOS builds need a Mac with Xcode: `flutter build ipa`. The bundle id is `rw.highbytes.pos`, the same as Android's application id.

## How it is built

| File | What it holds |
|---|---|
| `lib/money.dart` | The sums with integers, line for line as `app.js` and the server's `MobileSales` do them. Change all three together. `test/money_test.dart` holds the server's worked examples. |
| `lib/controller.dart` | The state and the rules, as `app.js`: the trip, the cart and its checks, the queue (oldest first, backoff up to 5 minutes), EBM signatures, breakage, the cash declaration, signing in and out. |
| `lib/store.dart` | SQLite in the app's own storage: the settings, the trip, the sales and the breakage reports. |
| `lib/api.dart` | The API calls, with the phone's token, `X-Device-Id` and the audit headers. |
| `lib/device.dart` | What the phone does: the network, the camera scanner, the photo, the position, sharing. |
| `lib/screens/` | One screen per view of the web app. |
| `test/screens_test.dart` | Draws every screen in both themes with the fake server's trip (`test/fake_server.dart`); with `SCREENS_DIR` it saves them for the user manual, using the PC's Segoe UI and Consolas. |
| `assets/texts/` | A copy of the server's `mobile.*` texts, made by `tool/texts.py`. It is used until the server's texts arrive. |
