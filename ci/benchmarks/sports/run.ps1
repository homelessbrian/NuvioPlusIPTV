param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AppClasses,
    [string]$ProductionSourceRoot = '',
    [string]$MatchSourceClasses = '',
    [switch]$HockeyOnly,
    [string]$GradleCache = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/caches/modules-2/files-2.1'),
    [string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) ('nuvio-sports-bench-' + [Guid]::NewGuid().ToString('N')))
)
$ErrorActionPreference = 'Stop'
if ($HockeyOnly -and $MatchSourceClasses) { throw '-HockeyOnly cannot be combined with -MatchSourceClasses.' }
$checkout = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (!$ProductionSourceRoot) { $ProductionSourceRoot = $checkout }
function CachedJar([string]$Group, [string]$Artifact, [string]$Version) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $GradleCache "$Group/$Artifact/$Version") -Recurse -Filter '*.jar' | Select-Object -First 1
    if (!$jar) { throw "Missing cached dependency $Group/$Artifact/$Version" }
    return $jar.FullName
}
$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.3.0'
$coroutines = CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.10.2'
$compiler = @(
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.3.0'), $stdlib,
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.3.0'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.3.0'), $coroutines,
    (CachedJar 'org.jetbrains' 'annotations' '13.0')
)
$classpath = @($stdlib, $coroutines) + $(if ($MatchSourceClasses) { @((Resolve-Path -LiteralPath $MatchSourceClasses).Path) } else { @() }) + @(
    (Resolve-Path -LiteralPath $AppClasses).Path,
    (CachedJar 'junit' 'junit' '4.13.2'),
    (CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'),
    (CachedJar 'org.json' 'json' '20250517'),
    (CachedJar 'com.squareup.okhttp3' 'okhttp' '4.12.0'),
    (CachedJar 'com.squareup.okio' 'okio-jvm' '3.16.4')
)
$sourcePaths = @(
    'app/src/main/java/com/nuvio/tv/livetv/data/ForkHttp.kt',
    'app/src/main/java/com/nuvio/tv/livetv/sports/SportsFeedCache.kt',
    'app/src/main/java/com/nuvio/tv/livetv/sports/SportsFeeds.kt',
    'app/src/main/java/com/nuvio/tv/livetv/sports/SportsModels.kt',
    'app/src/main/java/com/nuvio/tv/livetv/sports/SportsCatalog.kt',
    'app/src/main/java/com/nuvio/tv/livetv/sports/SportsEventCodec.kt',
    'app/src/test/java/com/nuvio/tv/livetv/sports/SportsHockeyTechTest.kt',
    'app/src/test/java/com/nuvio/tv/livetv/sports/SportsFeedDateFormattingTest.kt',
    'ci/benchmarks/sports/SportsHockeyTechAllocationBenchmark.kt'
)
if (!$HockeyOnly) {
    $sourcePaths += @(
        'app/src/main/java/com/nuvio/tv/livetv/sports/SportsMatchWork.kt',
        'app/src/test/java/com/nuvio/tv/livetv/sports/SportsFeedCacheTest.kt',
        'ci/benchmarks/sports/SportsFeedCacheAllocationBenchmark.kt'
    )
}
$sources = $sourcePaths | ForEach-Object { Join-Path $(if ($_.StartsWith('app/src/main/')) { $ProductionSourceRoot } else { $checkout }) $_ }
if ($MatchSourceClasses) { $sources += Join-Path $checkout 'ci/benchmarks/sports/SportsMatchInputsAllocationBenchmark.kt' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a fresh output directory to preserve previous results.' }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { $java = Join-Path $JavaHome 'bin/java' }
$compileArgs = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ($classpath -join [IO.Path]::PathSeparator), '-d', $OutputDirectory) + $sources
$argFile = Join-Path $OutputDirectory 'compiler.args'
$compileArgs | ForEach-Object { '"' + $_.Replace('\', '/') + '"' } | Set-Content -LiteralPath $argFile -Encoding utf8NoBOM
& $java '-Xmx768m' '-cp' ($compiler -join [IO.Path]::PathSeparator) 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$argFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$tests = if ($HockeyOnly) { @(
    'com.nuvio.tv.livetv.sports.SportsHockeyTechTest',
    'com.nuvio.tv.livetv.sports.SportsFeedDateFormattingTest',
    'com.nuvio.tv.livetv.sports.SportsHockeyTechAllocationBenchmark'
) } else { @(
    'com.nuvio.tv.livetv.sports.SportsFeedCacheTest',
    'com.nuvio.tv.livetv.sports.SportsHockeyTechTest',
    'com.nuvio.tv.livetv.sports.SportsFeedDateFormattingTest',
    'com.nuvio.tv.livetv.sports.SportsFeedCacheAllocationBenchmark',
    'com.nuvio.tv.livetv.sports.SportsHockeyTechAllocationBenchmark'
) }
if ($MatchSourceClasses) { $tests += 'com.nuvio.tv.livetv.sports.SportsMatchInputsAllocationBenchmark' }
& $java '-cp' ((@($OutputDirectory) + $classpath) -join [IO.Path]::PathSeparator) 'org.junit.runner.JUnitCore' @tests | Tee-Object -FilePath (Join-Path $OutputDirectory 'results.txt')
exit $LASTEXITCODE
