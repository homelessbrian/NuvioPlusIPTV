# Bounded guide RAM comparison

Before source `4636df605eb111897bfbb91913300188719399e0`; bounded candidate `96f9d91114394e7a709985e818b3bb6d866b6630`.

These results require review before merge. This exporter makes no merge-readiness or physical-device acceptance decision.

All eight trials and 1952 accepted numeric rows are retained. Three alternating primary browsing pairs each use the same 600 Down keys, plus one separate shared-guide startup pair. No GC-coincident row, spike, cadence gap or outlier is removed.

## Before and after

All figures below are MiB, baseline -> candidate. Exact integers, per-trial results, ranges and all other metrics are in summary.json and numeric-replay.json.

- Late startup managed Runtime, median of three trial medians: **62.80 -> 73.00**.
- Worst sampled primary startup managed maximum: **88.16 -> 97.69**.
- Browsing managed sampled peaks, median of three trial peaks: **129.03 -> 139.86**; worst sampled peak: **129.43 -> 146.33**.
- Browsing sampled peaks, median of three trial peaks: resident PSS **566.50 -> 597.35**, raw total PSS **597.59 -> 597.75**, native heap allocated **50.49 -> 54.70**.
- Settled managed Runtime, median of three trial medians: **101.73 -> 100.05**.
- Settled resident component PSS: **492.01 -> 513.34**, with derived SwapPSS **26.52 -> 0.39**.
- Settled native heap allocated: **42.57 -> 42.26**.

The separate shared-guide startup pair has late managed median **85.82 -> 84.89** and sampled managed maximum **125.49 -> 115.68**. Its late resident PSS is **495.49 -> 451.02**, derived SwapPSS **0.39 -> 47.17**, raw total PSS **495.89 -> 498.18**, and native heap allocated **38.52 -> 41.44**. This is one pair, not a repeat distribution.

Median actual 600-key traversal duration is **94.99 -> 96.86 seconds**, baseline -> candidate. Every trial duration/range remains in the numeric records; this small host-dependent observation is not a causal speed claim.

Startup uses [90,120) seconds from the actual in-process main-launch request. Sampled startup maximum uses [0,120); browsing peaks use actual begin/end markers; settled windows use [30,60) after browse end. Samples have observer/class-loading/thread/scheduling overhead. No explicit GC, trim or profiler is requested by this method. A 512MiB managed limit does not cap process/graphics/native memory.

Resident PSS and derived SwapPSS are separate; paging can lower resident PSS without an equal total footprint reduction. Raw total PSS and nonproportional swap remain separately reported. Independent medians and maxima need not sum. Actual browse durations are retained, with no causal speed/significance claim from this small host-dependent study. Agent-owned builds were stopped; user games, OS file cache, paging and unrelated scheduling remain uncontrolled.

## Coverage and limits

Primary preserves 4,000 bindings/96,000 rows. Shared preserves 26,716 bindings, 4,477 guide IDs, 107,448 stored/641,184 projected rows and all -1/0/+1-hour shifts. Two fresh cold proofs recompute all ten programme fields and the complete channel catalogue; hashes and capture/final-CSV bindings are in provenance.json. These proofs do not establish every Search query or real Sports event/channel match.

Some measured startups include automatic On Demand metadata refresh. Each trial retains its numeric fixture action counts; those counts do not attribute the requests to Sports.

Counter evidence is separate: 4 of four possible candidate last-persisted snapshots validate. Missing observations are not zero-filled. Counters include reserved capacity, including fixed offset capacity, and describe estimated browsing payload rather than whole-app heap. The five-second publication snapshots may be stale and are not exact instantaneous high-water measurements. These observations validate registered accounting, not independently every UI-retained object. Ownership coverage requires separate source and lifecycle checks. The candidate's bounded debug-writing overhead is included; the pristine pre-bounded baseline has no such counter feature.

Separate protocol-failures/manifest.json accounts for 3 reviewed failed/setup/proof attempts and all retained numeric CSV snapshots. Prefix/partial snapshots may overlap and never enter accepted statistics. Raw logs, UI captures, app data, profiles and traces remain private. Earlier public negative records remain intact.

## Reproduction

Run `python summarize-observer-csv.py --record-dir .` from this record directory for independent offline numeric replay. The manifest explicitly declares all-primary browsing. Replay validates arithmetic, clocks, windows, peaks, GC intervals, cadence and all declared cases, but does not recreate private full-data/capture proofs or establish input/lifecycle parity by itself.

files.json hashes every other exported file, including CSV bytes, helper source, this README and .gitattributes. No raw provider/profile contents are included. Android16 x86_64 emulator evidence does not establish Android11 SHIELD playback or long-session acceptance.
