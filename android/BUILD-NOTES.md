# Android build notes

Everything here is about producing and testing the APK on **this** machine. The
code itself has no machine-specific assumptions.

## Toolchain

| Component | Version | Location |
| --- | --- | --- |
| JDK | Corretto 21.0.10 | `C:\Program Files\Amazon Corretto\jdk21.0.10_7` |
| Android SDK | platform 36, build-tools 36.0.0, platform-tools 37.0.3 | `C:\Android\Sdk` |
| Gradle | 9.1.0 | `C:\gradle\gradle-9.1.0` |
| AGP / Kotlin | 9.0.1 / 2.3.20 | from `gradle/libs.versions.toml` |

`local.properties` points Gradle at `C:\Android\Sdk`. It is machine-local and
must not be committed.

## Why the build runs from `C:\glow-build`

The repository lives under a path containing Chinese characters
(`00_个人资料`). AGP refuses such a path outright; setting
`android.overridePathCheck=true` (already in `gradle.properties`) gets the
compile and packaging steps through, but the **Gradle test worker** then fails
every unit test with:

```
java.lang.ClassNotFoundException: dev.haljk.glow.core.ConformanceTest
```

The classes are compiled and the directory is on the test classpath — the worker
simply cannot read the classpath entry. Mirroring the project to an ASCII path
fixes it without moving the repository.

`tools/build-android.ps1` does the mirror, the build, the tests and the copy
back, so this never has to be remembered.

```powershell
./tools/build-android.ps1 -Test          # build + conformance tests
./tools/build-android.ps1 -Install       # build + install on a device
```

## Why the wrapper is not used locally

`gradle-wrapper.properties` points at the official distribution URL, which
stalled at 0 bytes from this network. Gradle 9.1.0 was downloaded from a mirror
and extracted to `C:\gradle`, and the build invokes that directly. The wrapper
stays valid for anyone else cloning the repository.

## What "self-tested" means here

### Passed

| Check | Command | Result |
| --- | --- | --- |
| Conformance with the desktop implementation | `:app:testDebugUnitTest` | **7/7** — 17 instruction vectors, 24 size vectors, 3 reject vectors, 4 invariants |
| Storage behaviour on a plain JVM | same | **10/10** — settings round-trip, favourites cap, archive/delete, sidecar |
| Rendered interface on the JVM | same | **9/9** — Compose composed and measured by Robolectric; taps dispatched through the real input pipeline |
| Build | `:app:assembleDebug` | APK 12.14 MB, `dev.haljk.glow`, minSdk 26, targetSdk 36 |
| APK structure | `aapt2 dump badging/xmltree` | permissions, `usesCleartextTraffic`, `foregroundServiceType=dataSync`, service not exported |

Total: **26 unit test cases, 0 failures.**

The interface tests also dump the composed semantics tree to
`app/build/ui-tree/*.txt`, which is the closest evidence to a screenshot on a
machine without a hypervisor: every element actually on screen, in order.

### Not yet verified: a run on a device or emulator

The x86_64 emulator needs a hypervisor driver, and installing one requires
administrator rights, which this session does not have. The driver package is
already downloaded:

```
C:\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat
```

Run that in an **administrator** command prompt, then:

```powershell
C:\Android\Sdk\emulator\emulator.exe -avd glow -gpu swiftshader_indirect
./tools/build-android.ps1 -Install
```

Until then, the following remain unproven on real hardware: foreground-service
survival across app switches, upload of a real photo, and a real generation
against the model service.
