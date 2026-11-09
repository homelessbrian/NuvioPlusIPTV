#!/usr/bin/env python3
"""Package complete source for the separate OpenVPN 2 executable, from its build cache.

No downloads, credentials, private fixtures, host build products or app data enter
this archive. Run after the native build has populated its pinned source cache.
"""
# SPDX-License-Identifier: GPL-2.0-only
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile


PINS = {
    "openvpn2": ("OpenVPN/openvpn", "7b60c2aa34992639691e3a2d651fe27301e2e9d5"),
    "android2": ("schwabe/ics-openvpn", "f669267e2547fddca04d0bc699739137085eb633"),
    "openssl": ("schwabe/platform_external_openssl", "b280e0c0c81ce6f8d5291c1f8efb6712c357c28a"),
    "lz4": ("lz4/lz4", "5ff839680134437dbf4678f3d0c7b371d84f4964"),
}
ENGINE_FILES = ("CMakeLists.txt", "launcher.c", "patch-engine.cmake", "README.md")
NOTICE_FILES = ("OpenVPN-COPYING.txt", "OpenSSL-LICENSE.txt", "LZ4-LICENSE.txt", "LZO-COPYING.txt", "sources.txt")
ARCHIVE_ROOT = "nuvio-openvpn2-source"


class SourceError(Exception):
    pass


