# App RAM measurement correction — 2026-11-10

Full dumpsys meminfo invokes app-side garbage collection before reading managed-heap fields; PSS is captured earlier. See the [Android framework source](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java).

The APK-process values in actual-app-ram-* are GC-perturbed diagnostics. This overrides earlier natural/no-forced-GC labels. All numbers, regressions and outliers remain unchanged.

The normalized full-dump series was also stopped and reclassified. Replacement collection is being validated. Isolated query/materialization and JDK allocation experiments remain separate; no app-RAM saving is established.
