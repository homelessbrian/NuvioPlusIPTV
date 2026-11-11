# PR description before the additional quality review

Captured on 2026-11-10 from PR #105 at source/head 302d4e07. This historical description preserves the earlier observations, regressions, failed attempts, units and limitations. Later measurements and fixes are recorded separately; none of these values are replaced by a more favorable later trial.

## Summary

**Measurement correction, 2026-11-10:** Earlier APK full-dump values are GC-perturbed diagnostics, overriding former natural/no-forced-GC labels. PSS and heap fields were collected on different sides of the implicit GC. Every observation is retained; no natural-RAM saving is established. See the [correction and Android source](https://github.com/ghbarker/NuvioPlusIPTV/blob/perf/guide-browsing-and-import/ci/benchmarks/guide/recorded/2026-11-10/RAM-MEASUREMENT-CORRECTION.md). Source tests, builds and JDK allocation checks remain valid.

**Draft: reported-device RAM hold remains.** Combined preview `ac39dab9` (guide `99fd3388` on normalized upstream `67570ddd`, plus AFR) passed **345 Live TV tests across 39 suites**. A new six-trial matched instrumented comparison gives late-startup median Runtime **80.16 → 64.91 MiB** and resident PSS **477.77 → 465.54 MiB**. Ranges overlap, the first candidate trial regresses, and the candidate has a **higher transient browsing peak** despite lower settled memory. All rows and unfavorable results are retained. These are emulator observations, not proof that the reported long-session SHIELD stall is fixed or that this is ready to merge. Sports also remained unfinished in both bounded runtime observations; its completion is not established.

The later Search-only follow-up `c30f5edc` preserves the earlier baseline's card retention during periodic refresh, after validation caught a focus regression in measured candidate `ac39dab9`. Its full build and timed D-pad check passed separately; the frozen RAM series remains for the preceding artifact.

On a large catalogue, opening the guide hydrates about 21 hours for every channel, then expands full future schedules for the first 400 group channels. Bound browsing to **384 four-hour channel tiles**, while retaining the complete eligible guide on disk. Programme Search and Sports read that stored guide independently of which rows have been browsed.

The implementation keeps upstream's normalized `listings` / `guide_ch` / `chan` storage and source-update/reuse model. Shared channels keep one stored schedule, with their own binding shifts. It removes duplicate concurrent Sports feed loads, avoids event trees for excluded HockeyTech dates, reuses date formatters per worker and avoids filtering the full catalogue for snapshot-only match fences.

## PR type

- [ ] Reproducible bug fix
- [ ] UI glitch/bug fix
- [ ] Behavior bug/regression fix
- [ ] Small maintenance only, with no UI or behavior change
- [ ] Docs accuracy fix
- [ ] Translation/localization only
- [ ] Approved larger or directional change

This draft changes caching and loading behavior. No accepted contribution category or maintainer approval is asserted.

## Why

The complete provider guide should remain available without keeping every channel's near-term and future programmes in the browsing graph. A cache miss for one row/time window must not be treated as evidence that the stored guide is incomplete.

- Startup first publishes up to **96 cached priority channels over five hours**. The grid requests up to **80 visible/focused/prefetch keys**; the cache's request safety limit is 96. At most **two explicitly requested full schedules** are retained alongside the global current/next view.
- Current/next uses two indexed row lookups per guide channel/shift, rather than decoding every future listing to select two. Equal guide-channel/shift bindings share programme and list identities, including global-offset conversion.
- Tile coverage includes empty results, cancellation and epoch/database-change/revision fences. Immutable map publications share the overview and snapshot only bounded overrides.
- Every normalized source finish is fenced before its live binding swap. Archive writes refresh all bindings sharing the affected guide channel, including different shifts. Unchanged source files remain reusable; listings are not copied per playlist channel.
- Update ownership clears busy state after failure/cancellation, recovers the actual committed guide and retains later automatic retries. Optional RAM-projection failures do not permanently hide a committed disk guide.
- Sports feed cache misses coalesce per key without holding unrelated keys through I/O. HockeyTech rejects excluded dates before constructing teams/status/competitor trees, and now reuses exactly the Eastern-day and UTC-output formatters per thread with the original patterns, locale, leniency and normalized predicate. MatchInputs lazily builds its policy-filtered catalogue only for actual consumers; every profile/policy/revision/event fence still reads current inputs. Requested-day/month/full-calendar output, score-bar overrides, date boundaries and concurrent use remain tested.

## Issue or approval

No linked issue or explicit maintainer approval has been supplied. The contribution-policy and RAM holds remain open.

## Reproduction steps

1. Import a large eligible playlist/guide using the same frozen input and settings in both builds.
2. Open a large group, move beyond the first 400 rows and browse forward through guide hours.
3. Search for a future programme on an unbrowsed channel; run Sports matching against a game listed only on a late catalogue channel.

The baseline broadly hydrates schedules. The new path reads requested tiles, keeps a small current/next projection, and queries the complete stored guide for Search/Sports. Full schedules and provider metadata remain queryable on disk. Current matched app observations and their limitations are recorded below; physical SHIELD acceptance remains open.

## UI / behavior impact

- [x] No UI change
- [ ] No behavior change
- [ ] UI changed only to fix a documented glitch/bug
- [ ] Behavior changed only to fix a documented bug/regression
- [ ] UI change has explicit maintainer approval
- [ ] Behavior change has explicit maintainer approval

No new layout or controls. Loading, cache retention, invalidation, retry/recovery and programme-query behavior change.

## Policy check

- [x] I have read and understood `CONTRIBUTING.md`.
- [ ] This PR fits an accepted contribution category.
- [ ] Any feature, larger change, dependency addition, or architecture change has explicit maintainer approval.
- [x] This PR is focused on one problem or approved request.
- [x] This PR does not bundle unrelated refactors, cleanups, formatting, or drive-by changes.
- [ ] This PR includes a linked issue, reproduction steps, and testing notes if it is a bug fix.
- [x] I listed the testing performed below.

## Scope boundaries

Every eligible channel and the configured guide horizon remain stored. Search preserves channel-name/number priority, two-character Unicode title substrings, channel ordering, the six-hour horizon, the global 40-card limit, visibility/parental policy and guide offsets. Sports uses the full eligible Live TV catalogue, rather than the viewport; this adds no VOD scan.

There is no additional application dependency, external API or schema change relative to normalized upstream `67570ddd`. Its one-copy listings, per-channel bindings/shifts, per-source atomic swaps and unchanged-file reuse are preserved. The parser optimization from merged PR #104 is already in that base. No new channel cap, horizon pruning or trim is used. The correction above applies to implicit GC in full app dumps.

## Testing

### Current normalized source checks

These are separate, partly overlapping checks; their counts must not be added into a single suite total.

- **26,716-channel actual Sports/Search proof:** `SportsMatcherNormalizedCatalogueTest` passed using current compiled `SportsMatcher`, database and policy helpers with disk-backed native SQLite (Robolectric 4.17, API 34, Windows x86_64). It stores 26,716 guide channels/listings/bindings, requests only the first 384 browsing channels, and places the only valid game on channel **26,716**. The real matcher finds that unbrowsed channel. Blocked matches, hidden channel/group, locked group and unlocked-group access are checked. Search finds that channel with binding and global offsets applied once each; reversed catalogue order verifies the global 40-result ranking. All ten listing fields plus channel bindings/shifts are logically hashed before/after reads and reopening; counts, hashes and revision remain unchanged. This is source/SQLite acceptance, not TV navigation, playback or provider acceptance.
- **Normalized integration suite:** eight checks passed: seven disk-backed SQLite cases plus injected statement-preparation failure/transaction rollback. They cover 651 bindings, shared objects/lists, independent shifts, overlap handling, complete schedules, Unicode search, Sports scans/word lookup and shared archive updates.
- **Guide/recovery helpers:** 35 targeted Kotlin/JVM checks passed at the integration checkpoint; two normalized Search adapter mocks also passed. The current complete Android suite below remains the final acceptance gate.
- **Sports source/component checks at `99fd3388`:** both pinned `30a5fc7b` and after source compilations passed the same 29-check host suite. This includes full nested-JSON parity, DST gaps/repeated hours, Eastern midnight, negative epochs, Gregorian-cutover/expanded-year behavior, changed process defaults and eight concurrent scoreboard workers. The lazy-input component checks real source-compiled private MatchInputs/filter code, complete policy-eligible output and shared list identity under concurrent consumers. The separate matched upstream HockeyTech-only pair passed seven tests per revision. These counts describe separate, overlapping suites; they are not a final Android suite total.

### Measured build and later Search-only follow-up

- **Measured guide production:** `99fd3388`, combined preview `ac39dab9` on normalized upstream `67570ddd`, including AFR. The later Search-only guide fix is `c30f5edc`, combined preview `84a3b3b6`; only `LiveTvSearch.kt` differs between those preview sources.
- **Measured-artifact full Gradle/JUnit suite: PASS.** Combined preview `ac39dab9` passed **345 Live TV tests across 39 suites**, with **zero failures, errors or skips**. The full build completed in **14m 4s**. This is combined source integration evidence, not a guide-only test count or physical-device acceptance.
- **Measured APK: VERIFIED.** The x86_64 full debug artifact is **115,721,188 bytes**, SHA-256 `2640FDD257AE47339F3F5315B48E2D1AEE90519CF9A89D6E4667A963C26794B0`. Signature v2 and 16 KiB ZIP alignment passed.
- **Runtime Search/Sports observations retained:** paired future `Team 9 vs Team 14` / 06:00 UTC queries were four to six hours ahead, with only guide channel 1 browsed. Baseline reached unbrowsed **channel 650** through D-pad focus-only navigation. Measured candidate `ac39dab9` returned upcoming cards, but both D-pad attempts lost focus to the search field/IME; channel 650 remained unconfirmed. This is a **failed Search navigation check**, not a pass inferred from returned cards.
- **Sports did not complete within either bounded observation:** baseline **104.93 s**, candidate **97.80 s**, both still displaying “Finding channels for today's games…”. Neither page-open nor synthetic programme results establish a real event/channel match. No media was activated and fixture catalogue/XML/media request deltas were zero. External Sports responses were not frozen; no Sports completion or latency improvement is claimed.
- **Search focus follow-up:** guide `c30f5edc`, combined preview `84a3b3b6`, changes only `LiveTvSearch.kt` relative to the measured artifact. The earlier producer cleared its publication on every 30-second clock refresh, removing the focused result row. Keep still-valid cards mounted during refresh; profile-ready and current access/catalogue/revision stamps continue to hide invalid cards and activation still rechecks fresh access. Query-loading retention preserves baseline behavior. Independent source review confirmed the regression path. The follow-up **passed 345 Live TV tests / 39 suites again**, zero failures/errors/skips, in **12m 24s**. Its x86_64 APK is **115,721,188 bytes**, SHA-256 `c8c98764dd51cf0f6b022c30cdb866d10f397e28c4310aca4215cea79174a165`; signature v2/16 KiB ZIP alignment passed and the three OpenVPN payloads are unchanged. On a separately recorded four-to-six-hour `Team 9 vs Team 15` / 08:00 UTC query, first-card focus was retained across **68.7859245 s**, then **20 Right keys reached channel 650**, with card focus retained and IME absent. No result/media activation, fixture request deltas zero. [Original failed checks and successful follow-up](https://github.com/ghbarker/NuvioPlusIPTV/blob/perf/guide-browsing-and-import/ci/benchmarks/guide/recorded/2026-11-10/matched-observer-0004/runtime-smoke.json). All RAM results below remain explicitly for `ac39dab9` **before this Search-only fix**; `84a3b3b6` was not remeasured for RAM.
- **Full-dump RAM v2 series: STOPPED / GC-PERTURBED.** Three baseline and two candidate runs completed; the third candidate was stopped at about104s before browsing. Failed navigation/dump attempts and every raw sample are preserved. These are diagnostic observations, not a natural six-run acceptance result. The replacement observer and its host-only portable build are now verified; the new series below is separate and does not rehabilitate full-dump values.

One **completed diagnostic intermediate baseline-only run** at `67570ddd` is retained separately: the fixed 90–120-second window had 30 complete valid samples, with median managed Heap Alloc **36.21 MiB** and median PSS **291.07 MiB**. Sampled startup peaks were managed Heap Alloc **44.40 MiB** and PSS **398.46 MiB**. This is one baseline observation before the replacement candidate pair, not a three-run aggregate or a completed before/after result.

An earlier attempt aborted because sidebar Home focus could not be verified before guide entry. Its failed navigation/protocol record and samples are preserved and marked invalid. It is excluded for protocol failure, **not discarded as an unfavorable RAM outlier**. The valid run's high sampled PSS peak remains in its evidence.

The RAM v2 primary workload has **4,000 channels / 96,000 programmes**, using frozen 48-hour input with 24 two-hour programmes per guide. Its optional large shape has **26,716 logical channels sharing 4,477 guide IDs**, **107,448 stored listings / 641,184 projected programmes**, and -1/0/+1-hour binding shifts. This differs from both the historical half-hour app fixture and the 26,716-unique-guide source proof above.

The stopped full-dump pair used identical frozen input/common loaded state, verified APK/source identities and an idle host. Report PSS and managed Heap Alloc separately over the fixed 90–120-second window; the third trial also browses 600 rows with actual input duration and a settle phase. Keep the optional large pair separate. Complete normalized bindings and all ten projected fields must match after applying each channel shift once. Preserve sample counts/gaps, peaks, valid outliers, ranges and SwapPSS. Full dumps implicitly requested GC, so this protocol is invalid for natural-RAM acceptance. No trim or live media was used. Physical **SHIELD remains unvalidated**; emulator results do not establish its playback behavior or actual app heap limit.

### Corrected primary RAM comparison — six complete trials

[All numeric CSVs, sanitized summary, hashes, protocol and limits](https://github.com/ghbarker/NuvioPlusIPTV/blob/perf/guide-browsing-and-import/ci/benchmarks/guide/recorded/2026-11-10/matched-observer-0004/README.md). The separately built [observer](https://github.com/ghbarker/NuvioPlusIPTV/blob/perf/guide-browsing-and-import/ci/benchmarks/guide/observer/README.md) reads Runtime/Debug.MemoryInfo in the actual target VM, without explicitly requesting GC. It has instrumentation/class-loading/thread/logging/OOM-treatment overhead; matched UI checks add overhead. This is **matched instrumented execution**, not passive or zero-overhead measurement. Target APKs remain unchanged.

Alternating baseline/candidate three times, with the same verified cold full-state restore before every trial, 4,000 channels/96,000 stored listings, 512 MiB managed cap, no video. Origin is the in-process MainActivity-launch timestamp, not Guide readiness; fixed late window **90–120 s**, at least 20 complete rows. All **1,071 numeric rows** are preserved, including prelaunch, GC-coincident samples and outliers. No gap exceeded two seconds (maximum **1.931 s**); individual reads can be slow and their durations remain recorded.

- **Late Runtime trial medians (bytes), baseline → candidate:** 58,408,176 / 86,174,016 / 84,057,600 → 86,846,096 / 68,062,256 / 54,308,176. Median of trial medians **80.16 → 64.91 MiB (−19.03%)**; trial-median ranges **55.70–82.18 → 51.79–82.82 MiB**. The first pair is worse and ranges overlap; no uniform gain is claimed.
- **Late resident PSS trial medians (KiB):** 464,278 / 490,165 / 489,238 → 487,695 / 476,710 / 460,925. Median **477.77 → 465.54 MiB (−2.56%)**; ranges **453.40–478.68 → 450.12–476.26 MiB**. Android 16 raw total PSS includes SwapPSS, so resident component sum and derived SwapPSS are separate. Derived SwapPSS median **410 → 409 KiB**; public total-swap is not added to PSS.
- **Startup sampled Runtime peaks (bytes):** 79,068,400 / 194,391,872 / 173,719,232 → 148,656,720 / 135,903,520 / 71,441,744. Median peak **165.67 → 129.61 MiB**; worst observed **185.39 → 141.77 MiB**. Resident PSS peaks (KiB) **546,346 / 645,907 / 598,648 → 554,955 / 542,292 / 514,293**. These are sampled maxima, not continuous peak guarantees.
- **Single 600-row browse pair:** traversal **103.05 → 105.58 s**, channel 601 verified; fixed **30–60 s after browse-end**, 30 rows per side, median Runtime **105.56 → 82.75 MiB**, resident PSS **506.03 → 478.20 MiB**. This is one observation per build, not a repeated distribution or speedup.
- **Unfavorable browsing transient:** Runtime peak **114,445,536 → 161,359,152 bytes (109.14 → 153.88 MiB)** and resident PSS **522,800 → 634,079 KiB (510.55 → 619.22 MiB)**. Lower settle memory does not erase this spike. The 512 MiB limit is for managed heap, not total process PSS. Reported-device/long-session validation remains a merge hold.
- **Guide integrity:** complete 4,000 bindings and 96,000 rows/all ten listing fields hash identically before/after; every measured fixture request/XML-transfer delta is zero. No guide pruning, trim or cache clearing was used to manufacture savings. Late sample counts **29/30/30 → 30/30/31**. First-guide observations **32.144/29.239/27.732 → 31.664/29.505/32.369 s**; no latency improvement claimed.

The six accepted trials have no failures/retries. A separate header-only observer pilot failed because the activity launch lacked `FLAG_ACTIVITY_NEW_TASK`; the one-line fix produced frozen observer 0004 and a passing 30-row pilot before this series. Failed pilots and the stopped full-dump series remain preserved separately, never pooled. Both APKs already include upstream normalized storage; these are combined-artifact observations, not Beta.13.2 comparisons or an isolated PR-causality estimate. Sports responses/cache ages were not frozen; other host activity, OS caches and system paging were not controlled. No assistant build ran during accepted samples.

### Separate shared-guide case — one startup per build

The M3U case preserves **26,716 bindings / 4,477 guide IDs / 107,448 stored listings / 641,184 projected ten-field programmes**; shifts verified **0: 8,906; −1h: 8,905; +1h: 8,905**. Same verified loaded state restored before each role, one 120-second startup each, no extra 600-row browse. Both late windows have 30 samples, every fixture catalogue/XML/media delta is zero, and a cold post-pair check preserves all rows/fields/shifts and the complete logical hash. [All shared CSVs and summary](https://github.com/ghbarker/NuvioPlusIPTV/blob/perf/guide-browsing-and-import/ci/benchmarks/guide/recorded/2026-11-10/matched-observer-0004/README.md#separate-26716-channel-shared-guide-case).

- Late Runtime **87,457,120 → 82,130,192 bytes (83.41 → 78.33 MiB)**.
- Resident PSS **506,546 → 476,840 KiB (494.67 → 465.66 MiB)**, **but derived SwapPSS increases 555 → 25,123 KiB (0.54 → 24.53 MiB)**. Much of the resident difference is paging; do not present it as a pure allocation saving.
- Raw total PSS including SwapPSS **507,101 → 501,963 KiB (495.22 → 490.20 MiB)**. This belongs alongside resident PSS. One sequential pair with uncontrolled paging does not establish a general RAM improvement.
- Startup sampled Runtime peaks **124,636,224 → 120,485,600 bytes**, resident PSS peaks **657,347 → 597,455 KiB**. First-guide upper bounds **36.82 → 32.18 seconds**. No failed shared attempts/retries or gaps over two seconds. All samples/GC-coincident reads/outliers remain.

The runtime smoke above preserves the failed candidate Search check and unfinished Sports observations. The Search-only follow-up passed separately, without replacing the measured artifact or its unfavorable observations. Physical SHIELD playback/HDMI/long-session RAM and contribution-policy approval remain unvalidated.

### Prior normalized candidate — completed evidence, not the current build

Preview `6f9e3220` combined guide `30a5fc7b` and AFR `4124` on current upstream. Its full build completed in 5m 11s: **342 Live TV tests across 38 suites**, with **zero failures, errors or skips**, and a full debug APK assembled. This was a combined integration count, not a guide-only suite, and predates the formatter/lazy-input changes.

That earlier x86_64 APK passed signature v2 and 16 KiB ZIP alignment: SHA-256 `0956FB49E38E55F1C218AF07F696239AD599761356552ACE14D4DCFF141BB49F`, 113,986,790 bytes. It is not substituted for candidate `ac39dab9` or substituted into its current matched RAM comparison.

### Sports allocation diagnostics — cumulative host allocation, not app PSS

JDK 21 source diagnostics preserve output while testing distinct allocation paths. The original matched experiments and separate public-harness reproduction use explicitly identified suites/warmups and must remain separate:

- **Matched overall current-base pair, `67570ddd` → `99fd3388`:** **142,703,848 → 48,346,440 bytes** (**136.09 → 46.11 MiB**) for three days of a cached 3,657-game season. Both sides compile their exact six Sports production sources, run the same three HockeyTech functional and three date/defaults/concurrency tests, then the unchanged allocation diagnostic: **seven tests per build, all pass**. Cache/loadingKeys and MatchInputs diagnostics are excluded on both sides. The original selection-only wrapper is now reproducible through committed `-HockeyOnly`; its completed separate public-harness reproduction is recorded below. [Sanitized overall record](https://github.com/ghbarker/NuvioPlusIPTV/blob/a6ec0c0aa4ebcdda8be8ed62fbf32eacc1c9bc12/ci/benchmarks/sports/recorded/2026-11-10/overall-hockey-67570ddd-vs-99fd3388.json).
- **Separate committed public-harness reproduction, `67570ddd` → `99fd3388`:** `-HockeyOnly` passed the same **seven tests on both sides**, using the identical six-test warmup/order and final diagnostic. Allocation was **142,088,064 → 48,769,344 bytes** (**135.51 → 46.51 MiB**), with **37 events / 30,974 bytes and the identical output SHA**. Exact production-source, test and runner hashes are recorded. This is a separate observation; the original overall and incremental pairs remain unchanged. [Sanitized public-harness record](https://github.com/ghbarker/NuvioPlusIPTV/blob/a6ec0c0aa4ebcdda8be8ed62fbf32eacc1c9bc12/ci/benchmarks/sports/recorded/2026-11-10/public-hockey-only-67570ddd-vs-99fd3388.json).
- **Matched incremental pair, `30a5fc7b` → `99fd3388`:** the season diagnostic reports **85,077,984 → 49,325,472 bytes** (**81.14 → 47.04 MiB**) in the same **29-test suite on each side, all pass**. This isolates the new formatter/lazy-input source changes from the earlier early-filter/cache work; its HockeyTech warmup differs from the seven-test overall pair. [Sanitized incremental record](https://github.com/ghbarker/NuvioPlusIPTV/blob/a6ec0c0aa4ebcdda8be8ed62fbf32eacc1c9bc12/ci/benchmarks/sports/recorded/2026-11-10/incremental-30a5fc7b-vs-99fd3388.json).
- **Lazy catalogue component within that incremental suite:** **1,901,744 → 381,688 bytes** for four snapshot-only fence captures plus one actual consumer. The actual private MatchInputs class and production filter visit **133,580 → 26,716 catalogue entries** (five complete passes → one), with **133,580 → 0** entry visits from snapshot-only fences. Both return all **26,714 policy-eligible entries**, including the last channel; the two exclusions are deliberate visibility/parental cases, with explicit-pick behavior preserved. Concurrent consumers share the same complete list. This component excludes preference reads, database work and matching; the before adapter supplies the exact pre-change eager constructor argument.

Every season comparison above returned **37 events / 30,974 UTF-8 bytes**, with identical SHA-256 `1f46d444242c5fbde104291890c933afbafc69a7d6ef4a7914c17c35099dbf8a`. Full nested-JSON day/month/calendar tests remain in the suite. Formatter patterns, Locale.US, Eastern/UTC zones, default leniency and `isoOut → isoMs → inSpan` semantics stay intact. Each worker retains at most its own two formatter instances; no mutable formatter is shared across workers.

Earlier source diagnostics remain historical observations: cold-cache coalescing **96.48 → 12.49 MiB**, with eight overlapping 4,194,304-character requests reduced from eight loads/distinct payloads to one; and the initial season early-filter diagnostic **131.63 → 78.76 MiB**. The eventual cache held one response in both cold-cache versions, and distinct retained payload characters were 33,554,432 → 4,194,304. Do not mix those older warmup/suite observations with the new matched pairs.

All values in this section are **cumulative calling-thread JVM allocation**, not Android PSS, steady-state retention, startup RAM or a physical-device result. Whole-season JSON retention/TTL and catalogue scope are unchanged by the two newest fixes. These source checks neither attribute the historical app regression nor replace the separate current-base app comparison.

<details>
<summary>Historical measurements — older source/schema and different fixtures; all unfavorable observations retained</summary>

The records below predate normalized upstream `67570ddd` and the integrated fixes. Their fixture sizes, source revisions and sample counts remain explicit. They must not be presented as current normalized performance or paired with the new baseline.

The older combined preview `5fca4878490daf121aa7b4be159da7bfad65a2ba` passed 242 Live TV JUnit tests with zero failures/errors/skips across 29 reports. It combined guide `7d816f90`, parser `660fd2d9`, groups `1303d9f0` and ExpressVPN `f9d67542`; this was not a guide-only suite. The 20 standalone map/cache/schedule checks at guide `7d816f90` overlap that suite. Its x86_64 emulator APK built, passed signature/16 KiB ZIP-alignment checks, and had SHA-256 `B7FD78CC4C9DA772D1B16EDAC8EDA1DF169F76B5B1C376C8F35FEA587EF3538B`. That artifact is historical and is not the latest Search-fix test APK.

Older UI checks found channel 650's future programme after 600 rows, and Sports opened with 148 upcoming events. These were Search/UI observations; they did not establish a real Sports event-channel match or playback. The newer native-SQLite matcher proof above is separate.

### Historical isolated Android query/materialization measurements — legacy per-channel schema

API 36 x86_64 emulator, SQLite 3.44.5, fresh `app_process` per sample. The runner reproduces SQL, Java programme decoding and retention; it does not run the compiled app's Kotlin cache. Both strategies use the same complete, read-only database. A Gradle build shared the host, and OS file-cache state was not controlled.

- **4,000 channels / 672,000 stored programmes, five paired alternating trials:** baseline median **8.831 s**, windowing-only median **3.753 s**; sampled p95 **11.331 s → 5.555 s**. Display-retained programmes **211,200 → 8,704**; decoded programmes **226,400 → 8,768**. With five samples, nearest-rank p95 is the observed maximum.
- **16,000 channels / 2,688,000 stored programmes:** one completed baseline observation **561.240 s**; two windowing-only observations **51.826 s** and **50.981 s**. Display-retained programmes **715,200 → 32,704**; decoded programmes **730,400 → 32,768**. This is insufficient for a stable median or p95 claim.
- **Separate 16k cursor experiment, one controlled pair:** default-cursor windowing **43.578 s**, forward-only windowing **31.758 s**. This is one pair, not a general speedup estimate. Both returned the same 32,704 display records/checksum, object counts and 2,688,000 stored rows.

The five-trial windowing results predate the production forward-only helper and do not time the final combined path. Repeated tile requests made zero extra queries/decodes; the never-browsed last channel remained queryable. The complete 16k database SHA-256 was unchanged across the controlled pair. All completed observations, sample counts and checksums are retained in the benchmark directory.

The measured process heap limit was 192 MiB despite a requested 768 MiB launch flag. Component heap/PSS values describe that benchmark process. These experiments exclude application startup, D-pad latency, frame time, playback and provider traffic.

### Historical actual-app RAM observations — older 4,000-channel / 96,000-programme fixture

This separate fixture has **4,000 channels / 96,000 programmes**. PSS values are **MiB** and must not be combined with the larger SQL benchmark datasets above.

The initial after build `26af4a4`, before the map-publication fix, was unfavorable in one full-dump diagnostic replay per build:

- Import-phase sampled peak: **349.874 baseline → 414.628 MiB initial after**.
- Guide settled: **291.797 → 350.661 MiB**; after browsing + 30 seconds: **294.953 → 354.853 MiB**.
- Browsing-phase sampled peak: **298.213 → 358.778 MiB**.
- Warm-start/navigation sampled peak: **322.306 → 401.332 MiB**; warm-guide phase: **290.420 → 346.927 MiB**. Startup includes navigation and is not a duration benchmark.

The final measured combined preview `5fca4878` / guide `7d816f90` completed another full-dump diagnostic replay:

- Import-phase sampled peak: **349.874 baseline → 425.522 MiB final**.
- Guide settled: **291.797 → 340.102 MiB**.
- 600-row browsing sampled peak: **298.213 → 348.089 MiB**.
- After browsing + 30 seconds: **294.953 → 344.931 MiB**.

Settled main metrics use the last-five median from the first 30 valid phase samples; peaks are near-1 Hz sampled maxima. These are one main replay per build, not stable estimates across devices. The import markers begin at different elapsed startup times (195.125 s baseline, 81.051 s final), include manual settings/navigation, and are not import-time measurements. Java memory already rises before the final manual import. Wall-clock cache freshness, background startup work, GC and OS residency are not isolated; these observations alone do not establish the responsible source change.

All **96,000 programme rows / 4,000 channel keys and ten programme fields match baseline exactly**, logical SHA-256 `d52279e67684dd804e40078bf01666503ac7e31677db9e489bfda799023ef83c`. Numeric traces, source/APK hashes and procedure/limits are under `ci/benchmarks/guide/recorded/2026-11-10/actual-app-ram-*`. All unfavorable initial observations remain recorded.

The original baseline has three fresh-process warm runs: **304.452 MiB median**, range **304.228–307.608 MiB**, with 31 samples per run and a last-five median; separate full snapshots each report 264 KiB SwapPSS. Earlier final warm attempts produced no valid measurements because startup ANRs occurred before the guide opened. Android installation/file transfer also degraded. Only the owned idle host Gradle daemon was stopped, and only the test emulator restarted. Failed attempts are excluded; the original three baseline runs are a separate series.

The new guarded, first-row-verified control series completed all **three runs per build with no ANR**, 31 samples per run, using last-five medians:

- Baseline per-run PSS: **281.648 / 281.900 / 279.496 MiB**; median **281.648 MiB**, range **279.496–281.900 MiB**.
- Final per-run PSS: **357.021 / 270.785 / 274.977 MiB**; median **274.977 MiB**, range **270.785–357.021 MiB**.
- Full snapshots: SwapPSS **221 KiB baseline / 225 KiB final**.

The wide first-final-run outlier is preserved. These results do not support a stable resident-RAM improvement claim. The synthetic endpoint was unavailable during this control series; both builds used the complete cached database. The unchanged fixed server was restarted for separate allocation profiling. Allocation profiling is separate from these frozen diagnostic measurements.

No explicit GC, trim or pruning command was used; the full dumps implicitly requested GC as corrected above. Private heap/allocation diagnostics remain separate. The combined after APK also includes parser PR #104, so app-RAM differences cannot be attributed solely to this browsing PR or solely to the parser. A sampled peak is not a guaranteed true peak. **No app-RAM reduction is claimed.**

### Historical sparse allocation diagnostic — six channels / 72 programmes

One coarse 90-second startup pair used the same six-channel / 72-programme pre-import state, with no fixture refresh in either capture. Reported sampled Java allocation bytes were approximately 25.26 MB baseline and 25.42 MB after. Sports feed download/decoding dominated both, especially whole-season HockeyTech responses in `SportsFeeds.get`; the Sports feed source was identical in those historical builds. Coarse sampling cannot prove that smaller paths allocate nothing, and allocation samples do not measure retained memory or PSS.

Both traces retain `config_write_into_file_no_flush` on the emulator's Perfetto v49.0. No overrun/client-error/data-loss counters were observed in this sparse pair, but strict profile quality remains false and the traces are used only as a qualified lead. Earlier lossy attempts are not evidence. The source checker describes the configuration condition in [Perfetto's importer](https://github.com/google/perfetto/blob/v59.1/src/trace_processor/importers/proto/proto_trace_reader.cc#L1288-L1305). These diagnostics do not establish the cause of the historical main-replay difference. The source fixes above have separate deterministic loader and output-parity evidence. Raw traces remain private.


</details>

## Screenshots / Video

No layout/control redesign. Runtime observations above retain the failed ac39 focus check, successful timed 84 follow-up and unfinished Sports checks. Historical UI observations remain labelled. No fixture stream was activated.

## Breaking changes

No additional database schema or external API change relative to normalized upstream `67570ddd`. That base introduced `livetv_guide2.db` and rebuilds its derived guide from saved source files; this integration preserves that upstream behavior. Internal consumers use explicit disk readiness and committed revisions instead of treating resident viewport data as the complete guide.

## Linked issues

None supplied; approval is not claimed. Keep this PR in draft and on RAM hold until the current validation and contribution-policy requirements are resolved.


### Final test cleanup

The latest verified Search-fix APK is retained. Original app data was restored with the complete **643-file / 19-directory** inventory verified, and the app is stopped. The owned observer was uninstalled; the owned fixture listener, loopback reverse and completed build daemon were removed/stopped. No other apps or devices were changed. This restores the original playlists/settings rather than leaving the large benchmark fixtures selected, and does not turn test-channel/emulator evidence into a physical-device or authenticated-provider result.
