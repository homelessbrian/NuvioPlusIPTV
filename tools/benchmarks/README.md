# VOD image-cache component benchmark

[CacheRamBenchmark.kt](CacheRamBenchmark.kt) is the exact source used for the [six recorded Android trials](results/vod-image-cache-api36-20261114.json). Both modes run the same binary and attach the real production `PlaybackImageCacheBudget`; only `candidate` enters playback mode. This is a fixed stress fixture, not a comparison of two complete app builds.

- The fixture fills Coil with 64 separate 512×512 ARGB bitmaps (64 MiB), writes every pixel page so memory is resident, and keeps the current 1 MiB artwork referenced outside the cache.
- Each process samples after 3 seconds, applies the candidate cap, then samples after another 3 seconds without forced GC. Two GC/finalization requests with 1-second waits form a separately labelled diagnostic stage. The final sample restores capacity without reloading artwork. Production does not force GC or recycle these bitmaps.

- Three alternating pairs ran on a private headless API 36 x86_64 TV emulator (1 vCPU, 1024 MiB guest RAM). Median strong cache ownership fell **64→16 MiB** immediately. Natural-settle process PSS was **157.460→158.242 MiB**, so this fixture showed no immediate process-RAM reduction.
- After diagnostic GC, native allocated memory was **65.115→16.914 MiB** and process PSS **157.418→109.996 MiB**. Artwork stayed valid. These component results do not measure whole-app VOD, retained screen painters, decoder memory or Fire TV RAM.

The tested toolchain was JDK 21, Coil `coil-core-android` 3.3.0, Android API 36 and Build Tools 36.0.0 D8. `KOTLIN_COMPILER_CP` contained Kotlin `kotlin-compiler-embeddable`, `kotlin-stdlib`, `kotlin-script-runtime` and `kotlin-daemon-embeddable` 2.3.0, `kotlin-reflect` 1.6.10, `kotlinx-coroutines-core-jvm` 1.10.2 and JetBrains `annotations` 13.0. The runtime bundle used Coil 3.3.0, Kotlin stdlib 2.3.0 and coroutines-core-jvm 1.10.2. D8 emitted Kotlin 2.3 metadata-rewrite warnings; it generated the executable DEX and all six runs completed. This fixture does not use Kotlin reflection.

From the repository root, set the uppercase placeholders below to your local tool/artifact paths. `COIL_JAR` is the `classes.jar` extracted from the Coil 3.3.0 AAR; `KOTLIN_COMPILER_CP` contains the compiler jars listed above. `SEP` is `:` on Unix and `;` on Windows. Use a fresh `OUT` directory. These are the tested compile/D8/runtime flags; no APK is required.

```sh
mkdir -p "$OUT/classes" "$OUT/dex"
unzip -p "$COIL_AAR" classes.jar > "$COIL_JAR"
"$JAVA" -Xmx512m -XX:ActiveProcessorCount=1 -cp "$KOTLIN_COMPILER_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "${ANDROID_JAR}${SEP}${COIL_JAR}${SEP}${KOTLIN_STDLIB}" -d "$OUT/classes" \
  app/src/main/java/com/nuvio/tv/core/image/PlaybackImageCache.kt \
  tools/benchmarks/CacheRamBenchmark.kt
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$JAVA" -Xmx512m -XX:ActiveProcessorCount=1 -cp "$D8_JAR" com.android.tools.r8.D8 \
  --debug --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  "$OUT/classes.jar" "$COIL_JAR" "$KOTLIN_STDLIB" "$COROUTINES_JAR"
"$JAR" cf "$OUT/benchmark.jar" -C "$OUT/dex" .
```

Use an isolated emulator and a new, uniquely owned `REMOTE` directory under `/data/local/tmp`. Verify its qemu flag and AVD name before pushing. The recorded process ran at Android nice level 10; the host emulator ran BelowNormal on one host CPU. Preserve each process's JSON output before removing only the files you created:

```sh
adb -s "$EMULATOR_SERIAL" shell getprop ro.kernel.qemu
adb -s "$EMULATOR_SERIAL" emu avd name
adb -s "$EMULATOR_SERIAL" shell mkdir "$REMOTE"
adb -s "$EMULATOR_SERIAL" push "$OUT/benchmark.jar" "$REMOTE/benchmark.jar"
adb -s "$EMULATOR_SERIAL" shell chmod 0444 "$REMOTE/benchmark.jar"
TRIAL=0
for MODE in baseline candidate candidate baseline baseline candidate; do
  TRIAL=$((TRIAL + 1))
  adb -s "$EMULATOR_SERIAL" shell env "CLASSPATH=$REMOTE/benchmark.jar" nice -n 10 \
    app_process /system/bin com.nuvio.tv.core.image.CacheRamBenchmark "$MODE" \
    > "$OUT/trial-$TRIAL-$MODE.log"
done
adb -s "$EMULATOR_SERIAL" shell rm -f "$REMOTE/benchmark.jar"
adb -s "$EMULATOR_SERIAL" shell rmdir "$REMOTE"
```

The result file retains all four samples from each of the six processes, omitting ephemeral PIDs. Byte fields are bytes; Android PSS/private-dirty fields ending in `Kb` are KiB. Medians are computed per stage/mode across three processes. Treat `natural-settle` and `controlled-gc` separately: evicted weakly referenced bitmaps become reclaimable, while their native storage can remain allocated until GC.
