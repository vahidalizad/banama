# Scenic Home

A standalone Android TV launcher for the TCL C89L. Open **Scenic Home** from the TV's **Apps** screen. Hold **OK** on an app for its options. Photo rotation, credits, and TV settings are under **Settings**.

## Build and test

From this directory, create the Docker builder and image once.

```sh
docker buildx create --name tv-android --driver docker-container desktop-linux
docker buildx build --builder tv-android --platform linux/amd64 --load -t tv-android-dev .
```

Then build and run the tests.

```sh
./dev ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The named volume `tv-android-home` holds the Gradle caches, ADB client key, and debug signing key.

## Install on the TV

With the TV awake, open **Developer options → Wireless debugging**. Pair once using the port in **Pair device with pairing code**. Enter the code at the terminal prompt.

```sh
./dev adb pair 192.168.1.112:PAIRING_PORT
```

Use the address on the **main** Wireless debugging screen for the install. Run connect, install, and disconnect in the same container session.

```sh
./dev sh
TV=192.168.1.112:CONNECT_PORT
adb connect "$TV"
adb devices -l
adb -s "$TV" install -r app/build/outputs/apk/debug/app-debug.apk
adb disconnect "$TV"
exit
```

After testing, switch Wireless debugging off on the TV. Keep the Docker volume to update in place with `install -r` and retain favorites and settings. To remove Scenic Home, use the TV's Apps settings. See [AGENTS.md](AGENTS.md) for Docker cleanup.

## Update

During a dependency review, check AndroidX and the pinned SDK toolchain for relevant changes. Rebuild and run the tests in Docker, then install over the previous APK using the same signing volume. Check remote focus, Google Play, photo credits, and **Next photo** on the TCL.

Scenic Home rotates featured [Wikimedia Commons photographs](https://commons.wikimedia.org/wiki/Category:Featured_pictures_of_landscapes). The offline photo and banner crop use [Murlough Bay](https://commons.wikimedia.org/wiki/File:Murlough_bay_(48770060832).jpg) by Salvatore Gerace, licensed CC0.
