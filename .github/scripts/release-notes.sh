#!/usr/bin/env bash
# Writes the GitHub release body for VERSION to OUT: the version's section of CHANGELOG.md and
# CHANGELOG_EN.md, then the install note. Sets found=true|false in $GITHUB_OUTPUT when it is set.
# Usage: release-notes.sh VERSION OUT
set -euo pipefail

version="$1"
out="$2"

# The lines under "## VERSION …" up to the next "## ", without the blank lines around them
section() {
    awk -v head="## ${version} " '
        index($0, head) == 1 { on = 1; next }
        on && /^## / { exit }
        on && !started && /^[[:space:]]*$/ { next }
        on { started = 1; print }
    ' "$1" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}'
}

ru="$(section CHANGELOG.md)"
en="$(section CHANGELOG_EN.md)"

{
    if [ -n "$ru" ]; then printf '## Что нового\n\n%s\n\n' "$ru"; fi
    if [ -n "$en" ]; then printf "## What's new\n\n%s\n\n" "$en"; fi
    printf '## Install\n\n'
    printf 'Download the APK below and install it on your Android device (API 26+).\n\n'
    printf 'See the [README](https://github.com/%s#readme) for build and setup instructions.\n' \
        "${GITHUB_REPOSITORY:-drrhaos/runner}"
} > "$out"

if [ -n "$ru$en" ]; then found=true; else found=false; fi
echo "found=$found" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "Release notes for $version: changelog section found=$found"
