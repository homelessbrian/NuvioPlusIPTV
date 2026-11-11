#!/usr/bin/env python3
"""Read-only, offline replay of the matched observer's exact 32-column CSV files.

Usage: python summarize-observer-csv.py --record-dir /path/to/public/records

The directory must contain manifest.json with version 1 (or no version), and:
  {"trials": [{"file": "baseline-1.csv", "role": "baseline", "case": "primary",
    "label": "baseline-1", "sha256": "<64 hexadecimal digits>",
    "main_launch_requested_elapsed_ns": 123,
    "browse_begin_elapsed_ns": null, "browse_end_elapsed_ns": null}, ...]}
Optional top-level cases is ["primary"] for exactly six primary trials, or
["primary", "shared"] for all eight. Omitting cases requires all eight, preserving
the original strict default. Order is primary baseline/candidate repeated three
times, then shared baseline/candidate once when declared. Declaring both cases
never permits missing shared trials. Only the third primary pair has browse
markers by default; browse_policy="all-primary" requires all three primary pairs. Origins must be positive and strictly increase in that order. Optional top-level
expected_runtime_max_bytes checks the observed cap; optional source_pins is copied
verbatim to the report, never inferred. File paths must remain within record-dir.

Windows use sample START times and half-open intervals: startup [90,120) seconds
after the in-process main launch; sampled startup maxima [0,120); browse maxima
[begin,end); settled [30,60) seconds after browse end. Each applicable measurement
window requires at least 20 complete rows. Prelaunch samples, cadence gaps,
GC-coincident rows and outliers remain in the input and are reported. No device,
network, application lifecycle, explicit GC, retries or writes exist in this tool.

Runtime/native heap units are bytes; PSS/swap units are KiB. Raw total PSS includes
derived SwapPss; resident PSS is the Dalvik/native/other component sum. swap_total
is separately reported and is never added to resident PSS. Aliases in the output:
debug_memory_total_pss_kib -> raw_total_pss_kib, swap_pss_derived_kib ->
derived_swap_pss_kib. Sampled maxima are not continuous or retained-heap peaks.

Successful replay establishes numeric consistency for the DECLARED cases only;
it does not establish completion of the whole originally planned study. A primary-
only record can stand alone when a planned shared setup failed before measurement,
provided the companion failure/unmeasured-case provenance is preserved separately.
The tool does not authenticate
the manifest, prove lifecycle/fixture/input parity, recover missing samples, prove
absence of GC requests, or establish physical-device behavior. Those require the
separate provenance and capture evidence. Invalid evidence fails without dropping
rows. JSON is printed to stdout; exit status is nonzero on any validation error.
"""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import statistics
import sys


HEADER = (
    "sample", "elapsed_realtime_ns", "uptime_ms", "pid", "uid",
    "runtime_total_bytes", "runtime_free_bytes", "runtime_max_bytes", "runtime_used_bytes",
    "debug_memory_total_pss_kib", "resident_component_pss_kib", "swap_pss_derived_kib",
    "swap_total_kib", "has_swapped_out_pss_hidden", "dalvik_pss_kib", "native_pss_kib",
    "other_pss_kib", "native_heap_allocated_bytes", "native_heap_size_bytes",
    "native_heap_free_bytes", "gc_count_before", "gc_blocking_count_before",
    "gc_time_ms_before", "gc_blocking_time_ms_before", "gc_count_after",
    "gc_blocking_count_after", "gc_time_ms_after", "gc_blocking_time_ms_after",
    "sample_end_elapsed_ns", "sample_duration_ns", "since_oncreate_ns",
    "main_launch_requested_elapsed_ns",
)
ALIASES = {"debug_memory_total_pss_kib": "raw_total_pss_kib",
           "swap_pss_derived_kib": "derived_swap_pss_kib"}
METRICS = (
    "runtime_used_bytes", "runtime_total_bytes", "runtime_free_bytes", "runtime_max_bytes",
    "resident_component_pss_kib", "derived_swap_pss_kib", "raw_total_pss_kib",
    "swap_total_kib", "dalvik_pss_kib", "native_pss_kib", "other_pss_kib",
    "native_heap_allocated_bytes", "native_heap_size_bytes", "native_heap_free_bytes",
)
GC = ("gc_count", "gc_blocking_count", "gc_time_ms", "gc_blocking_time_ms")
NS = 1_000_000_000
EXPECTED_ORDER = [("primary", role) for _ in range(3) for role in ("baseline", "candidate")]
EXPECTED_ORDER += [("shared", "baseline"), ("shared", "candidate")]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive_int(value):
    return type(value) is int and value > 0


