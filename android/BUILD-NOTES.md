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

**Now verified on an emulator.** The x86_64 AVD needs the AEHD hypervisor
driver, installed once from an elevated prompt:

```
C:\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat
```

`emulator -accel-check` then reports `0 · AEHD (version 2.2) is installed and usable`.
The AVD `glow` must keep its real display profile (1080×2400 @ 420 dpi) in
`config.ini` — overriding the size with `adb shell wm size` makes Android compute a
146 dp status bar and the layout looks broken for reasons that have nothing to do
with the app.

What was actually exercised, against the real services on the Mac mini:

| Step | Result |
| --- | --- |
| Install + launch on Android 16 | no crash, `topResumedActivity=Glow` |
| Model service probe from the app | 出图已连接 / 助手已连接 |
| Text to image, 1024² | **61.83 s**, 1024×1024 PNG written to the gallery |
| App backgrounded for 75 s mid-job | job survived, came back showing 完成，已保存 1 张 |
| Retouch with a picked photo | **412 s**, 672×1504 (aspect inherited from a 1:2.22 photo), reference copy saved |
| Viewer comparison | opens in 切换 mode; swiping flips 原图 ↔ 结果 |

Defects found by this run, and fixed:

- The primary button sat under the gesture bar (targetSdk 36 enforces edge-to-edge).
- The elapsed counter never ticked: a composition does not re-run on a clock, so
  the banner stayed on "正在提交…" for the whole job.
- `stopForeground(DETACH)` left the ongoing notification behind, which made the
  system log `Stop FGS timeout`.
- Failures were invisible in logcat; the service now logs the reason.

Still unexercised on a device: 并排/滑动 comparison, 归档/删除/存到相册, and the
multi-reference flow.

## Backgrounding, activity destruction, screen lock and Doze

Three different things can take the app away, and they need three different answers.

**1. The user switches apps.** The foreground service keeps the process and the
HTTP call alive. Verified: a 1024² job finished normally after 75 s in the
background.

**2. Android destroys the Activity but keeps the process.** This is what a device
actually did: the job banner kept counting up while the page had reset to the
default tab, because `JobRegistry` is process-scoped but the `ViewModel` is not.
Every workbench field is now backed by `SavedStateHandle`, `submitting` is derived
from the registry rather than remembered, and the services are re-probed on
`ON_RESUME`.

Reproduce and verify with:

```powershell
adb shell settings put global always_finish_activities 1
```

With that on, a retouch setup (scenario, preset, typed wording) survives
backgrounding and returns intact; the connections come back as 已连接.

**3. The screen locks and the device enters Doze.** A foreground service keeps the
process, but Doze restricts the network and Wi-Fi power save can stall an open
socket — a five-minute generation that never returns. The service now holds a
partial wake lock and a `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock for the length of the
job and releases both when it ends.

Verified with forced deep idle, which is harsher than a real screen lock:

```powershell
adb shell dumpsys deviceidle force-idle
```

| Condition | Result |
| --- | --- |
| App excluded from battery optimisation + screen off + forced deep Doze | finished in **70.33 s**, result and completion notification present |
| Battery optimisation **not** excluded + screen off + forced deep Doze | also finished; `WifiLockManager` shows the lock held then released |

So the locks — not the battery-optimisation exemption — are what makes this hold.
The exemption is still worth having, but the app no longer depends on it.
