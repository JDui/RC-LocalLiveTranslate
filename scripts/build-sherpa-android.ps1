#requires -Version 7.0
param([string]$SdkDirectory = '', [int]$Jobs = 4)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
New-Item -ItemType Directory -Force -Path $tools | Out-Null
try { $buildLock = [IO.File]::Open((Join-Path $tools 'sherpa-android-build.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
catch { throw 'Native preparation is already running. Wait for that process before retrying.' }
try {
if (!$SdkDirectory) {
    $SdkDirectory = if (Test-Path -LiteralPath (Join-Path $tools 'android-sdk')) { Join-Path $tools 'android-sdk' } else { $env:ANDROID_HOME }
}
if (!$SdkDirectory -or $Jobs -lt 1 -or $Jobs -gt 16) { throw 'An Android SDK and 1..16 build jobs are required' }
$ndk = Join-Path $SdkDirectory 'ndk/29.0.14206865'
$suffix = if ($IsWindows) { '.exe' } else { '' }
$cmake = Join-Path $SdkDirectory "cmake/3.22.1/bin/cmake$suffix"
$ninja = Join-Path $SdkDirectory "cmake/3.22.1/bin/ninja$suffix"
foreach ($required in @($cmake, $ninja, (Join-Path $ndk 'build/cmake/android.toolchain.cmake'))) {
    if (!(Test-Path -LiteralPath $required)) { throw "Install SDK packages ndk;29.0.14206865 and cmake;3.22.1 first: $required" }
}
$source = Join-Path $tools 'sherpa-source'
$commit = git -C $source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $commit -cne '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf') { throw 'Pinned sherpa-onnx source is required; run bootstrap-native.ps1' }
git -C $source sparse-checkout add cmake sherpa-onnx/csrc sherpa-onnx/jni
if ($LASTEXITCODE -ne 0) { throw 'Cannot materialize pinned native source' }
$languagePatchId = 'rc-sensevoice-language-scores-v1'
$languagePatch = Join-Path $PSScriptRoot 'native/sensevoice-language-scores-v1.patch'
$languageHeader = Join-Path $PSScriptRoot 'native/rc-sensevoice-language-scores.h'
foreach ($file in @($languagePatch, $languageHeader)) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing reproducible native adapter source: $file" }
}
# A fully applied patch is safe to reuse. Preserve and reject any incompatible local changes.
git -C $source apply --reverse --check $languagePatch 2>$null
if ($LASTEXITCODE -ne 0) {
    git -C $source apply --check $languagePatch
    if ($LASTEXITCODE -ne 0) { throw 'The pinned SenseVoice language-score patch does not apply; preserve the source checkout and inspect its changes.' }
    git -C $source apply $languagePatch
    if ($LASTEXITCODE -ne 0) { throw 'Cannot apply the SenseVoice language-score patch' }
}
Copy-Item -LiteralPath $languageHeader -Destination (Join-Path $source 'sherpa-onnx/csrc/rc-sensevoice-language-scores.h') -Force
$hintPatchId = 'rc-sensevoice-language-hint-v2'
$hintPatch = Join-Path $PSScriptRoot 'native/sensevoice-language-hint-v2.patch'
git -C $source apply --reverse --check $hintPatch 2>$null
if ($LASTEXITCODE -ne 0) {
    git -C $source apply --check $hintPatch
    if ($LASTEXITCODE -ne 0) { throw 'The pinned SenseVoice language-hint patch does not apply; preserve local source changes.' }
    git -C $source apply $hintPatch
    if ($LASTEXITCODE -ne 0) { throw 'Cannot apply the SenseVoice language-hint patch' }
}
$build = Join-Path $tools 'sherpa-android-no-tts'
$flags = @(
    '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=ON',
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-28', '-DANDROID_STL=c++_static',
    '-DSHERPA_ONNX_ENABLE_TTS=OFF', '-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF',
    '-DSHERPA_ONNX_ENABLE_JNI=ON', '-DSHERPA_ONNX_ENABLE_C_API=OFF',
    '-DSHERPA_ONNX_ENABLE_PYTHON=OFF', '-DSHERPA_ONNX_ENABLE_BINARY=OFF',
    '-DSHERPA_ONNX_ENABLE_TESTS=OFF', '-DSHERPA_ONNX_ENABLE_CHECK=OFF',
    '-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF', '-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF',
    '-DSHERPA_ONNX_ENABLE_GPU=OFF', '-DSHERPA_ONNX_ENABLE_RKNN=OFF', '-DSHERPA_ONNX_ENABLE_QNN=OFF',
    '-DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF', '-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF',
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'
)
& $cmake -S $source -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" "-DCMAKE_INSTALL_PREFIX=$build/install" @flags
if ($LASTEXITCODE -ne 0) { throw 'No-TTS native configuration failed' }
& $cmake --build $build --target sherpa-onnx-jni --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'No-TTS JNI compilation failed' }
$cache = Get-Content -LiteralPath (Join-Path $build 'CMakeCache.txt') -Raw
foreach ($option in @('SHERPA_ONNX_ENABLE_TTS', 'SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION')) {
    if ($cache -notmatch "(?m)^${option}:BOOL=OFF\r?$" ) { throw "Unsafe native build configuration: $option" }
}
$dependencies = [ordered]@{
    'kaldi_native_fbank' = 'https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.22.3'
    'kissfft' = 'https://github.com/mborgerding/kissfft/tree/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec'
    'kaldi_decoder' = 'https://github.com/k2-fsa/kaldi-decoder/tree/v0.3.0'
    'kaldifst' = 'https://github.com/k2-fsa/kaldifst/tree/v1.8.0'
    'openfst' = 'https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09'
    'eigen' = 'https://gitlab.com/libeigen/eigen/-/tree/5.0.1'
    'simple-sentencepiece' = 'https://github.com/pkufool/simple-sentencepiece/tree/v0.7'
    'json' = 'https://github.com/nlohmann/json/tree/v3.12.0'
}
$licenseRoot = Join-Path $tools 'native/assets/licenses/sherpa-dependencies'
New-Item -ItemType Directory -Force -Path $licenseRoot | Out-Null
$localSource = Join-Path $licenseRoot 'rc-local-patches'
New-Item -ItemType Directory -Force -Path $localSource | Out-Null
Copy-Item -LiteralPath $languagePatch -Destination (Join-Path $localSource 'sensevoice-language-scores-v1.patch.txt') -Force
Copy-Item -LiteralPath $languageHeader -Destination (Join-Path $localSource 'rc-sensevoice-language-scores.h.txt') -Force
Copy-Item -LiteralPath $hintPatch -Destination (Join-Path $localSource 'sensevoice-language-hint-v2.patch.txt') -Force
foreach ($name in $dependencies.Keys) {
    $directory = Join-Path $build "_deps/$name-src"
    $texts = @(Get-ChildItem -LiteralPath $directory -File | Where-Object Name -Match '^(LICENSE|NOTICE|COPYING|COPYRIGHT|AUTHORS)([._-].*)?$')
    if (!$texts.Count) { throw "No upstream license material for $name" }
    $destination = Join-Path $licenseRoot $name
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    foreach ($file in $texts) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $destination "$($file.Name).txt") -Force }
    if ($name -eq 'kissfft') {
        Copy-Item -LiteralPath (Join-Path $directory 'LICENSES/BSD-3-Clause') -Destination (Join-Path $destination 'BSD-3-Clause.txt') -Force
    }
    $copyrights = @(Get-ChildItem -LiteralPath $directory -Recurse -File | Where-Object Extension -In @('.h', '.hpp', '.c', '.cc', '.cpp') |
        Select-String -Pattern '(?i)copyright.*[0-9]{4}' | ForEach-Object { $_.Line.Trim() } | Sort-Object -Unique)
    [IO.File]::WriteAllLines((Join-Path $destination 'COPYRIGHTS.txt'), [string[]]$copyrights, [Text.UTF8Encoding]::new($false))
}
foreach ($file in @('NOTICE', 'NOTICE.toolchain')) {
    Copy-Item -LiteralPath (Join-Path $ndk $file) -Destination (Join-Path $licenseRoot "NDK-r29-$file.txt") -Force
}
$metadata = [ordered]@{
    sherpaVersion = '1.13.8'
    sherpaCommit = $commit
    source = "https://github.com/k2-fsa/sherpa-onnx/tree/$commit"
    buildScript = 'scripts/build-sherpa-android.ps1'
    abi = 'arm64-v8a'
    platform = 28
    ndk = '29.0.14206865'
    cmake = '3.22.1'
    tts = $false
    offlineDiarization = $false
    speakerEmbedding = $true
    localPatches = @($languagePatchId, $hintPatchId)
    localPatchSources = @('rc-local-patches/sensevoice-language-scores-v1.patch.txt', 'rc-local-patches/rc-sensevoice-language-scores.h.txt', 'rc-local-patches/sensevoice-language-hint-v2.patch.txt')
    dependencies = $dependencies
    onnxruntime = 'https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip'
    notes = 'No model weights. Full license texts and collected public-source copyright notices are adjacent. Not every upstream source file is linked. The read-only language-score adapter and optional per-stream second-pass language-input patch are included under rc-local-patches. Automatic first-pass input is unchanged. Eigen source is unchanged; sherpa applies its pinned Android sentencepiece patch through CMake. MPL-covered source remains available from the listed upstream sources.'
}
$hostTag = if ($IsWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
$strip = Join-Path $ndk "toolchains/llvm/prebuilt/$hostTag/bin/llvm-strip$suffix"
$library = Join-Path $build 'lib/libsherpa-onnx-jni.so'
$jni = Join-Path $tools 'native/sherpa/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force -Path $jni | Out-Null
$stripped = Join-Path $build 'libsherpa-onnx-jni-stripped.so'
& $strip --strip-unneeded -o $stripped $library
if ($LASTEXITCODE -ne 0) { throw 'Cannot strip the newly built JNI' }
Copy-Item -LiteralPath $stripped -Destination (Join-Path $jni 'libsherpa-onnx-jni.so') -Force
Copy-Item -LiteralPath (Join-Path $build '_deps/onnxruntime-src/jni/arm64-v8a/libonnxruntime.so') -Destination $jni -Force
[IO.File]::WriteAllText((Join-Path $licenseRoot 'SOURCE.txt'), ($metadata | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output 'Pinned ARM64 no-TTS JNI and upstream license / source materials prepared. Device loading remains unverified.'
} finally { $buildLock.Dispose() }
