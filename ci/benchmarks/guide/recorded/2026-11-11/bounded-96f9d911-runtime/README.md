# Final bounded-guide runtime checks

These are targeted synthetic UI checks after all eight RAM trials and both full-data proofs were frozen. They make no latency or RAM-performance claim. Source/API pins, observation hashes, limits and cleanup evidence are in [runtime-smoke.json](runtime-smoke.json).

Both plain cold roles used the same complete primary saved state and frozen `Team 9 vs Team 20` programme at 18:00 UTC on 2026-11-11, more than four hours ahead during the checks. Each displayed the future Live TV results, retained the first result's focus for at least 35 seconds (37.89/38.13 seconds), and reached focused channel 650 with exactly 20 Right keys. No stream was activated.

Both Sports hubs were initially busy and subsequently observed idle with content inside the 180-second bound (observed by 52.16/51.89 seconds). These are sampled UI bounds, not exact completion times. Backend results differed (172 games/51 leagues versus 169/46); failure totals remain unknown. This does not establish real broadcast/channel associations.

The candidate guide retained selection/details across a roughly twelve-hour jump, a wrap from the first channel to channel 4,000, and further horizontal movement into tomorrow's 03:00-05:00 programme. The Today label was not a clickable datepicker; no separate datepicker or media-dependent Player overlay was tested.

Observation problems remain recorded: neither first hierarchy dump created its expected file, and each was reacquired within the same cold launch. A missing digit in the baseline's initial injected text was detected and corrected to the frozen query before Search assertions. Focus guards stopped navigation/input before the intended screen was ready. These are not RAM reruns or established Search failures. Returning from Search showed an empty group; opening groups and choosing All channels recovered it. The exact baseline state was not compared, so no regression attribution is made.

The three screenshots below contain synthetic fixture data only. They were visually reviewed and copied without edits; moving text/scroll positions differ between captures. They are functional evidence, not an identical-frame appearance comparison.

- [Baseline: focused future Search result, channel 650](baseline-search650.png)
- [Candidate: focused future Search result, channel 650](candidate-search650.png)
- [Candidate: channel 4,000, next-day programme and details](candidate-guide-next-day.png)

The exact original app inventory (643 files/19 directories) was restored and reverified. The tested candidate APK remains enabled/stopped. Only the owned observer, reverse mapping and fixture server were removed; other mappings remain unchanged. No activity was started after restore. Raw app/profile/log/UI XML and recovery material remain private.
