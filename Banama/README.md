# Banama

Open Banama from your TV’s Apps screen. Use **Search** for catalog and saved-list browsing and **30nama** to connect, pair again, or disconnect. Continue Watching and the movie and series shelves bring you to details and playback.

For native 30nama access, set `thirtyNama.clientKey` in the ignored `local.properties` next to this wrapper. If 30nama supplies a different endpoint, also set `thirtyNama.api`. Use a client identifier intended for distribution in an APK. The app requests a pairing link and displays a QR code for your phone. [30nama API research](docs/30nama-api.md)

Banama keeps the original package `tv.scenichome.launcher`. Install the same-signer update over the old app to keep your sign-in, progress, and subtitle preferences. A debug APK uses the debug signer and the shareable release uses the distribution signer. If your TV used the old app as Home, install BananaLauncher and select it as Home before updating to Banama.

See the [workspace guide](../README.md) for builds, emulator testing, and TV installs.
