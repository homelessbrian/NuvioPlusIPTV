param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path,
    [string]$Baseline = '1049818f',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'),
    [string]$OutputDirectory,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw 'Pass -JavaHome pointing to a JDK17+ installation.' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $RepoRoot 'build/xmltv-parser-benchmark' }
New-Item -ItemType Directory -Force -Path $OutputDirectory,(Join-Path $OutputDirectory 'classes') | Out-Null
function CachedJar([string]$path) {
    $directory = Join-Path $GradleCache $path
    $jar = Get-ChildItem -LiteralPath $directory -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached JVM dependency $path. Populate the project's Gradle dependency cache first." }
    return $jar
}
$kxml = Join-Path $OutputDirectory 'kxml2-2.3.0.jar'
$kxmlHash = 'F264DD9F79A1FDE10CE5ECC53221EFF24BE4C9331C830B7D52F2F08A7B633DE2'
if (-not (Test-Path -LiteralPath $kxml)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar' -OutFile $kxml
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $kxml).Hash -ne $kxmlHash) { throw 'KXml2 benchmark dependency hash mismatch.' }
$compiler = @('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.0','org.jetbrains.kotlin/kotlin-stdlib/2.3.0',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.3.0','org.jetbrains.kotlin/kotlin-reflect/1.6.10',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2','org.jetbrains/annotations/13.0') | ForEach-Object { CachedJar $_ }
$classpath = @((CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.3.0'),(CachedJar 'org.jetbrains/annotations/13.0'),
    (CachedJar 'junit/junit/4.13.2'),(CachedJar 'org.hamcrest/hamcrest-core/1.3'),$kxml,(Join-Path $OutputDirectory 'classes'))

# Mechanically retain the exact baseline parser body. Only its object name changes;
# its public result declarations are shared with the current source for direct equality checks.
$relativeParser = 'app/src/main/java/com/nuvio/tv/livetv/data/XmltvParser.kt'
$baselineLines = git -C $RepoRoot show ($Baseline + ':' + $relativeParser)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the specified baseline parser.' }
$baselineText = $baselineLines -join "`n"
$begin = $baselineText.IndexOf('data class XmltvChannel(')
$finish = $baselineText.IndexOf("/**`n * Streaming XMLTV parser.")
if ($begin -lt 0 -or $finish -lt $begin) { throw 'Recheck baseline parser declarations before extracting.' }
$baselineText = ($baselineText.Substring(0,$begin) + $baselineText.Substring($finish)).Replace('object XmltvParser {','object BaselineXmltvParser {')
$baselineFile = Join-Path $OutputDirectory 'BaselineXmltvParser.kt'
[System.IO.File]::WriteAllText($baselineFile,$baselineText)
$model = [System.IO.File]::ReadAllText((Join-Path $RepoRoot 'app/src/main/java/com/nuvio/tv/livetv/model/LiveTvModels.kt')).Replace("`r`n","`n")
$begin = $model.IndexOf('data class EpgProgram(')
$finish = $model.IndexOf("`nenum class ZapMode",$begin)
if ($begin -lt 0 -or $finish -lt $begin) { throw 'Recheck EpgProgram model before extracting.' }
$modelFile = Join-Path $OutputDirectory 'EpgProgram.kt'
[System.IO.File]::WriteAllText($modelFile,"package com.nuvio.tv.livetv.model`n`n"+$model.Substring($begin,$finish-$begin))

& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -cp ($classpath -join ';') -d (Join-Path $OutputDirectory 'classes') (Join-Path $PSScriptRoot 'src/android/util/Xml.java')
if ($LASTEXITCODE -ne 0) { throw 'Host XML factory compilation failed.' }
$sources = @($modelFile,$baselineFile,(Join-Path $PSScriptRoot 'src/ParserBenchmark.kt'),(Join-Path $RepoRoot $relativeParser),
    (Join-Path $RepoRoot 'app/src/test/java/com/nuvio/tv/livetv/data/XmltvParserEpisodeTest.kt'),
    (Join-Path $RepoRoot 'app/src/test/java/com/nuvio/tv/livetv/data/XmltvParserTimeTest.kt'))
$arguments = @('-no-stdlib','-no-reflect','-jvm-target','17','-classpath',($classpath -join ';'),'-d',(Join-Path $OutputDirectory 'classes')) + $sources
$compilerArgs = Join-Path $OutputDirectory 'compiler.args'
$arguments | ForEach-Object { '"'+$_.Replace('\','/').Replace('"','\"')+'"' } | Set-Content -LiteralPath $compilerArgs -Encoding utf8
& (Join-Path $JavaHome 'bin/java.exe') -Xmx1g -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$compilerArgs"
if ($LASTEXITCODE -ne 0) { throw 'Parser comparison compilation failed.' }
$parserFile = Join-Path $RepoRoot $relativeParser
Write-Output ("baseline="+(git -C $RepoRoot rev-parse $Baseline)+",currentParserSHA256="+(Get-FileHash -Algorithm SHA256 -LiteralPath $parserFile).Hash)
$runnerArgs = @('-Xmx1536m','-XX:+UseG1GC','-cp',($classpath -join ';'),'com.nuvio.tv.livetv.data.ParserBenchmarkKt')
if ($CheckOnly) { $runnerArgs += '--check-only' }
& (Join-Path $JavaHome 'bin/java.exe') @runnerArgs | Tee-Object -FilePath (Join-Path $OutputDirectory 'results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Parser equivalence or benchmark check failed.' }
