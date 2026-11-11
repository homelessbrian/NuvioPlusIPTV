param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$OutDirectory,
    [Parameter(Mandatory=$true)][string]$DebugKeyStore,
    [string]$BuildToolsVersion = '36.0.0'
)
$ErrorActionPreference = 'Stop'
$buildRoot = [IO.Path]::GetFullPath($OutDirectory)
if (Test-Path -LiteralPath $buildRoot) { throw 'Output exists; select a new build directory' }
$exe = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { '.exe' } else { '' }
$buildTools = Join-Path $AndroidSdk "build-tools/$BuildToolsVersion"
$androidJar = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
$javaExe = Join-Path $JavaHome "bin/java$exe"
$javacExe = Join-Path $JavaHome "bin/javac$exe"
$aaptExe = Join-Path $buildTools "aapt2$exe"
$alignExe = Join-Path $buildTools "zipalign$exe"
$d8Jar = Join-Path $buildTools 'lib/d8.jar'
$signJar = Join-Path $buildTools 'lib/apksigner.jar'
$sourceRelative = 'src/com/nuvio/bench/ram20261110/RamInstrumentation.java'
$source = Join-Path $PSScriptRoot $sourceRelative
$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
foreach ($required in @($androidJar,$javaExe,$javacExe,$aaptExe,$alignExe,$d8Jar,$signJar,$source,$manifest,$DebugKeyStore)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing input: $required" }
}
$pins = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-sha256.json') -Raw | ConvertFrom-Json
foreach ($entry in $pins.files.PSObject.Properties) {
    $inputFile = Join-Path $PSScriptRoot $entry.Name
    if ((Get-Item -LiteralPath $inputFile).Length -ne $entry.Value.bytes -or
        (Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value.sha256) {
        throw "Reviewed source bytes changed: $($entry.Name)"
    }
}
$classes = Join-Path $buildRoot 'classes'
$dex = Join-Path $buildRoot 'dex'
[IO.Directory]::CreateDirectory($classes) | Out-Null
[IO.Directory]::CreateDirectory($dex) | Out-Null
function Run-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Tool failed with exit code $LASTEXITCODE : $Executable" }
}
Run-Checked $javacExe @('--release','8','-encoding','UTF-8','-classpath',$androidJar,'-d',$classes,$source)
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
Run-Checked $javaExe (@('-cp',$d8Jar,'com.android.tools.r8.D8','--release','--min-api','26','--lib',$androidJar,'--output',$dex) + $classFiles)
$unsigned = Join-Path $buildRoot 'instrumentation-unsigned.apk'
Run-Checked $aaptExe @('link','-I',$androidJar,'--manifest',$manifest,'-o',$unsigned)
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    # There are no resources; aapt2 emits only an empty table, removed from this new APK.
    $emptyResources = $zip.GetEntry('resources.arsc')
    if ($null -ne $emptyResources) {
        if ($emptyResources.Length -ne 40) { throw 'Unexpected nonempty resource table' }
        $emptyResources.Delete()
    }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $dex 'classes.dex'),'classes.dex',[IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
$aligned = Join-Path $buildRoot 'instrumentation-aligned.apk'
Run-Checked $alignExe @('-p','-f','4',$unsigned,$aligned)
$apk = Join-Path $buildRoot 'guide-ram-observer.apk'
Run-Checked $javaExe @('-jar',$signJar,'sign','--ks',$DebugKeyStore,'--ks-pass','pass:android',
    '--ks-key-alias','androiddebugkey','--key-pass','pass:android','--v1-signing-enabled','false',
    '--v2-signing-enabled','true','--v3-signing-enabled','false','--v4-signing-enabled','false','--out',$apk,$aligned)
$verify = & $javaExe '-jar' $signJar 'verify' '--verbose' '--print-certs' $apk
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
$verify | Set-Content -LiteralPath (Join-Path $buildRoot 'signature.txt') -Encoding utf8
Run-Checked $alignExe @('-c','-p','4',$apk)
$xml = & $aaptExe 'dump' 'xmltree' $apk '--file' 'AndroidManifest.xml'
if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed' }
$xml | Set-Content -LiteralPath (Join-Path $buildRoot 'manifest-tree.txt') -Encoding utf8
function Hash-Record([string]$Path) {
    @{ bytes=(Get-Item -LiteralPath $Path).Length; sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$record = [ordered]@{
    created_utc=[DateTime]::UtcNow.ToString('o')
    package=$pins.package
    target=$pins.target_package
    method='Host-only javac/D8/aapt2/ZIP alignment/APKv2 Android debug signing; no Gradle or device actions'
    source=@{
        $sourceRelative=(Hash-Record $source)
        'AndroidManifest.xml'=(Hash-Record $manifest)
        'build.ps1'=(Hash-Record $PSCommandPath)
        'source-sha256.json'=(Hash-Record (Join-Path $PSScriptRoot 'source-sha256.json'))
    }
    tools=@{
        'android-36/android.jar'=(Hash-Record $androidJar)
        'aapt2'=(Hash-Record $aaptExe)
        'd8.jar'=(Hash-Record $d8Jar)
        'apksigner.jar'=(Hash-Record $signJar)
        'javac'=(Hash-Record $javacExe)
    }
    apk=(Hash-Record $apk)
    checks=@{ signature_v2=$true; zip_alignment_4=$true; device_actions=$false }
}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $buildRoot 'build-manifest.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8
