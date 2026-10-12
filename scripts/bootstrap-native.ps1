$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
$native = Join-Path $tools 'native'
New-Item -ItemType Directory -Force -Path $native | Out-Null

function Get-Artifact([string]$Url, [string]$Path, [string]$Sha256 = '') {
    if (!(Test-Path -LiteralPath $Path)) {
        Invoke-WebRequest $Url -OutFile "$Path.part"
        Move-Item -LiteralPath "$Path.part" -Destination $Path
    }
    if ($Sha256 -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Sha256) {
        throw "Native distribution integrity mismatch: $Path"
    }
}

$sherpa = Join-Path $tools 'sherpa-source'
if (!(Test-Path -LiteralPath $sherpa)) {
    git clone --depth 1 --branch v1.13.8 --filter=blob:none --sparse https://github.com/k2-fsa/sherpa-onnx.git $sherpa
    if ($LASTEXITCODE -ne 0) { throw 'Cannot fetch sherpa-onnx v1.13.8' }
}
$commit = git -C $sherpa rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $commit -ne '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf') {
    throw 'Unexpected sherpa source revision. Preserve that checkout and use the pinned version.'
}
git -C $sherpa sparse-checkout add sherpa-onnx/kotlin-api
if ($LASTEXITCODE -ne 0) { throw 'Cannot materialize sherpa Kotlin API' }
$api = Join-Path $native 'sherpa/kotlin'
New-Item -ItemType Directory -Force -Path $api | Out-Null
$files = @('OfflineRecognizer.kt', 'OfflineStream.kt', 'FeatureConfig.kt', 'QnnConfig.kt',
    'HomophoneReplacerConfig.kt', 'Vad.kt', 'Speaker.kt', 'SpeakerEmbeddingExtractorConfig.kt', 'OnlineStream.kt',
    'VersionInfo.kt', 'WaveReader.kt')
foreach ($file in $files) { Copy-Item -LiteralPath (Join-Path $sherpa "sherpa-onnx/kotlin-api/$file") -Destination $api }

$licenses = Join-Path $native 'assets/licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
Copy-Item -LiteralPath (Join-Path $sherpa 'LICENSE') -Destination (Join-Path $licenses 'sherpa-onnx-Apache-2.0.txt')
Get-Artifact 'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/LICENSE' (Join-Path $licenses 'onnxruntime-1.28.2-MIT.txt')
Get-Artifact 'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/ThirdPartyNotices.txt' (Join-Path $licenses 'onnxruntime-1.28.2-ThirdPartyNotices.txt')
& (Join-Path $PSScriptRoot 'build-sherpa-android.ps1')
& (Join-Path $PSScriptRoot 'build-hymt-native.ps1')
Write-Output 'Pinned sherpa no-TTS and Hy-MT2 STQ SDKs ready. No weights included.'
