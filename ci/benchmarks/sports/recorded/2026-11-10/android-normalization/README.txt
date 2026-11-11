Actual-source Android normalization component, six fresh VMs

This accepted series compares the complete SportsCatalog.kt compiled from the
before and after source bytes pinned in provenance.json. NormBenchmark.java is
the EXACT measured Java source (SHA a7e67db3cc3a9c14fd4446efd4f6cdbdb6fd05de8f86467958dff5a6b19a6c00).
It is not a reimplementation of norm. The override DEX was first in the classpath,
with the unchanged dependency APK second. Source-role field-shape checks and
private runtime identity checks passed. Only allowlisted result fields are
exported here; no process/user IDs, classloader strings or raw logs are included.

Each fresh VM makes 192,000 actual norm calls: 4,000 guide channels x 24 slots x
(title plus description). Before timing, all 768 distinct titles and one Unicode
description are evaluated (769 semantic calls), followed by 4,096 warmup calls.
Every timed result is checked with exact String.equals. The full 192,000-result
sequence SHA is computed outside timing and agrees in all six runs:
0562a978215de19bfbcfbfe9c0655008219f69de4cde50660c9cd061f3d5f7c9.
Elapsed time includes equality validation; it excludes SHA work.

Order is before1, after1, after2, before2, before3, after3. All six completed
measurements, including the slower first before trial, are retained unchanged.
No accepted timing run failed or was retried. An earlier compile-only attempt
failed because a TeamInfo signature was missing; that failure remains preserved.
The corrected preparation added only the seventh REAL compile-only class file.
No fake models were used and none of those signature files entered runtime DEX.
Runtime model and Kotlin dependencies came from the pinned real APK.

The measured median time was 3.7480344s before and 2.1611969s after (-42.34%).
Median allocated-byte deltas were 662,437,888 and 340,656,128 (-48.58%). All
values, minima, maxima, GC and allocation counters appear in summary/trials files.
Three runs per role do not support a reliable p95 estimate.

These were Android 16 x86_64 shell helper VMs. Although -Xmx512m was requested,
Runtime.maxMemory() was exactly 201,326,592 bytes (192MiB) in ALL SIX runs. They
do not reproduce the application's 512MiB heap environment. Runtime used/total/
free values are ordinary before/after snapshots, not peaks or retained-heap data.
ART allocated-byte counters are process-wide ordinary counter reads, not isolated
per-method live memory. They include any concurrent helper-VM allocation.

No explicit GC, debugger, profiler, Activity, app-data operation or media/provider
traffic was requested. The dependency APK remained unchanged, all six owned VMs
exited, the owned scratch namespace was removed, and the preview app stayed
stopped. This is normalization component evidence, NOT app RAM/PSS, full Sports
matching latency, playback, a physical SHIELD test, or proof a 512MiB spike fixed.

This export contains source, numeric component records and hashes only. It has
no APK/DEX binaries, app data, private environment paths or raw logs. Source code
contains its original guard/logging FIELD NAMES and a fixed owned-device namespace
regex; actual private guard values and classloader outputs are not exported.
