#!/usr/bin/env bash
# Builds Mailcal for the LightOS emulator, installs it on the Light_Phone emulator, and opens it.
# Targets the emulator by serial so a Light Phone plugged in over USB is never touched.
# --mock also allows plain http for this build, to test against tools/MockCalDav.java
# (server http://10.0.2.2:5232/). lighttool.toml is restored afterwards either way.
set -e
cd "$(dirname "$0")/../.."

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5554
TOOL=com.jmsbrain.mailcal.light
TOML=tool/lighttool.toml

if ! "$ADB" devices | grep -q "^$SERIAL"; then
    echo "Light_Phone emulator isn't running. Start it with tool/scripts/emulator-start.sh"
    exit 1
fi

if [ -z "$JAVA_HOME" ] && [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
fi
export ANDROID_HOME="$SDK"

# Point at the LightOS emulator for this build only; lighttool.toml always goes back to com.lightos.
BACKUP="$(mktemp)"
cp "$TOML" "$BACKUP"
trap 'cp "$BACKUP" "$TOML"; rm -f "$BACKUP"' EXIT
sed -i 's/^serverPackage = "com.lightos"\r\?$/serverPackage = "com.thelightphone.sdk.emulator"/' "$TOML"
if [ "$1" = "--mock" ]; then
    sed -i 's/^capabilities = \[\]\r\?$/capabilities = ["cleartext-http"]/' "$TOML"
fi

ANDROID_SERIAL=$SERIAL ./gradlew :tool:installDebug -DlightSdk.allowAltServerPackage=true

"$ADB" -s "$SERIAL" shell monkey -p "$TOOL" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
echo "Mailcal installed and opened on the Light_Phone emulator."
