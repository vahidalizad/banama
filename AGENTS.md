# Android TV workspace

- `Banama/` is the media app. Its application ID is `tv.scenichome.launcher` for compatibility with installed builds.
- `Launcher/` is BananaLauncher. Its application ID is `tv.scenichome.home`. The TCL Home redirect lives in its debug source set.
- Build and test each app with `./dev Banama` or `./dev Launcher` followed by its Gradle wrapper command. Keep the two app builds independent.
- Test remote focus and app launches on the Google TV emulator. Target the real TV only for requested hardware checks. See the workspace README for setup and cleanup.
- Keep `Emulator/`, generated APKs, local configuration, and keys out of Git.
- Build on the existing apps and follow [CONTRIBUTING.md](CONTRIBUTING.md) when preparing changes for this repository.
