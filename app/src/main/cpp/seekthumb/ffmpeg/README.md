Public FFmpeg headers (LGPL 2.1, see COPYING.LGPLv2.1) from FFmpeg commit
f8bb61cd57a714b4eb8a9f8002dbb5214eed48c7 (2026-11-13), the FFmpeg inside
io.github.abdallahmehiz:mpv-android-lib 0.2.1 (libavcodec 63.28.100, libavformat 63.20.101,
libavutil 61.20.100, libswscale 10.6.100). Only headers: no FFmpeg code is built or copied.
The seek thumbnail reader loads that FFmpeg at run time and checks the major versions first.
If mpv-android-lib is updated, replace these headers with the matching FFmpeg commit.
avconfig.h is the generated file for little-endian ARM.
