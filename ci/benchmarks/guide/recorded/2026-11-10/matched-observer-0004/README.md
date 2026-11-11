# Matched instrumented app RAM comparison

This is a new, separate comparison after the [full-dump measurement correction](../RAM-MEASUREMENT-CORRECTION.md). It does not replace or pool the earlier unfavorable/failed observations. Six trials completed with no accepted-series failures, alternating baseline/candidate three times, each after the same verified cold full-state restore. All **1,071 numeric samples**, including prelaunch rows, GC-coincident reads, gaps and outliers, are retained byte-for-byte in the six CSVs. [Summary](primary-summary.json) gives every metric/window, GC interval, read duration and clock event; [hashes](files.json) identify the exported files. No app data, archive, image, heap dump, profile or endpoint is included.

## Input and artifacts

- Baseline: normalized upstream `67570ddd`, APK SHA-256 `1fde1a86c757c4998c34f4bf02663203ece814554fd7e9effaf51d23da87967f`.
- Candidate: combined preview `ac39dab9c89ca59066c4065ba97afb536200984a`, guide production `99fd3388` plus the frame-rate fix, APK SHA-256 `2640fdd257ae47339f3f5315b48e2d1aee90519cf9a89d6e4667a963c26794b0`. These are combined-artifact observations, not an isolated causal estimate for one PR.
- Same x86_64 Android 16 emulator, 512 MiB managed-heap cap, version code/signing/test application ID, observer and fixture on both sides. No video was playing. This is not the reported SHIELD Android 11 long-session workload.
- Synthetic primary input: 4,000 channels/guide IDs, 96,000 stored listings, 48-hour schedule. Before/after all ten programme fields and all bindings hashed identically: `450149e798a310d5e1f922c642d4e00a978c45714631eea9c17874b75d1044d1`. All six fixture request/XML-transfer deltas were zero during measurement. The full guide remained stored; no pruning or clearing was used to improve the result.
- The baseline already contains upstream normalized one-copy storage. These numbers do **not** measure Beta.13.2 to current development, nor attribute upstream deduplication to this PR.

## Method

The separate [observer](../../../observer/README.md) reads Runtime/Debug.MemoryInfo in the actual target VM about once per second; original target APKs are unchanged. This is **matched instrumented execution**, not passive or zero-overhead measurement. Instrumentation changes class loading, threads, scheduling, OOM treatment and logging. Matched UI-hierarchy/readiness observations add overhead. No explicit GC, heap dump, debugger, tracing or trim is requested. ART still collects naturally; used heap includes unreclaimed garbage and is not retained heap.

The origin is each CSV's unique positive `main_launch_requested_elapsed_ns`. Startup sampled peaks use `[0,120)` seconds; the fixed late window is `[90,120)` with at least 20 complete samples. Each guide appeared before 90 seconds. Only trial 3 on each side traverses 600 rows, then waits through 61 seconds; its fixed settle window is `[30,60)` seconds after the device-clock browse-end marker. Device markers have 10 ms resolution and recorded ADB intervals. No window was shifted and no outlier/GC-coincident row was removed. All clocks/cadences/read durations remain in the summary. No gap exceeded two seconds; the largest was 1.931 seconds.

Runtime used/max is **bytes**. PSS is **KiB**. On this Android version raw total PSS includes SwapPSS: resident component PSS is dalvik+native+other, while derived SwapPSS is reported separately. Public `summary.total-swap` is a separate nonproportional swap counter and must not be added to PSS. The hidden support flag is unknown (`-1`). PSS includes graphics/native mappings; the 512 MiB cap applies to managed heap, not total process PSS.

## Fixed late startup results

Each entry below is a trial's median over its fixed window, not a post-GC value. MiB divides bytes by 1,048,576 and KiB by 1,024.

| Trial | Baseline Runtime used (bytes) | Candidate Runtime used (bytes) | Baseline resident PSS (KiB) | Candidate resident PSS (KiB) |
| --- | ---: | ---: | ---: | ---: |
| 1 | 58,408,176 | 86,846,096 | 464,278 | 487,695 |
| 2 | 86,174,016 | 68,062,256 | 490,165 | 476,710 |
| 3 | 84,057,600 | 54,308,176 | 489,238 | 460,925 |

- Median of three trial medians: Runtime **80.16 → 64.91 MiB** (−19.03%); resident PSS **477.77 → 465.54 MiB** (−2.56%).
- Trial-median Runtime ranges: **55.70–82.18 → 51.79–82.82 MiB**. Resident PSS ranges: **453.40–478.68 → 450.12–476.26 MiB**. They overlap; **trial 1 regresses** on both metrics. Three trials do not establish a uniform improvement or a stable distribution.
- Derived SwapPSS median of trial medians: **410 → 409 KiB**. Paging is retained in the record and is not a sufficient explanation of the observed resident difference.
- Late-window sample counts: baseline **29/30/30**, candidate **30/30/31**. Within-window individual Runtime values range **43,084,928–194,391,872 → 37,596,496–148,656,720 bytes**; no highs are filtered.

## Peaks and browsing

Startup sampled Runtime peaks (trial 1/2/3, bytes): baseline **79,068,400 / 194,391,872 / 173,719,232**; candidate **148,656,720 / 135,903,520 / 71,441,744**. Median of these peaks is **165.67 → 129.61 MiB**. Startup resident PSS peaks (KiB): **546,346 / 645,907 / 598,648 → 554,955 / 542,292 / 514,293**. These are sampled maxima, not continuous peak guarantees.

The single 600-row browse pair took **103.05 → 105.58 seconds**, with channel 601 verified. Fixed post-browse median Runtime was **110,689,264 → 86,771,488 bytes (105.56 → 82.75 MiB)**; resident PSS **518,173 → 489,679 KiB (506.03 → 478.20 MiB)**, 30 samples per side. This is **one pair**, not a repeated browsing distribution or a speedup.

The candidate has a **worse transient during the browse phase**: sampled Runtime peak **114,445,536 → 161,359,152 bytes (109.14 → 153.88 MiB)**, resident PSS **522,800 → 634,079 KiB (510.55 → 619.22 MiB)**. The lower settle median does not erase that spike. Source improvements and this emulator comparison do not establish that the reported long-session SHIELD stall is fixed; that remains a device-validation hold.

First-guide observations in seconds after launch: baseline **32.144 / 29.239 / 27.732**, candidate **31.664 / 29.505 / 32.369**. No first-guide latency gain is claimed. Search/Sports correctness and the optional larger shared-guide measurement have separate evidence; the synthetic Sports teams are not a live scoreboard match test.

## Attempt accounting and limits

The accepted series has six complete trials and no retries. Before it, observer pilot 0001 failed at launch and produced only a CSV header because the activity intent lacked `FLAG_ACTIVITY_NEW_TASK`; that failed attempt is preserved privately. A one-line fix produced frozen observer 0004, and pilot 0002 passed 30 samples before this series. Pilot values are not pooled here. All historical full-dump runs, partial captures and unfavorable values remain separately classified as GC-perturbed diagnostics under the correction above.

Sports cache ages and external Sports responses were not frozen. Alternating order/common restored state limits but does not eliminate time effects. Neither source tests nor emulator RAM measurements certify provider authentication, playback, HDMI frame-rate switching, physical-device focus or five-hour session stability. This PR remains a draft pending the reported-device workload and contribution-policy resolution.
