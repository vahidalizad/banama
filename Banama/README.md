# Banama

Open Banama from your TV’s Apps screen. Use **Search** for catalog and saved-list browsing. Open the **Profile** circle to view your 30nama subscription, connect, or sign out. Continue Watching and the movie and series shelves bring you to details and playback.

For native 30nama access, set `thirtyNama.clientKey` in the ignored `local.properties` next to this wrapper. If 30nama supplies a different endpoint, also set `thirtyNama.api`. Use a client identifier intended for distribution in an APK. The app requests a pairing link and displays a QR code for your phone. [30nama API research](docs/30nama-api.md)

Banama keeps the original package `tv.scenichome.launcher`. Install the same-signer update over the old app to keep your sign-in, progress, and subtitle preferences. A debug APK uses the debug signer and the shareable release uses the distribution signer. If your TV used the old app as Home, install BananaLauncher and select it as Home before updating to Banama.

See the [workspace guide](../README.md) for builds, emulator testing, and TV installs.

## Playback builds

Build the workspace Docker image, then run either command from the workspace root.

```sh
./dev Banama ./release-apk
./dev Banama ./release-apk --bundled-hevc
```

Both signed APKs in `Banama/artifacts/` use the same package and distribution signer. Both try Android’s hardware decoders first. A compatible Dolby Vision profile 7 or 8 release can retry its HDR10-compatible or other HEVC base layer on the device decoder. The bundled build can also use FFmpeg for an unsupported video or audio track. Software 4K playback speed and HDR output depend on the TV.

The bundled build uses Media3 1.11.1, NextLib 1.11.1-0.19.0 (GPLv3), FFmpeg 9.1 (LGPL 2.1 or later) and dav1d 1.5.5 (BSD). The decoder Dockerfile pins source checksums and builds the JNI library for each supported Android ABI. Media3 handles file and network input. The first bundled build compiles the native sources, and Docker caches subsequent builds. The generated files live in `Banama/.gradle/decoder/`. Debug builds accept `-PbundledHevc=true` through `./dev Banama ./gradlew`. To inspect the active video decoder on the emulator, run `./tv-emulator adb logcat -s BanamaDecoder` with a debug build installed.

[Build recipe](decoder/Dockerfile) · [NextLib source](https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.19.0) · [FFmpeg source](https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz) · [dav1d source](https://download.videolan.org/pub/videolan/dav1d/1.5.5/)