def git(*arguments, capture=True):
    # These commands access only local repositories. No network/credential helper
    # or repository hook is necessary to construct corresponding source.
    command = ["git", "-c", "protocol.allow=never", "-c", "protocol.file.allow=always",
               "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
               "-c", "core.autocrlf=false", "-c", "init.templateDir=", "-c", "pack.threads=1"]
    result = subprocess.run(command + list(arguments), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        # Git diagnostics can include local paths or configuration: do not publish them.
        raise SourceError("A local Git source verification/copy operation failed.")
    return result.stdout.decode("utf-8", errors="strict").strip() if capture else None


def verify_recipe(module):
    recipe = (module / "openvpn2/CMakeLists.txt").read_text(encoding="utf-8")
    expected = {
        "OPENVPN2_REVISION": PINS["openvpn2"][1],
        "OPENVPN2_ANDROID_REVISION": PINS["android2"][1],
    }
    for variable, revision in expected.items():
        if not re.search(r"set\(" + variable + r"\s+" + revision + r"\s*\)", recipe):
            raise SourceError("Source pins changed: update and review the corresponding-source packager.")
    for dependency in ("openssl", "lz4"):
        if '"' + dependency + ";" + PINS[dependency][1] + '"' not in recipe:
            raise SourceError("Dependency pins changed: update and review the source packager.")


def verify_checkout(source, revision, label):
    if not source.is_dir() or not (source / ".git").exists():
        raise SourceError("Missing pinned source cache: " + label + ". Run the native build first.")
    if git("-C", str(source), "rev-parse", "HEAD") != revision:
        raise SourceError("Pinned source revision mismatch: " + label + ".")
    if git("-C", str(source), "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none"):
        raise SourceError("Source cache has local changes: " + label + ". Refusing an incomplete source archive.")


def clone_source(source, destination, repository, revision):
    destination.parent.mkdir(parents=True, exist_ok=True)
    # --no-local avoids alternates/hardlinks and produces a self-contained shallow
    # object database. Submodules are copied independently below, never fetched.
    git("clone", "--no-local", "--depth=1", "--no-tags", "--no-checkout",
        str(source), str(destination), capture=False)
    git("-C", str(destination), "checkout", "--detach", revision, capture=False)
    metadata = destination / ".git"
    if not metadata.is_dir() or (metadata / "objects/info/alternates").exists():
        raise SourceError("Source copy is not a standalone Git repository.")
    # Reflogs and clone config can contain local paths/user identities. The source
    # archive needs objects and the exact HEAD, with only public provenance URLs.
    for name in ("logs", "hooks", "info", "refs"):
        path = metadata / name
        if path.exists():
            shutil.rmtree(path)
    for name in ("FETCH_HEAD", "ORIG_HEAD", "description", "packed-refs", "index"):
        (metadata / name).unlink(missing_ok=True)
    (metadata / "refs").mkdir()
    (metadata / "HEAD").write_text(revision + "\n", encoding="ascii")
    (metadata / "config").write_text(
        "[core]\n\trepositoryformatversion = 0\n\tbare = false\n\tfilemode = true\n"
        "\tlogallrefupdates = false\n[remote \"origin\"]\n\turl = https://github.com/"
        + repository + ".git\n", encoding="ascii")
    # A fresh index has no original workspace stat metadata and keeps git status
    # usable for the exact revision checks in the included build recipe.
    git("-C", str(destination), "read-tree", "HEAD", capture=False)
    if git("-C", str(destination), "rev-parse", "HEAD") != revision:
        raise SourceError("Source copy did not retain the pinned revision.")


def copy_required(source, destination):
    if source.is_symlink() or not source.is_file():
        raise SourceError("A required engine source/build/notice file is missing or is a symbolic link.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(0o755 if source.name.endswith(".py") else 0o644)


def write_support(root):
    module = root / "vpn-android"
    (module / "CMakeLists.txt").write_text(
        "# SPDX-License-Identifier: GPL-2.0-only\ncmake_minimum_required(VERSION 3.22)\n"
        "project(NuvioOpenVPN2CorrespondingSource LANGUAGES C CXX)\nadd_subdirectory(openvpn2)\n",
        encoding="utf-8")
    (root / "README.md").write_text("""# OpenVPN 2 complete corresponding source

This archive accompanies Nuvio's separate OpenVPN 2 compatibility executable.
It includes the exact engine, Android build support and OpenSSL/LZO/LZ4 sources,
the local launcher and source overlays, build recipes, and dependency notices.
`SOURCE-MANIFEST.json` records revisions and SHA-256 values. The included shallow
Git object databases retain the pinned commits needed by the build recipe; they
contain only public origin URLs and no credentials, reflogs or local source paths.
Only OpenSSL and LZ4 submodules are used from Android build support. Other
uninitialized submodules are unrelated components and are not needed to build
this executable. LZO source is included directly in the Android support tree.

## Build without downloading source

Install CMake 3.22.1, an ordinary C/C++ build tool (Ninja or Make), Perl, Git, and
Android NDK 29.0.14206865. These are standard external build tools, not parts of
the executable. Set ANDROID_NDK_HOME to the installed NDK and run from this folder:

```sh
cmake -S vpn-android -B out/arm64-v8a \\
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \\
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \\
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build out/arm64-v8a --parallel 1 \\
  --target nuvio-openvpn2 nuvio-openvpn2-engine
```

Use `armeabi-v7a`, `x86`, or `x86_64` for the other ABI builds. Both
`libnuvio-openvpn2.so` (PIE launcher) and `libnuvio-openvpn2-engine.so` must be
packaged and extracted into Android's read-only native library directory.
This standalone recipe builds the compatibility executable only. The host app
and its other VPN engines are maintained in their respective source repositories.

The OpenVPN 2 executable and local launcher/overlays are GPL-2.0-only, retaining
the upstream linking exceptions. Each dependency retains its own license. See
`vpn-android/src/main/assets/vpn-licenses/openvpn2` and the source-tree notices.
This source archive must accompany binary distribution; public source URLs alone
are not substituted for the corresponding source of the distributed executable.
""", encoding="utf-8")


def manifest(root):
    files = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if ".git" in relative.parts or path.is_dir():
            continue
        if path.is_symlink():
            files[relative.as_posix()] = {"symlink": os.readlink(path)}
        else:
            files[relative.as_posix()] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    (root / "SOURCE-MANIFEST.json").write_text(json.dumps({
        "format": 1,
        "components": {name: {"repository": "https://github.com/" + repository + ".git", "revision": revision}
                       for name, (repository, revision) in PINS.items()},
        "build": {"ndk": "29.0.14206865", "cmake": "3.22.1", "min_android_api": 24},
        "files": files,
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def archive(root, destination):
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "0"))
    if not 0 <= epoch <= 0xffffffff:
        raise SourceError("SOURCE_DATE_EPOCH must fit the gzip timestamp range.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=destination.name + ".", suffix=".tmp", dir=destination.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output, gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=epoch) as zipped:
            with tarfile.open(fileobj=zipped, mode="w", format=tarfile.PAX_FORMAT) as tar:
                for path in [root] + sorted(root.rglob("*")):
                    if path.is_symlink() and not path.resolve().is_relative_to(root.resolve()):
                        raise SourceError("Source tree contains a symbolic link outside the archive.")
                    entry = tar.gettarinfo(str(path), arcname=str(Path(ARCHIVE_ROOT) / path.relative_to(root)))
                    entry.uid = entry.gid = 0
                    entry.uname = entry.gname = ""
                    entry.mtime = epoch
                    entry.pax_headers = {}
                    if entry.isfile():
                        with path.open("rb") as source:
                            tar.addfile(entry, source)
                    else:
                        tar.addfile(entry)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    module = Path(__file__).resolve().parents[1]
    parser.add_argument("--cache", type=Path, default=module / "build/openvpn2-sources")
    parser.add_argument("--output", type=Path, default=module / "build/openvpn2-corresponding-source.tar.gz")
    args = parser.parse_args()
    verify_recipe(module)
    cache = args.cache.resolve()
    sources = {name: cache / (name + "-" + PINS[name][1]) for name in ("openvpn2", "android2")}
    sources["openssl"] = sources["android2"] / "main/src/main/cpp/openssl"
    sources["lz4"] = sources["android2"] / "main/src/main/cpp/lz4"
    for name, source in sources.items():
        verify_checkout(source, PINS[name][1], name)
    with tempfile.TemporaryDirectory(prefix="nuvio-openvpn2-source-") as scratch:
        root = Path(scratch) / ARCHIVE_ROOT
        target_module = root / "vpn-android"
        target_cache = target_module / "build/openvpn2-sources"
        for name in ("openvpn2", "android2"):
            repository, revision = PINS[name]
            clone_source(sources[name], target_cache / sources[name].name, repository, revision)
        for name in ("openssl", "lz4"):
            repository, revision = PINS[name]
            clone_source(sources[name], target_cache / sources["android2"].name / "main/src/main/cpp" / name,
                         repository, revision)
        for name in ENGINE_FILES:
            copy_required(module / "openvpn2" / name, target_module / "openvpn2" / name)
        for name in NOTICE_FILES:
            relative = Path("src/main/assets/vpn-licenses/openvpn2") / name
            copy_required(module / relative, target_module / relative)
        copy_required(Path(__file__), target_module / "ci" / Path(__file__).name)
        write_support(root)
        for name in ("openvpn2", "android2"):
            verify_checkout(target_cache / sources[name].name, PINS[name][1], name)
        manifest(root)
        archive(root, args.output.resolve())
    print("Packaged complete OpenVPN 2 source: " + str(args.output.resolve()))


if __name__ == "__main__":
    try:
        main()
    except (SourceError, OSError, ValueError, subprocess.SubprocessError) as error:
        # Explicit SourceErrors contain fixed component names; do not emit raw Git output.
        detail = str(error) if isinstance(error, SourceError) else "Source packaging failed; inspect the local build/cache setup."
        print(detail, file=sys.stderr)
        sys.exit(1)
