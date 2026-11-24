# TCL Google TV app

Build a sideloaded Android TV app in Kotlin with the Gradle wrapper and a stable application ID. Add `LEANBACK_LAUNCHER`, a TV icon and banner, and D-pad navigation with visible focus. Declare the touchscreen optional.

## Isolated toolchain

- Use the existing Docker Desktop. Pin JDK 17, the Android SDK command-line tools, Platform-Tools (`adb`), the SDK platform, and Build Tools in a `tv-android-dev` image. Build for `linux/amd64` on Apple silicon for Linux SDK tool compatibility. Name the dedicated buildx builder `tv-android` for separate cache cleanup.
- Run with `--rm` and the default bridge network. Bind-mount only this folder at `/workspace`. Mount `tv-android-home` at the container user’s home to persist Gradle caches, ADB keys, and the debug signing key.

## Pair, build, and install

1. Keep the TV awake on the Mac’s trusted LAN. In **Developer options → Wireless debugging**, check **IP address & Port**. It can change. The Mac last reached `192.168.1.112:37623`.
2. In the container, use `adb pair <pairing-ip:port>` with the separate endpoint from **Pair device with pairing code**. Enter the code interactively. Use `adb connect <connect-ip:port>` with the main screen’s endpoint, then check `adb devices -l`.
3. Target the TV with `adb -s <connect-ip:port>`. Use `shell getprop` to read `ro.product.model`, `ro.build.version.sdk`, and `ro.product.cpu.abilist` before choosing the app’s minimum API and any native libraries.
4. Build with `./gradlew :app:assembleDebug`. Install the APK with `adb -s <connect-ip:port> install <apk>`. Test launch from the TV’s Apps screen and navigation with the remote. For edits, rebuild and use `install -r` with the same application ID and signer to retain app data.

## Safety and cleanup

- Scope changes to this app. Keep ADB on the private LAN, the ADB server inside Docker, and pairing codes and keys out of source and logs.
- Run `adb disconnect <connect-ip:port>` and switch Wireless debugging off after testing. Verify the port stops accepting connections while the TV stays online. Removing the app in **Apps** settings also removes its local data.
- To retire the setup, forget the paired client on the TV. Before removing the volume, privately back up `/home/android/.android/bananalauncher/` (release key and password) for shared APK updates and the debug signing key for development updates. Remove the builder with `docker buildx rm tv-android`, the image with `docker image rm tv-android-dev`, and the volume with `docker volume rm tv-android-home`.

[Android TV app setup](https://developer.android.com/training/tv/start/start) · [Android wireless ADB](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) · [Docker Desktop networking](https://docs.docker.com/desktop/features/networking/) · [CleanYour.TV safety FAQ](https://cleanyour.tv/faq)
