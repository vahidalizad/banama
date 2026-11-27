# Banama

Open Banama from your TV’s Apps screen. Use **Search** for catalog and saved-list browsing. Open the **Profile** circle to view your 30nama subscription, connect, or sign out. Continue Watching and the movie and series shelves bring you to details and playback.

Starting with 1.1.16, the standard signed build checks GitHub Releases after Home loads. When a newer build is available, select **Update** to the left of **Search**. Banama sends the APK directly to Android’s installer. Follow any installation or “Allow from this source” prompt on the TV. Your sign-in and watch progress stay in place.

During playback, **Up** hides the controls. With the controls hidden, **Down** brings them back without pausing, **Left** and **Right** seek 10 seconds, and **OK** pauses or resumes. If a chapter shortcut has focus, **OK** activates it. For series, use **Episodes** to choose an episode.

For native 30nama access, set `thirtyNama.clientKey` in the ignored `local.properties` next to this wrapper. If 30nama supplies a different endpoint, also set `thirtyNama.api`. Use a client identifier intended for distribution in an APK. The app requests a pairing link and displays a QR code for your phone. [30nama API research](docs/30nama-api.md)

Banama keeps the original package `tv.scenichome.launcher`. Install the same-signer update over the old app to keep your sign-in, progress, and subtitle preferences. A debug APK uses the debug signer and the shareable release uses the distribution signer. If your TV used the old app as Home, install BananaLauncher and select it as Home before updating to Banama.

See the [workspace guide](../README.md) for builds, emulator testing, and TV installs.

## Install on another TV

Share **Banama.apk** from the Banama release assets. Starting with 1.1.18, this single APK supports Android 7.0 and later on Android TV, Google TV, and Android-based boxes with a D-pad remote. It includes 32-bit and 64-bit ARM and x86 support. Banama opens from the device’s existing launcher. Samsung TVs running Tizen, LG TVs running webOS, and Roku TVs need an external Android TV device to run it.

Transfer the APK to the TV and open it with a file manager. If Android asks, allow that file manager to install apps. Android 7 calls this setting **Unknown sources**. To preserve an existing installation and its data, install the update over it.

If the TV just says “App not installed,” an ADB connection to that TV can show the reason. Run `adb install -r Banama.apk`. `INSTALL_FAILED_OLDER_SDK` means the TV’s Android version is below the APK minimum. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` usually means the installed copy uses a different signing key, such as a debug build. Uninstalling that copy clears Banama’s local sign-in and preferences. `INSTALL_FAILED_VERSION_DOWNGRADE` means a newer build is already installed.

## Playback builds

Build the workspace Docker image, then run either command from the workspace root.

```sh
./dev Banama ./release-apk
./dev Banama ./release-apk --bundled-hevc
```

Both signed APKs in `Banama/artifacts/` use the same package and distribution signer. Both try Android’s hardware decoders first. A compatible Dolby Vision profile 7 or 8 release can retry its HDR10-compatible or other HEVC base layer on the device decoder. The bundled build can also use FFmpeg for an unsupported video or audio track. Software 4K playback speed and HDR output depend on the TV.

The optional `Banama-bundled-hevc.apk` requires Android 8.0 or later because of its native decoder. Use the standard `Banama.apk` for general distribution.

The bundled build uses Media3 1.11.1, NextLib 1.11.1-0.19.0 (GPLv3), FFmpeg 9.1 (LGPL 2.1 or later) and dav1d 1.5.5 (BSD). The decoder Dockerfile pins source checksums and builds the JNI library for each supported Android ABI. Media3 handles file and network input. The first bundled build compiles the native sources, and Docker caches subsequent builds. The generated files live in `Banama/.gradle/decoder/`. Debug builds accept `-PbundledHevc=true` through `./dev Banama ./gradlew`. To inspect the active video decoder on the emulator, run `./tv-emulator adb logcat -s BanamaDecoder` with a debug build installed.

[Build recipe](decoder/Dockerfile) · [NextLib source](https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.19.0) · [FFmpeg source](https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz) · [dav1d source](https://download.videolan.org/pub/videolan/dav1d/1.5.5/)
