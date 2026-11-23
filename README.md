# Scenic Home

A standalone Android TV launcher for the TCL C89L. Open **Scenic Home** from the TV's **Apps** screen. **All apps** scrolls down to the app grid. Hold **OK** on an app for its options. The gear opens the TV settings panel.

Press **Down** from favorites to reach Watchlist, Notify, Liked, and Suggested. The hero loads from watch history and shows upcoming Watchlist episodes as they arrive. Suggested artwork fills the hero when no titles are ready. Each saved list ends with **See all**, which opens Search for that list. The Home header opens catalog Search with release-year and genre filters, release-year and IMDb sorting, and YouTube search. Hold **OK** on the gear to manage the 30nama connection. Pair from a phone using the launcher's QR code.

Scenic Home requests the pairing link directly from 30nama and renders a QR code on the TV. For native access, set `thirtyNama.clientKey` and, if supplied by 30nama, `thirtyNama.api` in the ignored `local.properties` file before building. Use a client identifier intended for distribution in an APK. See [30nama API research](docs/30nama-api.md) for the integration and playback checks.

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

During a dependency review, check AndroidX and the pinned SDK toolchain for relevant changes. Rebuild and run the tests in Docker, then install over the previous APK using the same signing volume. Check remote focus, Google Play, and the Home hero on the TCL.

The TV banner uses [Murlough Bay](https://commons.wikimedia.org/wiki/File:Murlough_bay_(48770060832).jpg) by Salvatore Gerace, licensed CC0.
