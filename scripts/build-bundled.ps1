#requires -Version 7.0
param([string]$Version = '0.1.0-prealpha.6', [string]$OutputFile = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root '.tools/bundled-model-assets/bundled-models'
$packages = @{
    'sensevoice' = '.tools/models/speech/packages/sensevoice-sherpa-int8-2024-07-17.zip'
    'silero-vad' = '.tools/models/speech/packages/silero-vad-sherpa-asset-271935959.zip'
    'speaker' = '.tools/models/speech/packages/speaker-campplus-asset-198893103.zip'
    'hy-mt2' = '.tools/models/hymt/packages/hy-mt2-tencent-hymt2-1.8b-stq1-9df5c824.zip'
}
foreach ($source in $packages.Values) {
    if (!(Test-Path -LiteralPath (Join-Path $root $source))) { throw "Missing prepared package: $source" }
}
New-Item -ItemType Directory -Force -Path $assets | Out-Null
foreach ($id in $packages.Keys) {
    Copy-Item -LiteralPath (Join-Path $root $packages[$id]) -Destination (Join-Path $assets "$id.zip")
}
& (Join-Path $PSScriptRoot 'build.ps1') -Tasks @(':app:assembleRelease', ':app:lintRelease', '-PbundledModels=true', '--no-daemon', '--max-workers=2')
if ($LASTEXITCODE -ne 0) { throw 'Bundled APK build failed' }
& (Join-Path $PSScriptRoot 'package-prealpha.ps1') -Version $Version -BundledModels -OutputFile $OutputFile
