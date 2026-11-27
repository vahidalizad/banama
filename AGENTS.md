# Android TV workspace

- `Banama/` is the media app. Its application ID is `tv.scenichome.launcher` for compatibility with installed builds.
- `Launcher/` is BananaLauncher. Its application ID is `tv.scenichome.home`.
- Build and test each app with `./dev Banama` or `./dev Launcher` followed by its Gradle wrapper command. Keep the two app builds independent.
- Test remote focus and app launches on the Google TV emulator. Target the real TV only for requested hardware checks. See the workspace README for setup and cleanup.
- Keep `Emulator/`, generated APKs, local configuration, and keys out of Git.
- Build on the existing apps and follow [CONTRIBUTING.md](CONTRIBUTING.md) when preparing changes for this repository.
- Write PR titles like short release notes. Use the PR template for one to three bullets under Changes and a brief Verification section. The release workflows generate each app's changelog from the merged titles.
