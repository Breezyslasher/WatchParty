# Watch Party Setup

Installs [Watch Party for Android TV](../watchparty-tv) so its **notification
access** switch works on Android 13+, without a computer.

## Why it's needed

Watch Party needs notification access — it's the only way Android lets an
app see and control what another app is playing. On Android 13+ the switch
for it is greyed out if Watch Party was installed by opening its APK in a
file or transfer app (e.g. Send Files to TV). That's deliberate, from
Android 14's source:

- The system installer labels an APK handed to it by another app as
  `PACKAGE_SOURCE_LOCAL_FILE` or `PACKAGE_SOURCE_DOWNLOADED_FILE`
  ([InstallStaging.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/packages/PackageInstaller/src/com/android/packageinstaller/InstallStaging.java)).
- For those two sources only, the package manager sets
  `OP_ACCESS_RESTRICTED_SETTINGS` to `MODE_ERRORED`
  ([InstallPackageHelper.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/services/core/java/com/android/server/pm/InstallPackageHelper.java))
  — the greyed-out switch — and never clears it on later updates.
- An app installing through its own `PackageInstaller` session keeps the
  source it asks for; the default, `PACKAGE_SOURCE_UNSPECIFIED`, isn't
  restricted. That's what app stores do, and what Setup does.
  (`adb install` uses `PACKAGE_SOURCE_OTHER`, also not restricted.)

## Use

1. Send **Watch Party Setup** to the TV (Send Files to TV is fine — Setup
   itself never needs notification access) and open it.
2. **Allow Setup to install apps** — the per-app "Install unknown apps"
   switch, which is not a restricted setting.
3. **Remove the blocked Watch Party**, if one is installed: the block never
   clears, so that copy has to go (the TV asks to confirm). Its relay/room
   settings go with it.
4. **Install Watch Party** and confirm. Then open Watch Party → **Grant
   access** → switch it on.

Later updates can be installed with **Update Watch Party** in Setup, so they
keep the unrestricted install.

## Build

Setup bundles the Watch Party APK in `assets/`. Build Watch Party first
(`../watchparty-tv`: `./gradlew assembleDebug`); this project's `preBuild`
copies it in. Then `./gradlew installDebug`.
