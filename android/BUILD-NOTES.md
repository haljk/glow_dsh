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

1. **Unit tests** — `:app:testDebugUnitTest`. The 44 conformance vectors recorded
   from the shipped desktop implementation must all match, plus invariant tests
   (legal size edges, no preset freezing what it changes, reference numbering).
2. **Build** — `:app:assembleDebug` produces an installable APK.
3. **Device/emulator run** — install, launch, exercise the three scenarios, and
   confirm a real generation against the model service.