def stats(values):
    return {"samples": len(values), "median": statistics.median(values) if values else None,
            "minimum": min(values) if values else None, "maximum": max(values) if values else None}


def window(rows, minimum=20):
    return {"samples": len(rows), "complete_samples": len(rows),
            "minimum_required": minimum, "valid": minimum is None or len(rows) >= minimum,
            "metrics": {key: stats([r[key] for r in rows]) for key in METRICS},
            "gc_changed_during_sample": sum(r["gc_count_after"] != r["gc_count_before"] for r in rows),
            "gc_count_before_range": stats([r["gc_count_before"] for r in rows]),
            "gc_count_after_range": stats([r["gc_count_after"] for r in rows])}


def read_trial(directory, entry, expected_cap):
    require(isinstance(entry, dict), "Trial must be an object")
    file = entry.get("file")
    require(isinstance(file, str) and file and not Path(file).is_absolute(), "Relative trial file required")
    path = (directory / file).resolve()
    require(directory in path.parents, "Trial file escapes record directory")
    digest = entry.get("sha256")
    require(isinstance(digest, str) and re.fullmatch(r"[0-9a-fA-F]{64}", digest), "Trial SHA-256 required")
    data = path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == digest.lower(), "Trial CSV SHA-256 mismatch")
    require(data.endswith(b"\n"), "CSV final row is incomplete")
    reader = csv.reader(io.StringIO(data.decode("ascii"), newline=""))
    require(tuple(next(reader, ())) == HEADER, "CSV header/order must match exact observer32 schema")
    rows = []
    for line, values in enumerate(reader, 2):
        require(len(values) == len(HEADER), f"CSV line {line}: column count differs")
        require(all(re.fullmatch(r"-?\d+", value) for value in values), f"CSV line {line}: finite integer required")
        row = {ALIASES.get(key, key): int(value) for key, value in zip(HEADER, values)}
        require(all(-(1 << 63) <= value < (1 << 63) for value in row.values()), f"CSV line {line}: signed64 integer range exceeded")
        rows.append(row)
    require(rows, "CSV contains no samples")
    origin = entry.get("main_launch_requested_elapsed_ns")
    require(positive_int(origin), "Positive in-process main launch origin required")
    identities = {(r["pid"], r["uid"]) for r in rows}
    require(len(identities) == 1 and all(v > 0 for v in next(iter(identities))), "PID/UID must be positive and fixed per file")
    caps = {r["runtime_max_bytes"] for r in rows}
    require(len(caps) == 1 and next(iter(caps)) > 0, "Runtime cap must be positive and fixed per file")
    cap = next(iter(caps))
    if expected_cap is not None:
        require(cap == expected_cap, "Observed Runtime cap differs from manifest")
    oncreate = None
    previous = None
    main_seen = False
    gaps = []
    durations = []
    for index, row in enumerate(rows):
        prefix = f"CSV row {index + 1}: "
        at = row["elapsed_realtime_ns"]
        require(at > 0 and row["uptime_ms"] >= 0 and row["sample"] >= 0, prefix + "negative clock or sample")
        require(all(row[key] >= 0 for key in METRICS), prefix + "negative metric")
        require(row["has_swapped_out_pss_hidden"] in (-1, 0, 1), prefix + "unknown swap support flag")
        require(row["runtime_used_bytes"] + row["runtime_free_bytes"] == row["runtime_total_bytes"], prefix + "Runtime arithmetic differs")
        require(row["runtime_total_bytes"] <= cap, prefix + "Runtime total exceeds cap")
        require(sum(row[key] for key in ("dalvik_pss_kib", "native_pss_kib", "other_pss_kib")) == row["resident_component_pss_kib"], prefix + "resident component sum differs")
        require(row["resident_component_pss_kib"] + row["derived_swap_pss_kib"] == row["raw_total_pss_kib"], prefix + "resident plus derived swap differs from raw total")
        require(row["sample_end_elapsed_ns"] - at == row["sample_duration_ns"] >= 0, prefix + "sample duration differs")
        require(row["since_oncreate_ns"] >= 0, prefix + "negative since-onCreate clock")
        this_oncreate = at - row["since_oncreate_ns"]
        require(this_oncreate > 0 and (oncreate is None or this_oncreate == oncreate), prefix + "onCreate clock origin changed")
        oncreate = this_oncreate
        stamp = row["main_launch_requested_elapsed_ns"]
        require(stamp in (0, origin), prefix + "main launch origin changed")
        require(not main_seen or stamp == origin, prefix + "main launch origin reverted to zero")
        require(at < origin or stamp == origin, prefix + "main launch origin absent after launch")
        main_seen = main_seen or stamp == origin
        for key in GC:
            before, after = row[key + "_before"], row[key + "_after"]
            require(0 <= before <= after, prefix + key + " interval decreased")
            if previous is not None:
                require(before >= previous[key + "_after"], prefix + key + " decreased for unchanged process")
        if previous is not None:
            require(row["sample"] == previous["sample"] + 1, prefix + "sample sequence has a gap/duplicate")
            require(at > previous["elapsed_realtime_ns"], prefix + "sample clock did not increase")
            gaps.append((at - previous["elapsed_realtime_ns"]) / NS)
        durations.append(row["sample_duration_ns"])
        previous = row
    require(main_seen, "CSV never records manifest main launch origin")
    require(oncreate <= origin, "Main launch origin precedes observer onCreate")
    prelaunch = [r for r in rows if r["elapsed_realtime_ns"] < origin]
    startup = [r for r in rows if 0 <= r["elapsed_realtime_ns"] - origin < 120 * NS]
    late = [r for r in startup if r["elapsed_realtime_ns"] - origin >= 90 * NS]
    summary = {"prelaunch": window(prelaunch, None), "startup_fixed_90_to_120s": window(late),
               "startup_peak_0_to_120s": {key: max((r[key] for r in startup), default=None) for key in METRICS},
               "startup_sample_count_0_to_120s": len(startup),
               "browse_peak_sampled": None, "post_browse_fixed_30_to_60s": None}
    require(summary["startup_fixed_90_to_120s"]["valid"], "Fewer than20 complete startup90–120s rows")
    begin, end = entry.get("browse_begin_elapsed_ns"), entry.get("browse_end_elapsed_ns")
    require((begin is None) == (end is None), "Both browse markers or neither required")
    if begin is not None:
        require(positive_int(begin) and positive_int(end) and origin + 120 * NS <= begin < end, "Invalid browse marker chronology")
        browse = [r for r in rows if begin <= r["elapsed_realtime_ns"] < end]
        settle = [r for r in rows if end + 30 * NS <= r["elapsed_realtime_ns"] < end + 60 * NS]
        require(len(browse) >= 20, "Fewer than20 complete browse rows")
        summary["browse_sample_count"] = len(browse)
        summary["browse_duration_device_seconds"] = (end - begin) / NS
        summary["browse_peak_sampled"] = {key: max(r[key] for r in browse) for key in METRICS}
        summary["post_browse_fixed_30_to_60s"] = window(settle)
        require(summary["post_browse_fixed_30_to_60s"]["valid"], "Fewer than20 complete settled30–60s rows")
    return {"case": entry["case"], "role": entry["role"], "label": entry["label"],
            "file": file, "sha256": digest.lower(), "rows": len(rows), "all_samples_retained": True,
            "main_launch_requested_elapsed_ns": origin, "observed_runtime_max_bytes": cap,
            "cadence": {"gaps_seconds": stats(gaps), "gaps_over_two_seconds": sum(x > 2 for x in gaps),
                        "gaps_over_five_seconds": sum(x > 5 for x in gaps),
                        "sample_duration_ns": stats(durations)},
            "swap_pss_hidden_flag_values": {str(flag): sum(r["has_swapped_out_pss_hidden"] == flag for r in rows)
                                            for flag in sorted({r["has_swapped_out_pss_hidden"] for r in rows})},
            "gc_counter_intervals": {key: {"first_before": rows[0][key + "_before"],
                "last_after": rows[-1][key + "_after"],
                "delta": rows[-1][key + "_after"] - rows[0][key + "_before"],
                "changed_during_sample": sum(r[key + "_before"] != r[key + "_after"] for r in rows)} for key in GC},
            "summary": summary}


