# BananaLauncher

An Android TV launcher by B4Banana. Open **BananaLauncher** from the TV's **Apps** screen. **All apps** scrolls down to the app grid. Hold **OK** on an app for its options. The gear opens the TV settings panel.

## Install the shared APK

1. Send `artifacts/BananaLauncher.apk` to a TV running Android TV 8.0 or later. Open it with the TV's file manager or transfer app.
2. If Android asks to **Allow from this source**, allow the app that opened the file. Return to the installer and choose **Install**. Reopen the APK if the installer closed.
3. Choose **Open**, or select **BananaLauncher** in **Apps**. Hold **OK** on the gear to see where Android routes Home. Choose **Default Home app** to open Android's picker.

On the TCL C89L, Android can select BananaLauncher and still open Google TV when you press **Home**. To set up a remote shortcut, install **Button Mapper** by **flar2** from Google Play and enable its Accessibility service on that TV.

1. In Button Mapper, choose **Add Buttons → Add Buttons** and press **Back** once on the physical remote. If Back only closes the prompt, turn on **Troubleshooting → Use scancodes** and **Alternate button handling**, then register Back again.
2. Open **Back**, turn on **Customize**, leave **Single tap** on **Default**, and set **Double tap → Applications → BananaLauncher**. Choosing **Home** as the action sends the command back to Google TV.
3. To configure the house button too, register **Home** under **Add Buttons**, turn on **Customize**, and set **Single tap → Applications → BananaLauncher**.
4. Open another app and test each shortcut with the physical remote. Both worked from Google Play on the TCL. Launcher settings still shows Android's underlying Home destination. Button Mapper opens BananaLauncher directly. These shortcuts are local to that TV and require Button Mapper's Accessibility service to stay enabled. [Button Mapper's Android TV guide](https://buttonmapper.app/remap-android-tv-remote)

Version 1.1.3 is the first build signed with the distribution key. If a previous debug-signed copy is installed, remove **BananaLauncher** under **Settings → Apps** and open the new APK again. Uninstalling clears local favorites, settings, and the 30nama sign-in. Future releases signed with the same distribution key support **Update** in place.

The release uses Android's Home picker and needs no Accessibility access. Google lists Accessibility as a trigger for the “App blocked to protect your device” warning when sideloading. If the new release still shows this warning, use Google's Play Protect review process. [Google Play Protect developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)

## Using the launcher

Press **Down** from favorites to reach Watchlist, Notify, Liked, and Suggested. The hero loads from watch history and shows upcoming Watchlist episodes as they arrive. Suggested artwork fills the hero when no titles are ready. Each saved list ends with **See all**, which opens Search for that list. The Home header opens catalog Search with release-year and genre filters, release-year and IMDb sorting, and YouTube search. Hold **OK** on the gear to manage the 30nama connection. Pair from a phone using the launcher's QR code.

BananaLauncher requests the pairing link directly from 30nama and renders a QR code on the TV. For native access, set `thirtyNama.clientKey` and, if supplied by 30nama, `thirtyNama.api` in the ignored `local.properties` file before building. Use a client identifier intended for distribution in an APK. See [30nama API research](docs/30nama-api.md) for the integration and playback checks.

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

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The named volume `tv-android-home` holds the Gradle caches, ADB client key, and signing keys.

### Build a release to share

```sh
./dev ./release-apk
```

This builds the signed release, checks the packaged APK, and writes `artifacts/BananaLauncher.apk`. Increment `versionCode` for each update. Keep a private encrypted backup of the entire `/home/android/.android/bananalauncher` directory, including the password, before removing the Docker volume. Restore that directory when moving release builds to another machine.

The original release key has already been created for this project. For a new distribution with its own signing identity, initialize an empty volume once.

```sh
./dev sh -eu -c '
umask 077
mkdir -p "$HOME/.android"
mkdir "$HOME/.android/bananalauncher"
cd "$HOME/.android/bananalauncher"
openssl rand -hex 32 > password
keytool -genkeypair -storetype PKCS12 -keystore release.p12 -storepass:file password \
  -alias bananalauncher -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=BananaLauncher"
'
```

### Install a development build with ADB

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

After testing, switch Wireless debugging off on the TV. Keep the Docker volume to update in place with `install -r` and retain favorites and settings. To remove BananaLauncher, use the TV's Apps settings. See [AGENTS.md](AGENTS.md) for Docker cleanup.

### Test the Home button on the TCL

Press **Home** on the remote to check the selection. Choose **Google TV** in **Settings → Apps → Default apps → Home app** to switch back on TVs that expose this setting.

The debug build also includes the TCL Home redirect. To test it, hold **OK** on the gear in BananaLauncher and choose **Remote Home button**. In Accessibility, turn on **BananaLauncher Home redirect**. Press **Home** from another app to test it. The service also opens BananaLauncher once after each restart. Google TV may appear briefly as the redirect opens BananaLauncher. To reverse the redirect, turn off the same Accessibility service.

On the TCL C89L, if the service stays enabled but stops working after a restart, allow TCL auto-start for BananaLauncher from the paired ADB session and restart again.

```sh
adb -s "$TV" shell cmd appops set tv.scenichome.launcher AUTO_START allow
adb -s "$TV" shell cmd appops write-settings
```

## Update

During a dependency review, check AndroidX and the pinned SDK toolchain for relevant changes. Rebuild and run the tests in Docker, then install over the previous APK using the same signing volume. Check remote focus, Google Play, and the Home hero on the TCL.
