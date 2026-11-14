param(
    [switch]$Android,
    [ValidateSet('sports','substring-misses','overlap-misses')][string]$Scenario='sports',
    [string]$Repository=(Join-Path $PSScriptRoot '../../..'),
    [string]$Baseline='e4d3d6e801c79835e53d3d967339268029f587b2',
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidSdk=$env:ANDROID_HOME,
    [string]$GradleCache="$env:USERPROFILE/.gradle/caches/modules-2/files-2.1",
    [ValidatePattern('^emulator-[0-9]+$')][string]$Serial
)
$ErrorActionPreference='Stop'
$taskSources=$PSScriptRoot
$taskBench=Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Path $taskBench -Force | Out-Null
$taskJdk=$JavaHome
$taskSdk=$AndroidSdk
if(-not $taskJdk){throw 'Set JAVA_HOME or pass -JavaHome (JDK21).'}
if(-not $taskSdk){$taskSdk=$env:ANDROID_SDK_ROOT}
$taskCache=$GradleCache
$taskArtifacts=@('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.0','org.jetbrains.kotlin/kotlin-stdlib/2.3.0','org.jetbrains.kotlin/kotlin-script-runtime/2.3.0','org.jetbrains.kotlin/kotlin-reflect/1.6.10','org.jetbrains.kotlin/kotlin-daemon-embeddable/2.3.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0','org.jetbrains/annotations')
$taskJars=@(); foreach($taskArtifact in $taskArtifacts){ $taskJars+=Get-ChildItem -LiteralPath "$taskCache/$taskArtifact" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName }
$taskStdlib=($taskJars | Where-Object { $_ -match 'kotlin-stdlib-' } | Select-Object -First 1)
$taskClasses="$taskBench/classes"
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& py -3 "$taskSources/extract.py" --repo $Repository --baseline $Baseline --output $taskBench
if($LASTEXITCODE){throw 'Source extraction failed'}
& "$taskJdk/bin/java.exe" -cp ($taskJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $taskStdlib -d $taskClasses "$taskBench/Baseline.kt" "$taskBench/Candidate.kt"
if($LASTEXITCODE){throw 'Kotlin compilation failed'}
& "$taskJdk/bin/javac.exe" --release 17 -cp "$taskClasses;$taskStdlib" -d $taskClasses "$taskSources/BenchCore.java" "$taskSources/JvmRunner.java"
if($LASTEXITCODE){throw 'Harness compilation failed'}
& "$taskJdk/bin/java.exe" -Xms128m -Xmx128m -cp "$taskClasses;$taskStdlib" sports.bench.JvmRunner $Scenario | Set-Content -LiteralPath "$taskBench/jvm-$Scenario-results.jsonl" -Encoding utf8
if($LASTEXITCODE){throw 'JVM benchmark failed'}
if($Android){
    if(-not $Serial){throw 'Pass -Serial for an owned emulator.'}
    if(-not $taskSdk){throw 'Set ANDROID_HOME or pass -AndroidSdk.'}
    & "$taskJdk/bin/javac.exe" --release 17 -cp "$taskClasses;$taskStdlib;$taskSdk/platforms/android-36/android.jar" -d $taskClasses "$taskSources/ArtRunner.java"
    if($LASTEXITCODE){throw 'ART harness compilation failed'}
    $env:JAVA_HOME=$taskJdk
    $env:PATH="$taskJdk/bin;$env:PATH"
    & "$taskJdk/bin/jar.exe" --create --file "$taskBench/art-input.jar" -C $taskClasses sports/bench/Baseline.class -C $taskClasses sports/bench/Candidate.class -C $taskClasses sports/bench/BenchCore.class -C $taskClasses sports/bench/ArtRunner.class
    New-Item -ItemType Directory -Path "$taskBench/dex" -Force | Out-Null
    $ErrorActionPreference='Continue'
    & "$taskSdk/build-tools/36.0.0/d8.bat" --min-api 30 --lib "$taskSdk/platforms/android-36/android.jar" --output "$taskBench/dex" "$taskBench/art-input.jar" $taskStdlib *> "$taskBench/d8.log"
    $taskD8Exit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($taskD8Exit){throw 'D8 failed'}
    & "$taskJdk/bin/jar.exe" --create --file "$taskBench/art-benchmark.jar" -C "$taskBench/dex" classes.dex
    $taskRemote="/data/local/tmp/nuvio-sports-word-bench-$PID"
    $taskAdb="$taskSdk/platform-tools/adb.exe"
    & $taskAdb -s $Serial shell mkdir -p $taskRemote
    if($LASTEXITCODE){throw 'Could not create the emulator benchmark directory.'}
    try {
        & $taskAdb -s $Serial push "$taskBench/art-benchmark.jar" "$taskRemote/bench.jar"
        if($LASTEXITCODE){throw 'Could not upload the emulator benchmark jar.'}
        & $taskAdb -s $Serial shell "CLASSPATH=$taskRemote/bench.jar app_process -Xms16m -Xmx128m /system/bin sports.bench.ArtRunner $Scenario" | Set-Content -LiteralPath "$taskBench/art-$Scenario-results.jsonl" -Encoding utf8
        if($LASTEXITCODE){throw 'ART benchmark failed'}
    } finally {
        & $taskAdb -s $Serial shell rm -f "$taskRemote/bench.jar"
        & $taskAdb -s $Serial shell rmdir $taskRemote
    }
}