def aggregate(trials, cases=("primary", "shared")):
    groups = {}
    for case in cases:
        groups[case] = {}
        for role in ("baseline", "candidate"):
            selected = [t for t in trials if t["case"] == case and t["role"] == role]
            windows = {}
            for name in ("startup_fixed_90_to_120s", "post_browse_fixed_30_to_60s"):
                reports = [t["summary"][name] for t in selected if t["summary"][name] is not None]
                windows[name] = {"trials": len(reports), "metrics": {key: {
                    "trial_medians": [r["metrics"][key]["median"] for r in reports],
                    "median_of_trial_medians": statistics.median(r["metrics"][key]["median"] for r in reports),
                    "trial_median_range": [min(r["metrics"][key]["median"] for r in reports), max(r["metrics"][key]["median"] for r in reports)],
                    "all_fixed_window_sample_range": [min(r["metrics"][key]["minimum"] for r in reports), max(r["metrics"][key]["maximum"] for r in reports)]
                } for key in METRICS}} if reports else None
            peaks = {name: {key: stats([t["summary"][name][key] for t in selected if t["summary"][name] is not None])
                            for key in METRICS} for name in ("startup_peak_0_to_120s", "browse_peak_sampled")}
            groups[case][role] = {"trials": len(selected), "windows": windows,
                                  "sampled_peak_distribution": peaks}
    return groups


