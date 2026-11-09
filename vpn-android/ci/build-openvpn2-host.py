#!/usr/bin/env python3
"""Build isolated rootless OpenVPN2 synthetic-test binaries, never Android APKs."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
REVISION = '7b60c2aa34992639691e3a2d651fe27301e2e9d5'

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True, help='Clean OpenVPN 2.7.9 Git checkout')
    p.add_argument('--work', type=Path, required=True, help='Separate disposable build output directory')
    p.add_argument('--overlays', type=Path, required=True, help='vpn-android/openvpn2/patch-engine.cmake')
    p.add_argument('--cmake', default=shutil.which('cmake'))
    p.add_argument('--ninja', default=shutil.which('ninja'))
    p.add_argument('--jobs', type=int, default=1)
    p.add_argument('--launcher', type=Path, help='Defaults to launcher.c beside --overlays')
    args = p.parse_args()
    if not args.cmake or not args.ninja:
        p.error('Supply --cmake and --ninja, or install them on PATH')
    if args.jobs < 1 or args.jobs > 2:
        p.error('--jobs must be 1 or 2')
    got = subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', 'HEAD'], text=True).strip()
    if got != REVISION:
        sys.exit('Source revision mismatch')
    if subprocess.check_output(['git', '-C', str(args.source), 'status', '--porcelain'], text=True).strip():
        sys.exit('Source checkout must be clean')
    work = args.work.resolve()
    if work.is_relative_to(args.source.resolve()):
        sys.exit('Build outputs must be outside the upstream checkout')
    work.mkdir(parents=True, exist_ok=True)
    source = work / 'host-source'
    build = work / 'host-build'
    overlay = work / 'security-overlay'
    if source.exists():
        shutil.rmtree(source)
    shutil.copytree(args.source, source, ignore=shutil.ignore_patterns('.git', 'build', 'out'))
    cm = source / 'CMakeLists.txt'
    text = cm.read_text()
    old = '    if (${CMAKE_SYSTEM_NAME} STREQUAL "Linux")\n        pkg_search_module(libcapng REQUIRED libcap-ng IMPORTED_TARGET)'
    new = '    if (${CMAKE_SYSTEM_NAME} STREQUAL "Linux" AND NOT FAKE_ANDROID)\n        pkg_search_module(libcapng REQUIRED libcap-ng IMPORTED_TARGET)'
    if text.count(old) != 1:
        sys.exit('Unused host-dependency patch no longer applies uniquely')
    cm.write_text(text.replace(old, new))

    def command(argv, log_name):
        with (work / log_name).open('w') as log:
            r = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT)
        if r.returncode:
            sys.exit('Host build command failed; see ' + str(work / log_name))
    command([args.cmake, '-DOPENVPN2_SOURCE_DIR=' + str(args.source.resolve()), '-DOPENVPN2_OVERLAY_DIR=' + str(overlay), '-P', str(args.overlays.resolve())], 'overlay.log')
    for file in overlay.glob('*.c'):
        shutil.copy2(file, source / 'src/openvpn' / file.name)
    command([args.cmake, '-S', str(source), '-B', str(build), '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + args.ninja, '-DUNSUPPORTED_BUILDS=ON', '-DFAKE_ANDROID=ON', '-DBUILD_TESTING=OFF', '-DENABLE_LZO=OFF', '-DENABLE_LZ4=OFF', '-DENABLE_PKCS11=OFF', '-DUSE_WERROR=OFF', '-DENABLE_DNS_UPDOWN_BY_DEFAULT=OFF', '-DCMAKE_BUILD_TYPE=Release'], 'host-configure.log')

    def compile_copy(name):
        command([args.cmake, '--build', str(build), '--target', 'openvpn', '--parallel', str(args.jobs)], name + '.log')
        shutil.copy2(build / 'openvpn', work / name)
    compile_copy('engine-client')
    options = source / 'src/openvpn/options.c'
    original_options = options.read_text()
    old = '        msg(M_FATAL, "--mode server not supported on Android");'
    if original_options.count(old) != 1:
        sys.exit('Test-only Android server restriction patch no longer applies')
    options.write_text(original_options.replace(old, '        /* Synthetic host server only: permit server mode for the fixture. */'))
    compile_copy('engine-test-server')
    options.write_text(original_options)
    sock = source / 'src/openvpn/socket.c'
    original_sock = sock.read_text()
    old = '    if (addr_local(addr))'
    if original_sock.count(old) != 1:
        sys.exit('Test-only loopback protection patch no longer applies')
    sock.write_text(original_sock.replace(old, '    if (false && addr_local(addr))'))
    compile_copy('engine-client-force-protect')
    sock.write_text(original_sock)
    compile_copy('engine-client')
    launcher = (args.launcher or args.overlays.with_name('launcher.c')).resolve()
    entry = source / 'src/openvpn/openvpn.c'
    original_entry = entry.read_text()
    point = '            options_postprocess(&c.options, c.es);'
    if original_entry.count(point) != 1:
        sys.exit('Validation entry insertion no longer applies uniquely')
    entry.write_text(original_entry.replace(point, point + '\n            if (getenv("NUVIO_OPENVPN2_VALIDATE_ONLY")) return 0;'))
    original_cmake = cm.read_text()
    cm.write_text(original_cmake + '\nset_source_files_properties(src/openvpn/openvpn.c PROPERTIES COMPILE_DEFINITIONS "main=nuvio_openvpn2_engine_main")\ntarget_sources(openvpn PRIVATE "' + str(launcher) + '")\n')
    compile_copy('engine-launcher')
    entry.write_text(original_entry)
    cm.write_text(original_cmake)
    compile_copy('engine-client')
    metadata = {'source_commit': got, 'security_overlay_sha256': hashlib.sha256(args.overlays.read_bytes()).hexdigest(), 'launcher_sha256': hashlib.sha256(launcher.read_bytes()).hexdigest(), 'security_overlays': str(args.overlays.resolve()), 'binaries': {'client': str(work / 'engine-client'), 'server': str(work / 'engine-test-server'), 'force_protect_client': str(work / 'engine-client-force-protect'), 'launcher_client': str(work / 'engine-launcher')}, 'target_android_management': True, 'variant_limitations': ['Linux executable; not Android ABI', 'system OpenSSL; LZO/LZ4 disabled', 'Unused host Linux cap-ng/netlink dependency detection skipped under FAKE_ANDROID', 'Test server alone removes Android server-mode restriction', 'Force-protect client alone requests Android protection for loopback transports']}
    (work / 'host-build.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata))
if __name__ == '__main__':
    main()
