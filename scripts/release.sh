#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle.kts"

usage() {
    echo "Usage: $0 [patch|minor|major] [--no-build] [--no-release]"
    echo ""
    echo "Bumps version, runs tests, builds APK, commits, pushes, creates GitHub release."
    echo ""
    echo "  patch        1.2.0 -> 1.2.1 (default)"
    echo "  minor        1.2.0 -> 1.3.0"
    echo "  major        1.2.0 -> 2.0.0"
    echo "  --no-build   Only bump version, skip build + release"
    echo "  --no-release Build but skip GitHub release"
    exit 1
}

BUMP_TYPE="patch"
BUILD=true
RELEASE=true

for arg in "$@"; do
    case "$arg" in
        patch|minor|major) BUMP_TYPE="$arg" ;;
        --no-build) BUILD=false; RELEASE=false ;;
        --no-release) RELEASE=false ;;
        -h|--help) usage ;;
        *) echo "Unknown arg: $arg"; usage ;;
    esac
done

current_version=$(grep 'versionName' "$BUILD_GRADLE" | head -1 | sed 's/.*"\(.*\)".*/\1/')
current_code=$(grep 'versionCode' "$BUILD_GRADLE" | head -1 | sed 's/[^0-9]*//g')

IFS='.' read -r major minor patch <<< "$current_version"

case "$BUMP_TYPE" in
    patch) patch=$((patch + 1)) ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    major) major=$((major + 1)); minor=0; patch=0 ;;
esac

new_version="$major.$minor.$patch"
new_code=$((current_code + 1))

echo "Version: $current_version -> $new_version"
echo "Code:    $current_code -> $new_code"

sed -i "s/versionCode = $current_code/versionCode = $new_code/" "$BUILD_GRADLE"
sed -i "s/versionName = \"$current_version\"/versionName = \"$new_version\"/" "$BUILD_GRADLE"

echo "Updated build.gradle.kts"

RELEASE_APK="$PROJECT_DIR/app/build/outputs/apk/debug/nudge-v${new_version}.apk"

if [ "$BUILD" = true ]; then
    echo "Running tests..."
    cd "$PROJECT_DIR"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    ./gradlew test --quiet

    echo "Building debug APK..."
    ./gradlew assembleDebug --quiet

    RAW_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$RAW_APK" ]; then
        cp "$RAW_APK" "$RELEASE_APK"
        echo "Built: $RELEASE_APK ($(du -h "$RELEASE_APK" | cut -f1))"
    else
        echo "ERROR: APK not found"
        exit 1
    fi
fi

cd "$PROJECT_DIR"
git add app/build.gradle.kts
git commit -m "chore: bump version to $new_version" || true

if [ "$RELEASE" = true ] && [ -f "$RELEASE_APK" ]; then
    echo "Pushing to origin..."
    git push origin main

    echo "Creating GitHub release v$new_version..."
    gh release create "v$new_version" "$RELEASE_APK" \
        --title "v$new_version" \
        --generate-notes

    rm -f "$RELEASE_APK"

    echo "Release: https://github.com/astraedus/nudge/releases/tag/v$new_version"
fi

echo ""
echo "Done. v$new_version (code $new_code)"
