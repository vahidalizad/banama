# BananaLauncher

Open BananaLauncher from the TV’s Apps screen. Use the favorites dock or **All apps** to open installed TV apps, including Banama. Hold **OK** on a tile to pin, rearrange, or manage it. The Play icon opens Google Play. The gear opens **Launcher settings** with access to TV settings, the default Home picker, and credits for the current Wikimedia Commons photo.

This app installs as `tv.scenichome.home`. Select it again via **Launcher settings → Default Home app** and set up favorites in the new app.

## TCL Home button

On the TCL C89L, the system may route the physical Home button to Google TV even after you select BananaLauncher. Open **Launcher settings** to see where Android routes Home.

The debug APK offers **Launcher settings → Remote Home button**. Enable **BananaLauncher Home redirect** in Accessibility and press **Home** from another app to test. Disable the same service to turn the redirect off. If TCL stops the debug service on restart, allow TCL auto-start from the paired development session.

```sh
adb -s "$tv" shell cmd appops set tv.scenichome.home AUTO_START allow
adb -s "$tv" shell cmd appops write-settings
```

The shareable APK uses Android’s Home picker. For the TCL C89L, Button Mapper by flar2 can bind **Home → Single tap** or **Back → Double tap** to **Applications → BananaLauncher**. If Back closes the button-registration prompt, enable **Use scancodes** and **Alternate button handling** in Button Mapper’s Troubleshooting settings. [Button Mapper’s TV guide](https://buttonmapper.app/remap-android-tv-remote)

See the [workspace guide](../README.md) for builds, emulator testing, and TV installs.
