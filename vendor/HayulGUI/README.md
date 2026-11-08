# HayulGUI

A generic **APK shared-uid patch workstation** for Android — run the whole
toolchain on-device, no root, no adb, no DeviceOwner.

This project is a **fork and Android port of
[ye-seola/Hayul](https://github.com/ye-seola/Hayul)** — rehosted here as private.
The original is a Python desktop tool that patches APKs so two apps can share a
Linux `uid` (`android:sharedUserId`) and spoof each other's certificate;
HayulGUI reimplements that workflow natively on the phone itself, in Kotlin +
Jetpack Compose.

## What it does

1. **Generates or imports an RSA signing key** (stored in app data; it does not
   leave the device).
2. **Patches any APK set**:
   - rewrites `AndroidManifest.xml` in place to inject / set `android:sharedUserId`
   - injects a stub dex that forwards `AppComponentFactory` and hooks signature
     lookups so paired apps see each other's original certificate
   - re-signs the result (v2/v3; v1 only when the legacy path requires it)
   - handles split sets: apk / xapk / apkm, base + splits patched as one unit
3. **Installs the result** on the same device through `PackageInstaller`
   sessions (split sets commit atomically). Artifacts you no longer need are
   deletable in-app.

Apps that share the chosen `sharedUserId` run under the same uid and can
exchange data through shared permissions/provider signatures — the classic
pairing trick, now without a PC.

> ⚠️ Uninstalling HayulGUI also deletes its signing key. Any artifact signed with
> that key becomes uninstall-only. Export your key from the Home tab if you plan
> to keep patching after a reinstall.

## Usage

```
Home  → generate (or import) key, set the sharedUserId group id
App List → Extract (ADB) tab: list installed apps, copy their `adb pull` command
         → Downloads tab: pick .apk/.xapk/.apkm, tap Patch
Install → tap Install on an artifact, confirm the system dialog
```

Patched artifacts whose signature doesn't match the currently installed package
are shown disabled ("서명불일치") so a mis-tap can't fail the install — uninstall
the old app yourself first, if needed.

## Modules

| Module   | What it is |
|----------|------------|
| `:app`   | Compose UI (Home / App List / Install), Korean strings |
| `:core`  | patch engine: binary AXML rewrite, raw-deflate zip io, apksig signing, `PackageInstaller` sessions |
| `:stub`  | runtime stub (Java) → `d8` → bundled as `stub.dex` asset, injected into patched APKs |

## Building

Requirements: JDK 17, Android SDK.

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :core:testDebugUnitTest   # core engine tests (incl. real-APK roundtrip)
```

minSdk 26, target/compile SDK 35. No root or adb needed on the target device.

## Acknowledgements

HayulGUI is a fork/Android port of the original desktop tool and stands on the
ideas of several projects:

- [ye-seola/Hayul](https://github.com/ye-seola/Hayul) — original desktop tool,
  the whole patching concept comes from there
- [ye-seola/HayulBasicStub](https://github.com/ye-seola/HayulBasicStub) —
  reference stub; our `:stub` is a clean-room reimplementation
- [xxxyanchenxxx/SigKill](https://github.com/xxxyanchenxxx/SigKill) — the
  signature-hook pattern our `SigHook` reimplements (no code reused)
- [iyxan23/zipalign-java](https://github.com/iyxan23/zipalign-java) — inspiration
  for zip alignment semantics
- [AOSP apksig](https://android.googlesource.com/platform/tools/apksig) — used
  directly as a dependency (`com.android.tools.build:apksig`, Apache-2.0) for
  APK signing and verification

## License

MIT — see [LICENSE](LICENSE). The apksig dependency remains under its
Apache-2.0 terms (see NOTICE); the stub reimplements patterns credited above
without copying code from them.
