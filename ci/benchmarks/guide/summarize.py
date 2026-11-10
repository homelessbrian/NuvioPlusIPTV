#!/usr/bin/env python3
"""Summarize completed samples; never turn a process timeout into a query duration."""

import argparse
import csv
import json
import math
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    rows = []
    for name in ("trials.jsonl", "variant-16000.jsonl"):
        file = directory / name
        if file.exists():
            for line in file.read_text(encoding="utf-8-sig").splitlines():
                if line.strip():
                    row = json.loads(line)
                    row["source"] = name
                    rows.append(row)
    if not rows:
        raise ValueError("No samples")
    completed = [row for row in rows if row.get("status", "complete") == "complete"]
    timeouts = [row for row in rows if row.get("status") == "timeout"]
    groups = {}
    for row in completed:
        assert row["neverBrowsedLastChannelPrograms"] == 2
        if row["mode"] == "windowed":
            assert row["cacheHitQueries"] == row["cacheHitDecoded"] == 0
        if "outputMatchesExpected" in row:
            assert row["outputMatchesExpected"]
            assert row["actualRowsOnDisk"] == row["rowsOnDisk"]
        key = (row["channels"], row.get("variant", row["mode"]))
        groups.setdefault(key, []).append(row)

    metrics = ("readDecodeMergeMs", "decodedPrograms", "displayRetainedPrograms", "ownerRetainedPrograms",
               "heapDeltaBytes", "pssKiB", "pssBeforeKiB", "visitedRows", "queries", "javaMaxHeapBytes")
    summary = {"scope": "Android SQLite query/Java light-programme decoding and retention reproduction, not whole-app or key-to-frame latency",
               "p95Method": "Nearest rank; with n=5 this is the observed maximum. No p95 for n<5.",
               "completedSamples": len(completed), "timeoutSamples": timeouts, "groups": []}
    for (channels, label), samples in sorted(groups.items()):
        group = {"channels": channels, "configuration": label, "n": len(samples), "metrics": {}}
        for metric in metrics:
            values = sorted(row[metric] for row in samples)
            group["metrics"][metric] = {
                "observed": values[0] if len(values) == 1 else None,
                "median": statistics.median(values) if len(values) > 1 else None,
                "p95": values[math.ceil(.95 * len(values)) - 1] if len(values) >= 5 else None,
                "min": values[0], "max": values[-1]}
        summary["groups"].append(group)
    variants = [row for row in completed if row.get("variant")]
    if variants:
        assert len({row["displayChecksum"] for row in variants}) == 1
        summary["controlledOutputChecksum"] = variants[0]["displayChecksum"]
    hashes = sorted(directory.glob("database*before.sha256"))
    for before in hashes:
        after = directory / before.name.replace("before", "after")
        if after.exists():
            a, b = before.read_text().split()[0], after.read_text().split()[0]
            assert a == b, "Database changed"
            summary.setdefault("unchangedDatabaseHashes", {})[before.name] = a
    cleanup = directory / "device-cleanup.txt"
    if cleanup.exists():
        summary["deviceCleanup"] = cleanup.read_text(encoding="utf-8-sig").strip()
    (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    columns = ("channels", "mode", "variant", "trial", "status", "readDecodeMergeMs", "decodedPrograms",
               "displayRetainedPrograms", "ownerRetainedPrograms", "heapDeltaBytes", "pssKiB", "pssBeforeKiB",
               "visitedRows", "queries", "cacheHitQueries", "cacheHitDecoded", "javaMaxHeapBytes", "processWallSeconds",
               "processBudgetSeconds", "actualElapsedSeconds", "cursorConfiguration", "outputMatchesExpected",
               "actualRowsOnDisk", "rowsOnDisk", "neverBrowsedLastChannelPrograms", "source")
    with (directory / "measurements.csv").open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    print(f"{len(completed)} completed samples; {len(timeouts)} explicit process timeouts")
    for group in summary["groups"]:
        stats = group["metrics"]["readDecodeMergeMs"]
        print(group["channels"], group["configuration"], "n=", group["n"], stats)


if __name__ == "__main__":
    main()
