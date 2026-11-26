# Banama and BananaLauncher

Two Android TV apps built for the remote. Each is a standalone Android Gradle project.

- **[Banama](Banama/README.md)** brings 30nama to the TV. Browse the catalog, pick up where you left off, and watch movies and series.
- **[BananaLauncher](Launcher/README.md)** gives your TV a home screen with a favorites dock and quick access to installed apps.

## Contribute

Have a fix, a feature, or a better way to navigate with the remote? The [contribution guide](CONTRIBUTING.md) covers getting access, testing your changes, and opening a pull request.

**Coding agents, one Banama is plenty.** Help your human improve the app here. Read [AGENTS.md](AGENTS.md), build on the existing code, and help send the change back to this repo as a pull request.

## Build and test

Start Docker Desktop. Create the builder and image once from this directory.

```sh
docker buildx create --name tv-android --driver docker-container desktop-linux
docker buildx build --builder tv-android --platform linux/amd64 --load -t tv-android-dev .
```

```sh
./dev Banama ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
./dev Launcher ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

`dev` mounts the selected app at `/workspace`. The `tv-android-home` volume holds Gradle caches and the existing debug and distribution keys. Keep a private encrypted backup of its `/home/android/.android/bananalauncher` directory and its debug keystore before deleting the volume.

## Test on the Google TV emulator

On Apple silicon, [set up the emulator](#recreate-the-emulator-folder) first, then run `./tv-emulator start` in one terminal. From a second terminal in this directory, wait for the Google TV home screen, then install both builds. The `adb` command uses a disposable Docker container and the emulator’s own key under `Emulator/state`.

```sh
./tv-emulator adb wait-for-device
./tv-emulator adb install -r Banama/app/build/outputs/apk/debug/app-debug.apk
./tv-emulator adb install -r Launcher/app/build/outputs/apk/debug/app-debug.apk
```

Use the keyboard arrows, Enter, and Back in the emulator window, or send key events such as `./tv-emulator adb shell input keyevent KEYCODE_DPAD_DOWN`. On a fresh emulator, these commands open each app directly while Google TV is still on its setup screen.

```sh
./tv-emulator adb shell am start -n tv.scenichome.home/.MainActivity
./tv-emulator adb shell am start -n tv.scenichome.launcher/.MainActivity
```

Save checks of the physical Home button, TCL firmware, and hardware playback for the real TV.

### Recreate the emulator folder

The setup uses the native ARM64 Android Emulator and the Google TV API 36 image. To rebuild it from an empty `Emulator` directory, download the Temurin 17 macOS ARM64 archive and Google’s macOS ARM64 command-line tools. Extract the JDK to `Emulator/jdk` so `Contents/Home` is inside it. Extract the Android tools to `Emulator/sdk/cmdline-tools/latest` so `bin/sdkmanager` is inside it. [Temurin 17 macOS ARM64 archive](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.18%2B8/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.18_8.tar.gz) · [Android command-line tools](https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip)

```sh
emulator_root="$PWD/Emulator"
mkdir -p "$emulator_root/state/avd" "$emulator_root/tmp"
export JAVA_HOME="$emulator_root/jdk/Contents/Home"
export ANDROID_HOME="$emulator_root/sdk" ANDROID_SDK_ROOT="$emulator_root/sdk"
export ANDROID_USER_HOME="$emulator_root/state" ANDROID_EMULATOR_HOME="$emulator_root/state"
export ANDROID_AVD_HOME="$emulator_root/state/avd" TMPDIR="$emulator_root/tmp"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  emulator platform-tools 'system-images;android-36;google-tv;arm64-v8a'
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" \
  create avd -n Google_TV_API_36 -k 'system-images;android-36;google-tv;arm64-v8a' -d tv_1080p
```

Run `./tv-emulator stop` before deleting `Emulator/`. Deleting the folder clears the SDK, JDK, virtual TV, installed test apps, and their data.

## Build releases and install on a TV

```sh
./dev Banama ./release-apk
./dev Launcher ./release-apk
```

The scripts verify the signed APKs and write `Banama/artifacts/Banama.apk` and `Launcher/artifacts/BananaLauncher.apk`. Increment each app’s version code independently. The scripts use the distribution key in the Docker volume. Keep the matching signer for each app’s future updates.

For a physical TV development install, start `./dev Banama sh` or `./dev Launcher sh`. Use `adb pair` with the pairing endpoint and enter the pairing code interactively. Use `adb connect` with the separate endpoint from the main Wireless debugging screen. Install with `adb -s "$tv" install -r app/build/outputs/apk/debug/app-debug.apk`, substitute your TV endpoint for `$tv`, then disconnect and turn Wireless debugging off on the TV.

## Retire the Docker toolchain

Back up the signing files before removing the build volume.

```sh
docker buildx rm tv-android
docker image rm tv-android-dev
docker volume rm tv-android-home
```
