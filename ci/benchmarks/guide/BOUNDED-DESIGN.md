# Bounded guide browsing

The canonical SQLite guide keeps complete programme metadata and all channel bindings and shifts. Display residency is separate: visiting a small part of the guide does not limit programme Search or Live TV Sports matching.

## Display reads and ownership

- Indexed keyset reads return at most 512 physical rows and 512 KiB of estimated display payload. Titles cap at 512 UTF-16 units, episodes at 64, and artwork URIs longer than 2,048 units use a fallback. Canonical fields are unchanged.
- Channel schedules use pages of at most 256 rows/128 KiB, three pages per requested channel and two requested channels. Date and directional continuation use exact anchors, request serials, displayed-publication receipts and row identity checks.
- One read is active and one latest demand can wait. SQL cancellation and catalogue/guide/revision/source-change checks prevent obsolete reads from being accepted. Bulk progress notifies after 32 accepted tiles and flushes its final partial batch; direct focus progress remains immediate.
- The shared browsing pool permits at most 8,192 charged/reserved model slots and 8 MiB of estimated payload/index/ownership capacity. A grid window also has local limits of 2,048 rows, 2 MiB, 384 tiles, 96 requested keys and six hours. Reservations are admitted before display decoding.
- Cache entries, combined lists, merged rows and publications keep independent paid owners. Eviction releases that owner's handle; an outstanding frame, flow, modal or action can keep the same objects charged.
- Before publication, each fully equal old programme identity is reused within its channel. Whole equal lists are reused too. This keeps the exact objects retained by structural-equality row/Home memos charged by the replacement publication, including an unchanged current/next pair whose later programme changed.
- Menu/dialog selection captures ownership from the event's frame. The identity registry retains committed and pending targets through frame replacement, transfers the same selection from menu to information, and releases dismissed, replaced or never-mounted selections. A channel-menu fallback programme is captured at selection, rather than looked up from a later publication.
- Detached target handles retain the lease and the original repository release callback, without retaining an old publication map. Asynchronous reminder, recording, catch-up, information, Search handoff and Calendar work keeps a fork until job completion, including cancellation before the coroutine body starts. Existing reminder/recording cancellation shortcuts still avoid unnecessary detail loading.
- Each ownership group prepays 64 handles. Optional target/action forks stop at 48 total references, leaving 16 prepaid slots for mandatory frame/flow handoffs. Optional overload fails closed without installing an unpaid projection.

## Separate data and limits

The 8 MiB estimate is not an ART heap, native-memory, graphics or process-memory limit. The current/next overview is separately owned. Focused canonical details use a two-entry/512 KiB cache; oversized details remain usable without being cached. Legacy/API-only full-guide fallback is outside this SQLite display pool.

Global time-offset conversion uses lazy views rather than an eager catalogue copy. Each memo retains at most 96 lists with four cached shifted cells each. The shared budget includes a fixed 2,048-slot/516 KiB offset-capacity reservation. Display page reservations include ownership/index headroom: 580 KiB for a grid decode and 196 KiB for a schedule decode.

Actions hydrate complete canonical fields before persisting a programme title or constructing catch-up requests. Search continues to query the complete eligible stored guide, retaining the existing 200-card global budget and tier/name/quality ordering. Sports matching remains Live TV only; it does not scan VOD. Normal automatic On Demand metadata refresh is a separate startup workload.

Debug counters contain numeric fields only and persist at most every five seconds using an atomic file. They validate registered accounting/reservations, can be stale, and are not an independent census of every UI object or an exact instantaneous memory peak.

## Validation and observed RAM

Source `96f9d911` passed 460 app tests across 53 suites and 153 selected-source checks across 19 classes. These overlap and must not be added. The selected checks include 23 native SQLite cases, 29 Sports cases (including the actual matcher/Search over 26,716 channels and the final eligible channel), and 101 guide/lifecycle cases. See the [sanitized validation record](recorded/2026-11-11/bounded-96f9d911-validation.json).

The [matched emulator comparison](recorded/2026-11-11/bounded-96f9d911/) retains eight trials and every one of its 1,952 numeric rows. Both fresh full-data proofs preserve all ten programme fields, channel bindings and shifts. All three primary browsing pairs have higher managed-heap peaks: median of trial peaks **129.03 -> 139.86 MiB**, worst sampled peak **129.43 -> 146.33 MiB**. Median settled managed memory is **101.73 -> 100.05 MiB**. This does not establish a whole-app RAM improvement; the PR remains on merge hold.

The separate shared-guide startup pair has a lower sampled managed maximum, **125.49 -> 115.68 MiB**, but lower late resident PSS accompanies more swap and raw total PSS rises **495.89 -> 498.18 MiB**. It is one pair. Preserve the full resident/swap/raw-total/native results and uncontrolled host activity when interpreting either case. These checks do not establish physical Android 11 SHIELD playback, long-session stability, actual-phone acceptance, every Search query or every real Sports association.

Earlier adverse cohorts and the [correction to their ownership claims](recorded/2026-11-11/bounded-ownership-correction.md) remain preserved. A passing ownership test and a registered-model cap are distinct from measured whole-app performance.
