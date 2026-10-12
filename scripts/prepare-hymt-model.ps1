param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lock = Get-Content (Join-Path $PSScriptRoot 'hymt-model.lock.json') -Raw | ConvertFrom-Json
$source = Join-Path $root '.tools/models/hymt/source'
$packages = Join-Path $root '.tools/models/hymt/packages'
New-Item -ItemType Directory -Force $source, $packages | Out-Null
foreach ($spec in $lock.files) {
    $path = Join-Path $source $spec.name
    if (!(Test-Path -LiteralPath $path)) {
        if ($Offline) { throw 'Missing Hy-MT2 offline weight cache' }
        Invoke-WebRequest "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/$($lock.revision)/$($spec.upstreamName)" -OutFile "$path.part"
        Move-Item -LiteralPath "$path.part" -Destination $path
    }
    if ((Get-Item -LiteralPath $path).Length -ne $spec.size -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $spec.sha256) { throw 'Hy-MT2 integrity mismatch' }
}
$license = Join-Path $source 'LICENSE.txt'
if (!(Test-Path -LiteralPath $license)) {
    if ($Offline) { throw 'Missing Hy-MT2 license in offline cache' }
    Invoke-WebRequest "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/$($lock.revision)/LICENSE.txt" -OutFile $license
}
if ((Get-Content -LiteralPath $license -Raw) -notmatch 'licensed under the Apache License, Version 2.0') { throw 'Unexpected model license' }
$lock | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $source 'SOURCE.json')
$output = Join-Path $packages "hy-mt2-$($lock.version).zip"
if (!(Test-Path -LiteralPath $output)) {
    & (Join-Path $PSScriptRoot 'pack-model.ps1') -Id hy-mt2 -SourceDirectory $source -Version $lock.version -License $lock.license -SourceURL $lock.sourceURL -OutputFile $output
}
& (Join-Path $PSScriptRoot 'verify-model-package.ps1') -Package $output -ExpectedId hy-mt2 -ExpectedVersion $lock.version -ExpectedFiles $lock.files
Write-Output "Hy-MT2 local import package ready: $output"
