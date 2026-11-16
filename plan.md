# Windows desktop port plan

## Goal

Ship a usable Windows x64 Nuvio + IPTV desktop application from this repository, with a repeatable Windows installer build in GitHub Actions. The existing Android application remains the source of truth for product behavior, but its Android-only UI and playback stack cannot run on Windows directly.

## Current state and constraints

- The repository is an Android Gradle application (AndroidX TV Compose, Media3, Android services, Android VPN). There is no desktop target or reusable multiplatform UI module.
- `dwk001/NuvioPlusIPTV` already exists as a fork. Work starts from upstream `dev` on `feat/windows-desktop`.
- Upstream `CONTRIBUTING.md` requires a linked feature request and explicit maintainer approval before a large platform/architecture PR. Prepare the implementation in the fork and request approval before submitting the upstream PR.
- An installer workflow can build only on Windows runners. A successful CI artifact and a Windows install/playback smoke test are release gates; creating a workflow alone is insufficient.

## Delivery phases

### 1. Desktop foundation

- Add an isolated `desktop/` application with its own dependency lockfile and build scripts.
- Implement a keyboard and mouse friendly TV layout, local settings, and a secure boundary between UI and local file/network access.
- Support importing an M3U URL or file, parsing channel groups and TVG metadata, and loading XMLTV from a URL or file.
- Display groups, channels, current/next listings, and search. Persist playlist and guide settings per Windows user.
- Play common HTTP/HLS streams in-app; surface an explicit error for unsupported formats. Do not claim Android playback, DRM, VPN, recording, account sync, or Sports parity until implemented and tested.

### 2. Installer and CI

- Package a Windows x64 installer and publish it as a workflow artifact on desktop changes and manual runs.
- Pin dependencies, keep executable permissions narrow, and document the build and installation path.
- Add release integration only after the installer has passed an actual Windows smoke test and the upstream maintainer agrees to publish Windows artifacts.

### 3. Parity roadmap

- Share or port IPTV data models and guide matching where feasible; add catch-up, favorites, Sports channel matching, account sync, VOD, recording, VPN, updater, and codec coverage as separately tested milestones.
- For each milestone, record the Android behavior, Windows behavior, manual verification, and remaining gaps.

## Acceptance checks for the first deliverable

1. On Windows, install the generated artifact, launch from Start, import a representative M3U playlist, and play an HLS channel.
2. Load a representative XMLTV guide, show current and next programs, search and switch channels with keyboard/mouse.
3. Restart the app and confirm playlist/guide settings persist; malformed or unreachable sources show actionable errors.
4. Run the desktop test/build commands locally and in CI; verify the installer artifact exists and launches on a clean Windows machine or VM.
5. Update documentation with supported features and limitations. Link an approved upstream feature request before opening the upstream PR.

## Work split

- Desktop backend: source import, parsing, persistence, and safe IPC.
- Desktop UI and playback: channel browser, guide, search, keyboard controls, and playback states.
- Windows build: package configuration, installer workflow, and build documentation.
- Integration: review boundaries, add meaningful tests, run Windows smoke checks, and prepare upstream contribution.
