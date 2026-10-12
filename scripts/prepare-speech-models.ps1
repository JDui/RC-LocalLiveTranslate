param([switch]$Offline, [switch]$Use2025Candidate)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'speech-models.lock.json') -Raw | ConvertFrom-Json
if ($Use2025Candidate) { $lock.models = @($lock.asrAlternatives[0]) }
$cache = Join-Path $root '.tools/models/speech'
$downloads = Join-Path $cache 'downloads'
$packages = Join-Path $cache $(if ($Use2025Candidate) { 'rejected-packages' } else { 'packages' })
New-Item -ItemType Directory -Path $downloads, $packages -Force | Out-Null
. (Join-Path $PSScriptRoot 'model-assets.ps1')
foreach ($model in $lock.models) {
    Get-Asset $downloads $model.download -OfflineMode $Offline
    $directory = Join-Path $cache "$($model.id)/$($model.version)"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    if ($model.id -eq 'sensevoice') {
        $missing = @($model.files | Where-Object { !(Test-Path -LiteralPath (Join-Path $directory $_.name)) })
        if ($missing.Count -gt 0) {
            $archive = Join-Path $downloads $model.download.name
            $members = @($model.files | ForEach-Object { $_.archivePath })
            foreach ($member in $members) {
                if ($member -notmatch '^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$') { throw 'Unsafe archive member' }
            }
            $listing = @(& tar -tvf $archive @members)
            if ($LASTEXITCODE -ne 0 -or $listing.Count -ne $members.Count -or @($listing | Where-Object { !$_.StartsWith('-') }).Count -gt 0) {
                throw 'Expected only regular model files in ASR archive'
            }
            $stage = Join-Path $cache ".extract-$([Guid]::NewGuid().ToString('N'))"
            New-Item -ItemType Directory -Path $stage | Out-Null
            try {
                & tar -xf $archive -C $stage @members
                if ($LASTEXITCODE -ne 0) { throw 'ASR extraction failed' }
                foreach ($asset in $missing) {
                    $extracted = Join-Path $stage $asset.archivePath
                    Assert-Asset $extracted $asset
                    Copy-Item -LiteralPath $extracted -Destination (Join-Path $directory $asset.name)
                }
            } finally {
                $resolved = [IO.Path]::GetFullPath($stage)
                if (!$resolved.StartsWith([IO.Path]::GetFullPath($cache) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe extraction cleanup' }
                Remove-Item -LiteralPath $resolved -Recurse -Force
            }
        }
    } else {
        $target = Join-Path $directory $model.files[0].name
        if (!(Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath (Join-Path $downloads $model.download.name) -Destination $target }
    }
    foreach ($asset in $model.files) { Assert-Asset (Join-Path $directory $asset.name) $asset }
    $notices = @($model.licenseKeys | ForEach-Object { $lock.licenses.$_ })
    foreach ($notice in $notices) {
        Get-Asset $downloads $notice -OfflineMode $Offline
        Copy-Item -LiteralPath (Join-Path $downloads $notice.name) -Destination (Join-Path $directory $notice.name)
    }
    $source = [ordered]@{
        modelId = $model.id; version = $model.version; license = $model.license
        authors = $model.authors; sourceURL = $model.sourceURL; licenseEvidence = $model.licenseEvidence
        redistributionStatus = $model.redistributionStatus; download = $model.download; files = $model.files
        licenseFiles = $notices
        modifications = 'No weight or token byte changes. Runtime filename normalization only; ONNX conversion was performed upstream.'
        status = 'Local evaluation candidate. Android native inference, accuracy and performance remain unvalidated.'
    }
    [IO.File]::WriteAllText((Join-Path $directory 'SOURCE.json'), ($source | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    $expected = @($model.files + $notices)
    $output = Join-Path $packages "$($model.id)-$($model.version).zip"
    if (Test-Path -LiteralPath $output) {
        & (Join-Path $PSScriptRoot 'verify-model-package.ps1') -Package $output -ExpectedId $model.id -ExpectedVersion $model.version -ExpectedFiles $expected
        continue
    }
    $temporary = Join-Path $packages ".pack-$([Guid]::NewGuid().ToString('N')).zip"
    try {
        & (Join-Path $PSScriptRoot 'pack-model.ps1') -Id $model.id -SourceDirectory $directory -Version $model.version -License $model.license -SourceURL $model.sourceURL -OutputFile $temporary
        & (Join-Path $PSScriptRoot 'verify-model-package.ps1') -Package $temporary -ExpectedId $model.id -ExpectedVersion $model.version -ExpectedFiles $expected
        Move-Item -LiteralPath $temporary -Destination $output
        Write-Output "Ready for local evaluation import: $output"
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
