param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'translation-models.lock.json') -Raw | ConvertFrom-Json
$cache = Join-Path $root '.tools/models/mozilla'
$packages = Join-Path $cache 'packages'
New-Item -ItemType Directory -Path $packages -Force | Out-Null

. (Join-Path $PSScriptRoot 'model-assets.ps1')

foreach ($model in $lock.models) {
    $directory = Join-Path $cache "$($model.id)/$($model.version)"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    foreach ($asset in $model.files) { Get-Asset $directory $asset -OfflineMode $Offline }
    Get-Asset $directory $lock.licenseFile -OfflineMode $Offline
    $source = [ordered]@{
        modelId = $model.id; version = $model.version; license = $model.license
        sourceLanguage = $model.source; targetLanguage = $model.target
        catalogSource = $lock.catalogSource; licenseEvidence = $lock.licenseEvidence
        licenseSource = $lock.licenseFile.url
        weightsSource = 'https://github.com/mozilla/firefox-translations-models'
        upstreamFiles = $model.files
        modifications = 'None. Model weights and vocabulary bytes are unchanged.'
        status = 'Host-prepared import package. Android native inference and translation quality are not yet validated.'
    }
    [IO.File]::WriteAllText((Join-Path $directory 'SOURCE.json'), ($source | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $output = Join-Path $packages "$($model.id)-$($model.version).zip"
    # Never treat an existing ZIP as trusted merely because its file name matches.
    if (Test-Path -LiteralPath $output) {
        & (Join-Path $PSScriptRoot 'verify-model-package.ps1') -Package $output -ExpectedId $model.id -ExpectedVersion $model.version -ExpectedFiles @($model.files + $lock.licenseFile)
        continue
    }
    $temporary = Join-Path $packages ".pack-$([Guid]::NewGuid().ToString('N')).zip"
    try {
        & (Join-Path $PSScriptRoot 'pack-model.ps1') -Id $model.id -SourceDirectory $directory -Version $model.version -License $model.license -SourceURL $lock.catalogSource -OutputFile $temporary
        & (Join-Path $PSScriptRoot 'verify-model-package.ps1') -Package $temporary -ExpectedId $model.id -ExpectedVersion $model.version -ExpectedFiles @($model.files + $lock.licenseFile)
        Move-Item -LiteralPath $temporary -Destination $output
        Write-Output "Ready for local SAF import: $output"
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
