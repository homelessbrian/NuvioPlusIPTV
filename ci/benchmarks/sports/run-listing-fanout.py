#!/usr/bin/env python3
"""Offline selected-source/native-SQLite Sports checks and fresh-VM A/B component trials.

No Gradle, Android device, app lifecycle, network, explicit GC, or source instrumentation.
The host JVM's caller allocation counter excludes native SQLite and other-thread allocations.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


MAIN_SOURCES = [
    "data/EpgDatabase.kt", "data/EpgSearch.kt", "data/SqliteCompact.kt", "data/XmltvParser.kt",
    "data/CatchupUrlBuilder.kt", "data/LiveTvPosterResolver.kt", "model/LiveTvModels.kt",
    "sports/SportsMatcher.kt", "sports/SportsModels.kt", "sports/SportsCatalog.kt",
    "sports/SportsMatchWork.kt", "parental/ParentalControls.kt",
]
BENCHMARK = "ci/benchmarks/sports/SportsListingFanoutBenchmark.kt"
COMMON_TEST = "ci/benchmarks/guide/tests/SportsMatcherNormalizedCatalogueTest.kt"
AFTER_TEST = "ci/benchmarks/guide/tests/SportsListingGroupsTest.kt"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--worktree", required=True, type=Path)
    p.add_argument("--before-ref", default="302d4e07108c95c4b9b3f28e0ba1776d4c74e05c")
    p.add_argument("--out", required=True, type=Path, help="Fresh output directory; never overwritten")
    p.add_argument("--phase", choices=["tests", "benchmark", "all"], default="tests")
    p.add_argument("--shapes", nargs="+", choices=["primary", "shared"], default=["primary", "shared"])
    p.add_argument("--modes", nargs="+", choices=["stable-guide", "test-table"], default=["stable-guide", "test-table"])
    p.add_argument("--primary-trials", type=int, default=3)
    p.add_argument("--shared-trials", type=int, default=1)
    p.add_argument("--process-timeout", type=int, default=900)
    p.add_argument("--java-home", required=True, type=Path)
    p.add_argument("--cached-app-classes", required=True, type=Path)
    p.add_argument("--build-config-classes", required=True, type=Path)
    p.add_argument("--android-jar", required=True, type=Path)
    p.add_argument("--execute", action="store_true", help="Explicitly allow host compilation/tests; no device capability exists")
    a = p.parse_args()
    root, out = a.worktree.resolve(), a.out.resolve()
    if out.exists():
        p.error("Output already exists")
    if min(a.primary_trials, a.shared_trials, a.process_timeout) < 1:
        p.error("Positive trial counts and timeout required")
    before = subprocess.run(["git", "rev-parse", "--verify", a.before_ref + "^{commit}"], cwd=root,
                            check=True, capture_output=True, text=True).stdout.strip()
    plan = {"beforeCommit": before, "afterHead": subprocess.run(["git", "rev-parse", "HEAD"], cwd=root,
            check=True, capture_output=True, text=True).stdout.strip(), "phase": a.phase,
            "shapes": a.shapes, "modes": a.modes, "primaryTrials": a.primary_trials, "sharedTrials": a.shared_trials,
            "corpus": "sports-fanout-v1-real-game (new deterministic corpus, not the APK RAM fixture bytes)",
            "method": "actual-selected-Kotlin-source-native-SQLite-Robolectric-fresh-host-VM",
            "scope": "component timing/caller allocations, not app RAM, device latency or provider validation",
            "explicitGc": False, "normalizerInstrumentation": False}
    if not a.execute:
        print(json.dumps(plan, indent=2))
        return
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    android_dir = Path.home() / ".m2/repository/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7"
    java = a.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    required = [java, a.cached_app_classes, a.build_config_classes / "com/nuvio/tv/BuildConfig.class",
                a.android_jar, android_dir / "android-all-instrumented-14-robolectric-10818077-i7.jar"]
    for path in required:
        if not path.exists():
            raise FileNotFoundError(path)
    out.mkdir(parents=True, exist_ok=False)
    (out / "plan.json").write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")

    def artifact(group, name, version, suffix="jar"):
        files = sorted((cache / group / name / version).glob("*/*." + suffix))
        if not files:
            raise FileNotFoundError(f"Cached {group}:{name}:{version}")
        return files[0]

    def version_key(value):
        return tuple((0, int(x)) if x.isdigit() else (1, x) for x in re.split(r"[.\-]", value))

    jars = []
    for group in cache.iterdir():
        if not group.is_dir():
            continue
        for name in group.iterdir():
            versions = [v for v in name.iterdir() if v.is_dir() and list(v.glob("*/*.jar"))]
            if not versions:
                continue
            if group.name == "org.robolectric" and name.name != "nativeruntime-dist-compat":
                chosen = name / "4.17"
            elif group.name == "org.jetbrains.kotlin" and (name / "2.3.0").is_dir():
                chosen = name / "2.3.0"
            else:
                chosen = max(versions, key=lambda v: version_key(v.name))
            jars.extend(chosen.glob("*/*.jar"))
    for group, name, version in [("androidx.core", "core", "1.16.0"), ("com.google.dagger", "hilt-android", "2.58"),
                                  ("androidx.test", "monitor", "1.8.0"), ("androidx.test.espresso", "espresso-idling-resource", "3.7.0")]:
        target = out / f"{name}-{version}.jar"
        with zipfile.ZipFile(artifact(group, name, version, "aar")) as archive:
            target.write_bytes(archive.read("classes.jar"))
        jars.append(target)
    cp = os.pathsep.join(map(str, [a.android_jar, a.build_config_classes, a.cached_app_classes] + sorted(set(jars))))
    compiler = [artifact("org.jetbrains.kotlin", n, v) for n, v in [
        ("kotlin-compiler-embeddable", "2.3.0"), ("kotlin-stdlib", "2.3.0"), ("kotlin-script-runtime", "2.3.0"),
        ("kotlin-reflect", "1.6.10"), ("kotlin-daemon-embeddable", "2.3.0")]]
    compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"), artifact("org.jetbrains", "annotations", "13.0")]
    provenance = {}

    def run_java(directory, name, args):
        argfile = directory / (name + ".args")
        argfile.write_text("\n".join('"' + str(x).replace("\\", "/").replace('"', '\\"') + '"' for x in args), encoding="utf-8")
        with (directory / (name + ".log")).open("w", encoding="utf-8") as log:
            subprocess.run([str(java), "@" + str(argfile)], check=True,
                           stdout=log, stderr=subprocess.STDOUT, text=True, timeout=a.process_timeout)
        return (directory / (name + ".log")).read_text(encoding="utf-8")

    classes = {}
    for role in ("before", "after"):
        directory = out / role
        source_dir, compiled = directory / "source", directory / "classes"
        compiled.mkdir(parents=True)
        sources, hashes = [], {}
        for relative in MAIN_SOURCES:
            name = "app/src/main/java/com/nuvio/tv/livetv/" + relative
            data = subprocess.run(["git", "show", before + ":" + name], cwd=root, check=True, capture_output=True).stdout if role == "before" else (root / name).read_bytes()
            target = source_dir / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            assert target.read_bytes() == data
            hashes[name] = sha(data)
            sources.append(target)
        tests = [COMMON_TEST, BENCHMARK] + ([AFTER_TEST] if role == "after" else [])
        for name in tests:
            data = (root / name).read_bytes()
            target = source_dir / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            hashes[name] = sha(data)
            sources.append(target)
        provenance[role] = hashes
        run_java(directory, "compile", ["-Xmx1g", "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-Xfriend-paths=" + str(a.cached_app_classes), "-classpath", cp, "-d", compiled, *sources])
        classes[role] = (directory, compiled)
    (out / "source-sha256.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")

    def invocation(role, name, test, props=()):
        directory, compiled = classes[role]
        return run_java(directory, name, ["-Xmx1g", "-Drobolectric.offline=true",
            "-Drobolectric.dependency.dir=" + str(android_dir), *props, "-cp", str(compiled) + os.pathsep + cp,
            "org.junit.runner.JUnitCore", test])

    if a.phase in ("tests", "all"):
        for role in ("before", "after"):
            invocation(role, "catalogue-test", "com.nuvio.tv.livetv.sports.SportsMatcherNormalizedCatalogueTest")
        invocation("after", "grouped-tests", "com.nuvio.tv.livetv.sports.SportsListingGroupsTest")
    records = []
    if a.phase in ("benchmark", "all"):
        for shape in a.shapes:
            trials = a.primary_trials if shape == "primary" else a.shared_trials
            for mode in a.modes:
                for trial in range(1, trials + 1):
                    for role in (("before", "after") if trial % 2 else ("after", "before")):
                        text = invocation(role, f"{shape}-{mode}-{trial}", "com.nuvio.tv.livetv.sports.SportsListingFanoutBenchmark",
                            [f"-Dnuvio.bench.shape={shape}", "-Dnuvio.bench.trials=1",
                             "-Dnuvio.bench.sportsTable=" + str(mode == "test-table").lower()])
                        found = [json.loads(line.split("sports-listing-fanout: ", 1)[1]) for line in text.splitlines() if "sports-listing-fanout: " in line]
                        if len(found) != 1:
                            raise RuntimeError("Exactly one completed benchmark record required")
                        record = dict(found[0], role=role, pairTrial=trial)
                        records.append(record)
                        with (out / "trials.jsonl").open("a", encoding="utf-8") as file:
                            file.write(json.dumps(record, separators=(",", ":")) + "\n")
                        print(json.dumps(record), flush=True)
                mode_rows = [r for r in records if r["shape"] == shape and r["mode"] == mode]
                for field in ("bindings", "storedRows", "logicalRows", "gidShiftGroups", "shiftDistribution", "inputSha256", "outputSha256", "sportsTableSha256", "sportsTableRows", "matchedChannels", "guideEvents"):
                    if len({r[field] for r in mode_rows}) != 1:
                        raise RuntimeError(f"A/B {shape}/{mode} parity failed for {field}")
            # Matching and guide-event output must agree across stable GUIDE and table modes.
            for field in ("inputSha256", "outputSha256", "matchedChannels", "guideEvents"):
                if len({r[field] for r in records if r["shape"] == shape}) != 1:
                    raise RuntimeError(f"Cross-mode {shape} parity failed for {field}")
    (out / "complete.json").write_text(json.dumps({"complete": True, "benchmarkRecords": len(records), "plan": plan}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
