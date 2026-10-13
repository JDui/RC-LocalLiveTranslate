$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'elf-alignment.ps1')
$sdk = if (Test-Path (Join-Path $root '.tools/android-sdk')) { Join-Path $root '.tools/android-sdk' } else { $env:ANDROID_HOME }
if (Test-Path (Join-Path $root '.tools/jdk')) { $env:JAVA_HOME = Join-Path $root '.tools/jdk' }
$suffix = if ($IsWindows) { '.bat' } else { '' }
$analyzer = Join-Path $sdk "cmdline-tools/latest/bin/apkanalyzer$suffix"
if (!(Test-Path -LiteralPath $analyzer)) {
    $command = Get-Command "apkanalyzer$suffix" -ErrorAction SilentlyContinue
    if (!$command) { throw 'SDK apkanalyzer is required (cmdline-tools/latest or PATH)' }
    $analyzer = $command.Source
}
$ns = 'http://schemas.android.com/apk/res/android'
$release = Join-Path $root 'app/build/outputs/apk/release/app-release-unsigned.apk'
$target = Join-Path $root 'app/build/outputs/apk/benchmark/app-benchmark.apk'
$tool = Join-Path $root 'macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk'
foreach ($item in @(@($release, 'com.jd.rc.localtranslate'), @($target, 'com.jd.rc.localtranslate.benchmark'),
    @($tool, 'com.jd.rc.localtranslate.macrobenchmark'))) {
    $text = & $analyzer manifest print $item[0]
    if ($LASTEXITCODE -ne 0) { throw "Cannot decode APK manifest: $($item[0])" }
    [xml]$manifest = $text -join "`n"
    if ($manifest.manifest.GetAttribute('package') -cne $item[1]) { throw 'Benchmark package isolation failed' }
    $application = $manifest.manifest.application
    if ($item[0] -eq $tool) {
        if ($manifest.manifest.instrumentation.GetAttribute('targetPackage', $ns) -cne $item[1]) { throw 'Tool must be self-instrumenting' }
    } else {
        if ($application.GetAttribute('debuggable', $ns) -eq 'true') { throw 'Application target must not be debuggable' }
        $profile = $application.SelectSingleNode('profileable')
        if ($item[0] -eq $target) {
            if (!$profile -or $profile.GetAttribute('shell', $ns) -ne 'true') { throw 'Benchmark target is not profileable' }
        } elseif ($profile) { throw 'Release application unexpectedly enables profiling' }
        foreach ($permission in $manifest.manifest.SelectNodes('uses-permission | uses-permission-sdk-23')) {
            if ($permission.GetAttribute('name', $ns) -in @('android.permission.ACCESS_NETWORK_STATE',
                'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.MANAGE_EXTERNAL_STORAGE')) {
                throw 'Application target has forbidden runtime permissions'
            }
        }
    }
    Write-Output "Manifest isolation PASS: $($item[1])"
}
& (Join-Path $PSScriptRoot 'verify-native.ps1') -Apk $target
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($tool)
try {
    $libraries = @($zip.Entries | Where-Object FullName -Match '^lib/.+\.so$')
    if (!$libraries.Count) { throw 'Benchmark tool contains no native libraries' }
    foreach ($entry in $libraries) {
        if (!$entry.FullName.StartsWith('lib/arm64-v8a/')) { throw 'Benchmark tool must match the ARM64 target' }
        $stream = $entry.Open(); $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        Assert-Arm64Elf16Kb -Bytes $bytes -Name $entry.FullName
    }
} finally { $zip.Dispose() }
$zipalign = Join-Path $sdk ('build-tools/36.0.0/zipalign' + $(if ($IsWindows) { '.exe' } else { '' }))
& $zipalign -c -P 16 4 $tool
if ($LASTEXITCODE -ne 0) { throw 'Benchmark tool APK ZIP alignment failed' }
Write-Output 'Benchmark packages / permissions / ELF / ZIP isolation PASS (BUILD ONLY; no device measurements)'
