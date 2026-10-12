param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $projectRoot '.tools'
if (Test-Path (Join-Path $toolsRoot 'jdk')) { $env:JAVA_HOME = Join-Path $toolsRoot 'jdk' }
if (Test-Path (Join-Path $toolsRoot 'android-sdk')) { $env:ANDROID_HOME = Join-Path $toolsRoot 'android-sdk' }
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-home'
$taskTemp = Join-Path $toolsRoot 'tmp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$taskTemp"
Push-Location $projectRoot
try {
    $requiredNative = @('native/sherpa/kotlin/Vad.kt',
        'native/sherpa/kotlin/VersionInfo.kt', 'native/sherpa/kotlin/WaveReader.kt',
        'native/sherpa/jniLibs/arm64-v8a/libsherpa-onnx-jni.so', 'native/assets/licenses/hymt/SOURCE.txt',
        'native/assets/licenses/onnxruntime-1.28.2-ThirdPartyNotices.txt', 'native/assets/licenses/sherpa-dependencies/SOURCE.txt')
    if ($requiredNative | Where-Object { !(Test-Path (Join-Path $toolsRoot $_)) }) { & (Join-Path $PSScriptRoot 'bootstrap-native.ps1') }
    & (Join-Path $PSScriptRoot 'build-hymt-native.ps1')
    $nativeSource = Get-Content -LiteralPath (Join-Path $toolsRoot 'native/assets/licenses/sherpa-dependencies/SOURCE.txt') -Raw | ConvertFrom-Json
    if ($nativeSource.sherpaCommit -cne '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf' -or
        $nativeSource.tts -ne $false -or $nativeSource.offlineDiarization -ne $false -or $nativeSource.speakerEmbedding -ne $true) {
        throw 'Unexpected native SDK metadata; preserve it and rerun bootstrap-native.ps1 with the pinned toolchain'
    }
    if (Test-Path (Join-Path $projectRoot 'gradlew.bat')) {
        & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain
    } else {
        & (Join-Path $toolsRoot 'gradle-8.11.1/bin/gradle.bat') @Tasks --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
