# App RAM measurement correction — 2026-11-10

Full dumpsys meminfo invokes app-side garbage collection before reading managed-heap fields; PSS is captured earlier. See the [Android framework source](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java).

The APK-process values in actual-app-ram-* are GC-perturbed diagnostics. This overrides earlier natural/no-forced-GC labels. All numbers, regressions and outliers remain unchanged.

The normalized full-dump series was also stopped and reclassified. A new [matched instrumented comparison](matched-observer-0004/README.md) now uses in-process Runtime/Debug.MemoryInfo without an explicit GC request. It reports all six trials, overlapping ranges and an unfavorable candidate browsing peak; it is not pooled with these diagnostics or presented as passive execution. Isolated query/materialization and JDK allocation experiments remain separate. No uniform app-RAM saving or reported-device resolution is established.
