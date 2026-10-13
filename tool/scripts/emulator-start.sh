#!/usr/bin/env bash
# Boots the Light_Phone emulator (LightOS emulator installed as a system app)
# and waits until Android has finished starting.
set -e

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5554

if "$ADB" devices | grep -q "^$SERIAL"; then
    echo "Light_Phone emulator is already running."
    exit 0
fi

# -writable-system is required every boot, or the LightOS system app won't load.
"$SDK/emulator/emulator" -avd Light_Phone -writable-system > /dev/null 2>&1 &

echo "Starting Light_Phone emulator..."
"$ADB" -s "$SERIAL" wait-for-device
until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    sleep 2
done
echo "Light_Phone emulator is ready."
