#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle.kts"

usage() {
    echo "Usage: $0 [patch|minor|major] [--install] [--no-build]"
    echo ""
    echo "Bumps version, builds debug APK, optionally installs on device."
    echo ""
    echo "  patch   1.2.0 -> 1.2.1 (default)"
    echo "  minor   1.2.0 -> 1.3.0"
    echo "  major   1.2.0 -> 2.0.0"
    echo "  --install   Install APK on connected device after build"
    echo "  --no-build  Only bump version, skip build"
    exit 1
}

BUMP_TYPE="patch"
INSTALL=false
BUILD=true

for arg in "$@"; do
    case "$arg" in
        patch|minor|major) BUMP_TYPE="$arg" ;;
        --install) INSTALL=true ;;
        --no-build) BUILD=false ;;
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

if [ "$BUILD" = true ]; then
    echo "Building debug APK..."
    cd "$PROJECT_DIR"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    ./gradlew assembleDebug --quiet

    APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK" ]; then
        echo "Built: $APK"
        echo "Size: $(du -h "$APK" | cut -f1)"
    else
        echo "ERROR: APK not found at expected path"
        exit 1
    fi

    if [ "$INSTALL" = true ]; then
        echo "Installing on device..."
        adb install -r "$APK"
        echo "Installed v$new_version on device"
    fi
fi

echo ""
echo "Done. v$new_version (code $new_code)"
