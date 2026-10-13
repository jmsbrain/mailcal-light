# Mailcal (Light SDK tool)

A mailbox.org (CalDAV) calendar for the Light Phone III: agenda, month view, add, edit, delete, plus read-only .ics feeds.

This repo is a clone of lightphone/light-sdk (remote `upstream`; not a GitHub fork, never run `gh repo fork`). Mailcal is the `:tool` module (`tool/`); everything else is upstream SDK. Keep changes inside `tool/`, `tools/` and this file so upstream merges stay easy.

## Rules

- Kotlin + Jetpack Compose, `foundation` only (no Material). Black background, white text, Akkurat when present, LightOS sizes (see `LightStyle.kt`).
- SDK limits are enforced at build time: no `android.app.*`, `Context`, `Intent`, `LocalContext`, `androidx.activity.*`, reflection, own `AndroidManifest.xml`, or Java in `tool/src`. Permissions and capabilities go in `tool/lighttool.toml` (allowlist in `plugin/.../LightToolMetadata.kt`).
- Ask for as few permissions as possible. Say what leaves the phone in `tool/README.md`.
- Keep code comments short.
- Version: `versionName` (MAJOR.MINOR.PATCH) and `versionCode` +1 per release, in `tool/lighttool.toml`. Tag releases `vX.Y.Z`.
- Commit as you go. Ask before pushing. Never force-push yourself.

## Build and install

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :tool:assembleDebug
adb -s <serial> install -r tool\build\outputs\apk\debug\tool-debug.apk
.\gradlew.bat :tool:testDebugUnitTest
```

For the LightOS emulator (AVD Light_Phone, emulator-5554): `tool/scripts/emulator-start.sh`, then `tool/scripts/emulator-install.sh` (add `--mock` for MockCalDav). The script switches `serverPackage` and builds with `-DlightSdk.allowAltServerPackage=true` for that build only, and installs by serial so a USB-connected phone is never touched.

Use made-up data for screenshots.

## Project notes

- Pages (`Pages.kt`) are SDK `SimpleLightScreen`s; the composables in `*Screen.kt` are the UI. The SDK disposes a page's composition when another page opens on top, so form state uses `rememberOnPage` and work uses `LocalPageScope`, not `remember`/`rememberCoroutineScope`.
- Text input goes through the LightOS editor (`TextEditPage`, opened by `Field`). It has no password masking.
- The SDK's system back pops the page without asking it (and `androidx.activity.*` is banned, so no BackHandler): the edit form's discard prompt only covers CANCEL, and feed edits save as they're made. Date and time pickers are their own pages (`DatePickerPage`, `TimePickerPage`) so back only closes the picker.
- Repo and Feeds never keep a `SealedLightContext` (it wraps the activity); pages pass theirs (`LocalLightContext`) for background-job calls.
- Needs INTERNET only. Background refresh is a `@LightJob` every 30 min (`SyncWorker.kt`). No DAVx5 / CalendarContract: the server is the only copy, edits PUT with If-Match.
- Reminders are saved to the event (VALARM) but the phone doesn't alert: the SDK has no alarms or notifications yet. The old app's `Reminders.kt` (exact alarms + boot receiver) is the reference if that changes.
- Settings are a JSON file (`Prefs.kt`); the password is AES-GCM encrypted with an Android Keystore key.
- `VCal.kt` edits raw iCalendar so unknown properties survive; cover changes with `tool/src/test/.../VCalTest.kt`.
- `Ics.kt` (parser + recurrence expansion) came from SubcalScriber.
- Test end to end on the emulator with `tools/MockCalDav.java`: run `java tools/MockCalDav.java`, install with `emulator-install.sh --mock` (temporarily adds `cleartext-http`; never commit that) and use server `http://10.0.2.2:5232/`. Never test with the user's real account.
