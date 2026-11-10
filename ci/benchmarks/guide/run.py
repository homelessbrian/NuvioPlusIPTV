#!/usr/bin/env python3
"""Run the synthetic guide query benchmark without opening or modifying the app."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device/emulator serial")
    parser.add_argument("--out", required=True, type=Path, help="New output directory; existing directories are refused")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--channels", nargs="+", type=int, choices=(4000, 16000), default=[4000, 16000])
    parser.add_argument("--small-trials", type=int, default=5)
    parser.add_argument("--large-trials", type=int, default=1)
    parser.add_argument("--budget", type=int, default=300, help="Per-process measurement budget in seconds")
    parser.add_argument("--large-budget", type=int, default=300, help="Use 900 to allow a completed long baseline like the recorded run")
    parser.add_argument("--forward-pair", action="store_true", help="Also run a separately labelled API28 forward-only/default pair at 16k")
    args = parser.parse_args()
    if args.sdk is None or args.java_home is None:
        parser.error("Set --sdk/ANDROID_HOME and --java-home/JAVA_HOME")
    if min(args.small_trials, args.large_trials, args.budget, args.large_budget) < 1:
        parser.error("Trial counts and budgets must be positive")

    source = Path(__file__).resolve().parent
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    build = out / "build"
    classes, dex = build / "classes", build / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    suffix = ".exe" if os.name == "nt" else ""
    java = args.java_home / "bin" / ("java" + suffix)
    javac = args.java_home / "bin" / ("javac" + suffix)
    jar = args.java_home / "bin" / ("jar" + suffix)
    adb = args.sdk / "platform-tools" / ("adb" + suffix)
    android = args.sdk / "platforms" / "android-36" / "android.jar"
    d8 = args.sdk / "build-tools" / args.build_tools / "lib" / "d8.jar"
    for tool in (java, javac, jar, adb, android, d8):
        if not tool.is_file():
            raise FileNotFoundError(tool)
    remote = "/data/local/tmp/nuvio-guide-query-bench-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    base_class = "com.nuvio.bench.GuideQueryBenchmark"
    remote_files = [remote + "/benchmark.jar"]
    created = False

    def command(argv, **kwargs):
        return subprocess.run([str(value) for value in argv], check=True, text=True, capture_output=True, **kwargs).stdout.strip()

    def shell(text, **kwargs):
        return command([adb, "-s", args.serial, "shell", text], **kwargs)

    def append(name, record):
        with (out / name).open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, separators=(",", ":")) + "\n")

    def owned_processes():
        found = []
        for line in shell("ps -A -o PID,ARGS").splitlines():
            fields = line.strip().split(None, 1)
            if len(fields) == 2 and fields[0].isdigit() and fields[1].startswith("app_process ") and base_class in fields[1] and remote in fields[1]:
                found.append(fields[0])
        return found

    def stop_owned():
        for pid in owned_processes():
            shell("kill -TERM " + pid)
        for _ in range(25):
            if not owned_processes():
                return
            time.sleep(.2)
        for pid in owned_processes():
            shell("kill -KILL " + pid)
        if owned_processes():
            raise RuntimeError("Owned benchmark process remains; scratch files retained for inspection")

    def measure(mode, database, channels, trial, budget, forward=False, variant=None):
        classname = base_class + ("Forward" if forward else "")
        invocation = f"CLASSPATH={remote}/benchmark.jar app_process -Xmx768m /system/bin {classname} {mode} {database} {channels}"
        started = time.monotonic()
        try:
            output = shell(invocation, timeout=budget)
        except subprocess.TimeoutExpired:
            stop_owned()
            return {"channels": channels, "mode": mode, "trial": trial, "variant": variant,
                    "status": "timeout", "processBudgetSeconds": budget,
                    "actualElapsedSeconds": time.monotonic() - started, "queryOnlyDurationKnown": False}
        rows = [line for line in output.splitlines() if line.startswith("{")]
        if not rows:
            raise RuntimeError("No benchmark JSON: " + output)
        record = json.loads(rows[-1])
        record.update(trial=trial, status="complete", processBudgetSeconds=budget,
                      processWallSeconds=time.monotonic() - started)
        if variant is not None:
            record["variant"] = variant
        return record

    try:
        command([javac, "--release", "8", "-cp", android, "-d", classes,
                 source / "GuideQueryBenchmark.java", source / "GuideQueryBenchmarkForward.java"])
        command([jar, "--create", "--file", build / "classes.jar", "-C", classes, "."])
        command([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "24", "--lib", android,
                 "--output", dex, build / "classes.jar"])
        command([jar, "--create", "--file", build / "benchmark.jar", "-C", dex, "classes.dex"])
        shell("mkdir " + remote)
        created = True
        command([adb, "-s", args.serial, "push", build / "benchmark.jar", remote + "/benchmark.jar"])
        for channels in args.channels:
            disk = shell("df -k /data/local/tmp")
            (out / f"free-before-{channels}.txt").write_text(disk + "\n", encoding="utf-8")
            if int(disk.splitlines()[-1].split()[3]) < 2_200_000:
                raise RuntimeError("Need at least 2.2GB available in device scratch storage")
            database = f"{remote}/guide-{channels}.db"
            names = [database + suffix for suffix in ("", "-wal", "-shm", "-journal")]
            remote_files.extend(names)
            setup = measure("create", database, channels, 0, 900)
            append("setup.jsonl", setup)
            if setup["status"] != "complete":
                raise RuntimeError("Fixture setup did not complete; no measurement can use it")
            before = shell("sha256sum " + database)
            (out / f"database-{channels}-before.sha256").write_text(before + "\n", encoding="utf-8")
            trial_count = args.small_trials if channels == 4000 else args.large_trials
            budget = args.budget if channels == 4000 else args.large_budget
            timed_out = set()
            for trial in range(1, trial_count + 1):
                for mode in (("baseline", "windowed") if trial % 2 else ("windowed", "baseline")):
                    if mode in timed_out:
                        continue  # Do not repeat a failed large measurement indefinitely.
                    record = measure(mode, database, channels, trial, budget)
                    append("trials.jsonl", record)
                    print(json.dumps(record), flush=True)
                    if record["status"] == "timeout":
                        timed_out.add(mode)
            if channels == 16000 and args.forward_pair:
                for forward, label in ((True, "forward_only_api28"), (False, "default_with_checksum")):
                    record = measure("windowed", database, channels, 1, budget, forward, label)
                    append("variant-16000.jsonl", record)
                    print(json.dumps(record), flush=True)
            after = shell("sha256sum " + database)
            (out / f"database-{channels}-after.sha256").write_text(after + "\n", encoding="utf-8")
            if before.split()[0] != after.split()[0]:
                raise RuntimeError("Read-only fixture content changed")
            shell("rm -f " + " ".join(names))
    finally:
        if created:
            stop_owned()
            # All names are generated above, directly beneath this unique owned directory.
            if not re.fullmatch(r"/data/local/tmp/nuvio-guide-query-bench-[A-Za-z0-9_-]+", remote):
                raise RuntimeError("Refusing cleanup of an unexpected directory")
            shell("rm -f " + " ".join(remote_files))
            shell("rmdir " + remote)  # Refuse recursive deletion of unexpected content.
            clean = shell(f"if [ ! -e {remote} ]; then echo CLEAN; else echo REMAINS; fi")
            (out / "device-cleanup.txt").write_text(clean + "\n", encoding="utf-8")
        resolved_build = build.resolve()
        if resolved_build.parent != out or resolved_build.name != "build":
            raise RuntimeError("Refusing cleanup outside the new output directory")
        shutil.rmtree(resolved_build)  # Only this new output directory's generated host build files.
    print("Results:", out)


if __name__ == "__main__":
    main()
