param([string]$Apk = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'elf-alignment.ps1')
if (!$Apk) { $Apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk))
try {
    $sourceEntry = $zip.GetEntry('assets/licenses/sherpa-dependencies/SOURCE.txt')
    if (!$sourceEntry) { throw 'APK is missing no-TTS source / license inventory' }
    $reader = [IO.StreamReader]::new($sourceEntry.Open())
    try { $sourceInfo = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($sourceInfo.sherpaCommit -cne '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf' -or
        $sourceInfo.tts -ne $false -or $sourceInfo.offlineDiarization -ne $false -or $sourceInfo.speakerEmbedding -ne $true) {
        throw 'Unexpected native preparation metadata'
    }
    foreach ($path in @('eigen/COPYING.MPL2.txt', 'kissfft/BSD-3-Clause.txt', 'NDK-r29-NOTICE.toolchain.txt')) {
        if (!$zip.GetEntry("assets/licenses/sherpa-dependencies/$path")) { throw "Missing native license: $path" }
    }
    $libraries = @($zip.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' })
    if (!$libraries.Count) { throw 'APK contains no native libraries' }
    foreach ($entry in $libraries) {
        if (!$entry.FullName.StartsWith('lib/arm64-v8a/')) { throw "Unexpected ABI: $($entry.FullName)" }
        $stream = $entry.Open()
        $memory = [System.IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        if ($entry.FullName -eq 'lib/arm64-v8a/libsherpa-onnx-jni.so') { $jniBytes = $bytes }
        Assert-Arm64Elf16Kb -Bytes $bytes -Name $entry.FullName
    }
} finally { $zip.Dispose() }
$sdk = Join-Path $root '.tools/android-sdk'
if (!(Test-Path -LiteralPath $sdk)) { $sdk = $env:ANDROID_HOME }
if (!$sdk -or !(Test-Path -LiteralPath (Join-Path $sdk 'build-tools'))) { throw 'ELF checks passed, but SDK build-tools are required for APK ZIP verification' }
$hostTag = if ($IsWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
$suffix = if ($IsWindows) { '.exe' } else { '' }
$nm = Join-Path $sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/$hostTag/bin/llvm-nm$suffix"
if (!(Test-Path -LiteralPath $nm) -or !$jniBytes) { throw 'JNI bytes and pinned NDK llvm-nm are required for feature verification' }
$temporary = Join-Path $root ".tools/native-check-$([Guid]::NewGuid().ToString('N'))"
$extracted = Join-Path $temporary 'libsherpa-onnx-jni.so'
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    [IO.File]::WriteAllBytes($extracted, $jniBytes)
    $symbols = & $nm -D --defined-only $extracted
    if ($LASTEXITCODE -ne 0) { throw 'APK JNI symbol inspection failed' }
    if ($symbols | Select-String 'OfflineTts|OfflineSpeakerDiarization|espeak|piper') { throw 'APK contains disabled native feature symbols' }
    foreach ($name in @('OfflineRecognizer', 'SpeakerEmbeddingExtractor', 'Vad')) {
        if (!($symbols | Select-String "Java_com_k2fsa_sherpa_onnx_${name}_newFromFile$")) { throw "APK missing required JNI: $name" }
    }
    Write-Output 'APK no-TTS / required JNI / source inventory PASS'
} finally {
    if (Test-Path -LiteralPath $extracted) { Remove-Item -LiteralPath $extracted }
    Remove-Item -LiteralPath $temporary
}
$zipalign = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -File -Recurse | Where-Object { $_.Name -in @('zipalign', 'zipalign.exe') } | Select-Object -Last 1
if (!$zipalign) { throw 'ELF checks passed, but zipalign was not found' }
& $zipalign.FullName -c -P 16 4 $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK zip alignment failed' }
Write-Output 'APK zip 16 KB alignment PASS'
