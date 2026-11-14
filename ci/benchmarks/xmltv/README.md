# XMLTV parser CPU/allocation comparison

This host-only benchmark compares the exact parser from `1049818f4cc6435a5390ba6ad8eb406018944633` with the working parser source. It does not measure Android PSS, peak resident RAM, SQLite import, network transfer, or device frame times.

Both versions use the same KXml2 2.3.0 backend and EpgProgram model. The baseline parser body is extracted from git; only its object name and duplicate public result declarations change so the runner can compare actual result objects. No production test hooks or application dependencies are added.

## Recorded run, 2026-11-10

Environment: Windows 11 amd64, Temurin/OpenJDK 21.0.12.1+1-LTS, Kotlin 2.3.0, G1, 1536 MiB maximum heap. Changed XmltvParser SHA256: `CC96A6A7B0F5B0D82350D3AAF8A79A8D75D1EF8C13EFC509EA511915F639DC8C`.

Each engine received three warmup parses of a 512-channel feed. For each measured size, five paired trials alternated baseline/changed order. Every trial, including slower trials, is retained in `recorded-results.txt`. GC is requested before each trial, outside the measured interval. Allocations are cumulative Java bytes allocated on the parsing thread, not live heap or app RAM.

- 4,000 channels / 96,000 programmes / 33,318,039 XML bytes: median 542.712 ms baseline, 464.196 ms changed (14.47% less parsing time). Nearest-rank p95: 587.862 ms / 482.017 ms. Median allocated bytes: 347,823,816 / 173,752,616 (50.05% fewer).
- 16,000 channels / 384,000 programmes / 133,524,039 XML bytes: median 1,971.193 ms baseline, 1,734.307 ms changed (12.02% less parsing time). Nearest-rank p95: 2,204.890 ms / 1,928.297 ms. Median allocated bytes: 1,391,222,088 / 694,934,888 (50.05% fewer).

With five samples, the reported p95 is simply the slowest measured trial; it is not a production latency estimate. Host timing varies with machine load. The allocation reduction should not be presented as a 50% reduction in resident RAM.

All measured trials retained all channels and programmes, with identical output checksums. Separately, 80 full-result fixture/mode comparisons passed for metadata, aliases, offsets, window filtering, shuffled/tied/split channel runs, missing channel declarations, repeated selection predicates, malformed XML recovery, and independent providers reusing an XMLTV id. Five JUnit timestamp/episode tests passed, including 20,000 generated timestamp compatibility inputs. Full-field fixture equality and large-feed checksums are distinct checks.

## Reproduce

From a Windows checkout whose normal Gradle dependency cache contains the project's Kotlin/JUnit tooling:

```powershell
pwsh -File ci/benchmarks/xmltv/run.ps1 -JavaHome '<JDK 21 directory>'
```

Pass `-Baseline <commit>` to compare another revision, `-OutputDirectory <directory>` to change scratch output, or `-CheckOnly` to run the semantic/unit checks without timings. The script compiles only host test support using javac and the cached Kotlin compiler. It downloads the small KXml2 test backend from Maven Central and verifies its pinned SHA256. No Gradle, emulator, app installation, or provider connection is invoked.

The synthetic workload includes four provider prefixes, two channel aliases, 24 half-hour listings per channel, descriptions, Sports categories, subtitles, episode labels and occasional cast/director credits. The production streaming callback is used with an in-memory checksum sink, so database write performance is intentionally excluded.