def replay(directory):
    data = (directory / "manifest.json").read_bytes()
    def reject_constant(value):
        raise ValueError("Nonfinite manifest number: " + value)
    manifest = json.loads(data.decode("utf-8"), parse_constant=reject_constant)
    require(isinstance(manifest, dict) and type(manifest.get("version", 1)) is int
            and manifest.get("version", 1) == 1, "Manifest version1 object required")
    cases = manifest.get("cases", ["primary", "shared"])
    browse_policy = manifest.get("browse_policy", "third-primary-pair")
    require(browse_policy in ("third-primary-pair", "all-primary"), "Unknown declared browse policy")
    require(cases in (["primary"], ["primary", "shared"]), "Cases must be ['primary'] or ['primary','shared']")
    expected_order = EXPECTED_ORDER[:6] if cases == ["primary"] else EXPECTED_ORDER
    entries = manifest.get("trials")
    require(isinstance(entries, list) and len(entries) == len(expected_order), "Exactly six primary trials, plus two shared when declared, required")
    require(all(isinstance(t, dict) for t in entries), "Trial entries must be objects")
    require([(t.get("case"), t.get("role")) for t in entries] == expected_order, "Full declared primary B/A×3 then shared B/A order required")
    labels, files = set(), set()
    for index, entry in enumerate(entries):
        label = entry.get("label")
        require(isinstance(label, str) and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}", label), "Safe nonempty trial label required")
        identity = (entry["case"], label)
        require(identity not in labels, "Duplicated case/label")
        labels.add(identity)
        file = entry.get("file")
        require(isinstance(file, str) and file not in files, "Missing/duplicated trial file")
        files.add(file)
        require("browse_begin_elapsed_ns" in entry and "browse_end_elapsed_ns" in entry, "Explicit null/value browse markers required")
        expected_browse = index < 6 if browse_policy == "all-primary" else index in (4, 5)
        require((entry["browse_begin_elapsed_ns"] is not None) == expected_browse, "Browse markers differ from declared primary policy")
    expected_cap = manifest.get("expected_runtime_max_bytes")
    require(expected_cap is None or positive_int(expected_cap), "Expected cap must be a positive integer")
    trials = [read_trial(directory, entry, expected_cap) for entry in entries]
    origins = [t["main_launch_requested_elapsed_ns"] for t in trials]
    require(all(a < b for a, b in zip(origins, origins[1:])), "Main launch clocks do not follow full alternating order")
    require(len({t["observed_runtime_max_bytes"] for t in trials}) == 1, "Runtime cap differs across matched trials")
    return {"complete": True, "completion_scope": "numeric-replay-of-declared-cases-only", "cases": cases,
            "whole_original_study_completion_established": False, "browse_policy": browse_policy,
            "manifest_sha256": hashlib.sha256(data).hexdigest(),
            "source_pins": manifest.get("source_pins"), "trials": trials, "groups": aggregate(trials, cases),
            "units": {"runtime_and_native_heap": "bytes", "pss_and_swap": "KiB", "origin": "device elapsed realtime ns"},
            "all_samples_retained": True, "explicit_gc_requested_by_replay": False,
            "limits": ["Matched instrumentation has observer overhead; replay does not establish uninstrumented behavior.",
                       "Manifest, capture/input parity and lifecycle evidence require independent review.",
                       "Declared-case numeric completion does not establish completion of the original planned study; preserve companion failure/unmeasured-case provenance.",
                       "All prelaunch, gap, ordinary-GC and outlier samples retained; no filtering or retries.",
                       "Sampled maxima are not continuous peaks or retained heap; browse repetitions follow the declared policy and shared remains one pair.",
                       "No physical SHIELD or long-session acceptance inferred."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--record-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = replay(args.record_dir.resolve())
    except (OSError, ValueError, KeyError, TypeError, UnicodeError, csv.Error) as error:
        print(json.dumps({"complete": False, "error": str(error)}, indent=2, allow_nan=False))
        return 1
    print(json.dumps(report, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
