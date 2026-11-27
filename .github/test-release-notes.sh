#!/usr/bin/env bash
set -euo pipefail

notes_script=$(cd "$(dirname "$0")" && pwd)/release-notes.sh
repo=$(mktemp -d)
trap 'rm -r -- "$repo"' EXIT
cd "$repo"
git init -qb main
git config user.name 'Release notes test'
git config user.email 'test@example.com'
mkdir Banama Launcher
touch Banama/app Launcher/app
git add .
git commit -qm 'Initial release'
git tag banama-base
git tag launcher-base

git switch -qc feature
printf 'new' > Banama/app
git commit -qam 'Internal implementation detail'
git switch -q main
git merge -q --no-ff feature -m 'Merge pull request #21 from test/feature' -m 'Add watch progress'
git tag "banama-$(git rev-parse HEAD)"
[[ $(bash "$notes_script" banama) == $'## Changes\n\n- Add watch progress (#21)' ]]

printf 'fix' > Launcher/app
git commit -qam 'Fix launcher focus'
touch dev
git add dev
git commit -qm 'Update build tools'
[[ $(bash "$notes_script" banama) == $'## Changes\n\n- Update build tools' ]]
[[ $(bash "$notes_script" launcher) == $'## Changes\n\n- Update build tools\n- Fix launcher focus' ]]
