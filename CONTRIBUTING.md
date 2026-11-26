# Contributing

Thanks for helping improve Banama or BananaLauncher. Send changes to `main` through a pull request. Keep each pull request focused on one change so it’s easy to review and try on a TV.

## Get access and create a branch

This is a private repository. Ask Vahid for an invitation if you can’t open it on GitHub. With write access, you can push a branch here. If GitHub offers you a fork, you can work in your fork and open a pull request back to this repository.

Start a branch from the latest `main`. In a fresh checkout, for example, a README fix could start with these commands.

```sh
git clone https://github.com/vahidalizad/my-tv-launcher.git
cd my-tv-launcher
git switch -c docs/clarify-emulator-setup
```

For a fork, clone your fork’s URL. If you already have a fork, use **Sync fork** on GitHub first. In an existing checkout, run `git switch main` and `git pull --ff-only` before creating your branch.

## Check your change

- Run the unit tests and build the debug APK for each app you changed. The [build instructions](README.md#build-and-test) show the separate `./dev` commands.
- For UI changes, try the affected flow on the Google TV emulator. Check D-pad focus, OK, Back, and any app launches your change affects. Use a physical TV for requested hardware checks. [Emulator setup](README.md#test-on-the-google-tv-emulator)
- Keep `Emulator/`, APKs, `local.properties`, and keys out of commits.

## Open a pull request

1. Check `git status` and `git diff --check`, then stage only the files for your change. These commands commit and push the README example.

   ```sh
   git add -- README.md
   git commit -m 'Clarify emulator setup in the README'
   git push -u origin docs/clarify-emulator-setup
   ```

2. On GitHub, open **Pull requests → New pull request**. Set the base to `main` in `vahidalizad/my-tv-launcher` and compare against your branch. If you pushed to a fork, choose **compare across forks** to select it.
3. Give the pull request a descriptive title. Explain what changed, why, and how you tested it. Include reproduction steps for a bug or a screenshot for a visible change when it helps. Click **Create pull request**.
4. Request a review. Push any follow-up commits to the same branch and GitHub will update the pull request.
