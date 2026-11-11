# Bounded guide RAM comparison

Before source `4636df605eb111897bfbb91913300188719399e0`; bounded candidate `df9f514686bb45f4c22b114137614d68709b1ef0`.

These results require review before merge. This exporter makes no merge-readiness or physical-device acceptance decision.

All eight trials and 1930 accepted numeric rows are retained. Three alternating primary browsing pairs each use the same 600 Down keys, plus one separate shared-guide startup pair. No GC-coincident row, spike, cadence gap or outlier is removed.

## Before and after

All figures below are MiB, baseline -> candidate. Exact integers, per-trial results, ranges and all other metrics are in summary.json and numeric-replay.json.

- Late startup managed Runtime, median of three trial medians: **71.63 -> 64.39**.
- Worst sampled primary startup managed maximum: **87.98 -> 85.93**.
- Browsing managed sampled peaks, median of three trial peaks: **129.39 -> 145.20**; worst sampled peak: **139.48 -> 162.57**.
- Browsing sampled peaks, median of three trial peaks: resident PSS **553.60 -> 590.81**, raw total PSS **554.00 -> 591.20**, native heap allocated **53.79 -> 54.92**.
- Settled managed Runtime, median of three trial medians: **89.54 -> 90.30**.
- Settled resident component PSS: **488.42 -> 495.39**, with derived SwapPSS **6.09 -> 0.39**.
- Settled native heap allocated: **42.67 -> 43.15**.

The separate shared-guide startup pair has late managed median **83.93 -> 84.95** and sampled managed maximum **115.40 -> 199.44**. Its late resident PSS is **491.69 -> 468.93**, derived SwapPSS **0.39 -> 27.20**, raw total PSS **492.08 -> 496.13**, and native heap allocated **41.44 -> 41.37**. This is one pair, not a repeat distribution.

Median actual 600-key traversal duration is **91.84 -> 94.99 seconds**, baseline -> candidate. Every trial duration/range remains in the numeric records; this small host-dependent observation is not a causal speed claim.

Startup uses [90,120) seconds from the actual in-process main-launch request. Sampled startup maximum uses [0,120); browsing peaks use actual begin/end markers; settled windows use [30,60) after browse end. Samples have observer/class-loading/thread/scheduling overhead. No explicit GC, trim or profiler is requested by this method. A 512MiB managed limit does not cap process/graphics/native memory.

Resident PSS and derived SwapPSS are separate; paging can lower resident PSS without an equal total footprint reduction. Raw total PSS and nonproportional swap remain separately reported. Independent medians and maxima need not sum. Actual browse durations are retained, with no causal speed/significance claim from this small host-dependent study. Agent-owned builds were stopped; user games, OS file cache, paging and unrelated scheduling remain uncontrolled.

## Coverage and limits

Primary preserves 4,000 bindings/96,000 rows. Shared preserves 26,716 bindings, 4,477 guide IDs, 107,448 stored/641,184 projected rows and all -1/0/+1-hour shifts. Two fresh cold proofs recompute all ten programme fields and the complete channel catalogue; hashes and capture/final-CSV bindings are in provenance.json. These proofs do not establish every Search query or real Sports event/channel match.

Some measured startups include automatic On Demand metadata refresh. Each trial retains its numeric fixture action counts; those counts do not attribute the requests to Sports.

Counter evidence is separate: 4 of four possible candidate last-persisted snapshots validate. Missing observations are not zero-filled. Counters include reserved capacity, including fixed offset capacity, and describe estimated browsing payload rather than whole-app heap. The five-second publication snapshots may be stale and are not exact instantaneous high-water measurements. Source guards/tests prove the contract separately. The candidate's bounded debug-writing overhead is included; the pristine pre-bounded baseline has no such counter feature.

Separate protocol-failures/manifest.json accounts for 1 reviewed failed/setup/proof attempts and all retained numeric CSV snapshots. Prefix/partial snapshots may overlap and never enter accepted statistics. Raw logs, UI captures, app data, profiles and traces remain private. Earlier public negative records remain intact.

## Reproduction

Run `python summarize-observer-csv.py --record-dir .` from this record directory for independent offline numeric replay. The manifest explicitly declares all-primary browsing. Replay validates arithmetic, clocks, windows, peaks, GC intervals, cadence and all declared cases, but does not recreate private full-data/capture proofs or establish input/lifecycle parity by itself.

files.json hashes every other exported file, including CSV bytes, helper source, this README and .gitattributes. No raw provider/profile contents are included. Android16 x86_64 emulator evidence does not establish Android11 SHIELD playback or long-session acceptance.
