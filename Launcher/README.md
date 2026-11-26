# BananaLauncher

Open BananaLauncher from the TV’s Apps screen. Use the favorites dock or **All apps** to open installed TV apps, including Banama. Hold **OK** on a tile to pin, rearrange, or manage it. The Play icon opens Google Play. The gear opens **Launcher settings** with access to TV settings, the default Home picker, and credits for the current Wikimedia Commons photo.

This app installs as `tv.scenichome.home`. Set up favorites in the new app.

## Default Home app

Open **Launcher settings → Default Home app** and choose BananaLauncher in Android's picker. Open Banama or another app, then press **Home** to check the remote. Launcher settings shows which app Android resolves for Home.

BananaLauncher declares `MAIN`, `HOME`, and `DEFAULT`, as FLauncher and TV Launcher do. On Android 10 and newer, it can ask Android to grant the Home role. [Android Home role](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_HOME) · [FLauncher manifest](https://gitlab.com/flauncher/flauncher/-/blob/master/android/app/src/main/AndroidManifest.xml) · [TV Launcher manifest](https://github.com/nielsvanvelzen/tv-launcher/blob/master/app/src/main/AndroidManifest.xml)

The Google TV API 36 emulator grants BananaLauncher the Home role through the picker, then still opens Google TV when you press **Home**. Its built-in Home activity has priority 2, and Android caps third-party apps at 0. Android checks that priority before the saved choice. [Android intent resolver](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/ResolveIntentHelper.java#171) · [Android intent filter priority](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/resolution/ComponentResolver.java#481)

Some TV firmware, including the TCL C89L, can send the remote's **Home** button directly to the stock launcher. Android's Home role applies when the TV delegates Home to Android's default app. You can still open BananaLauncher from the TV's Apps screen.

## Debug Home button fallback

In the debug APK, open **Launcher settings → Home button fallback → BananaLauncher Home fallback** and enable it in Android's Accessibility settings. To verify it, open Banama and press **Home**. Turn it off in the same Accessibility screen.

The service uses the TV's registered system Home activities to recognize when the built-in Home screen opens. It receives activity names from that launcher and asks Android to open BananaLauncher. The enabled service reconnects after a normal restart. Some firmware may keep Home in the system or prevent the service from reconnecting. [Plume's window-based Home fallback](https://github.com/aradhyas8/Plume_Launcher/blob/main/app/src/main/java/dev/plume/HomeWatch.kt) · [Plume's setup detection fix](https://github.com/aradhyas8/Plume_Launcher/pull/171)

See the [workspace guide](../README.md) for builds, emulator testing, and TV installs.
