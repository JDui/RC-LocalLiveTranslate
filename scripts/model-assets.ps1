function Assert-Asset([string]$Path, $Spec) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing model asset: $Path" }
    if ((Get-Item -LiteralPath $Path).Length -ne $Spec.size) { throw "Model asset size mismatch: $Path" }
    # Required model/package integrity, not ordinary source-file change verification.
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Spec.sha256) {
        throw "Model asset SHA-256 mismatch: $Path"
    }
}
function Get-Asset([string]$Directory, $Spec, [bool]$OfflineMode = $false) {
    if ($Spec.name -notmatch '^[a-zA-Z0-9_.-]+$' -or $Spec.name -in @('.', '..')) { throw 'Unsafe asset name' }
    $target = Join-Path $Directory $Spec.name
    if (Test-Path -LiteralPath $target) { Assert-Asset $target $Spec; return }
    if ($OfflineMode) { throw "Offline preparation needs cached asset: $target" }
    $uri = [Uri]$Spec.url
    if ($uri.Scheme -ne 'https' -or $uri.Host -notin @('firefox-settings-attachments.cdn.mozilla.net', 'raw.githubusercontent.com', 'github.com', 'huggingface.co')) {
        throw "Unexpected model source: $uri"
    }
    $partial = "$target.download-$([Guid]::NewGuid().ToString('N'))"
    try {
        Write-Output "Downloading $($Spec.name) ($($Spec.size) bytes)"
        Invoke-WebRequest -Uri $uri -OutFile $partial -TimeoutSec 300
        Assert-Asset $partial $Spec
        Move-Item -LiteralPath $partial -Destination $target
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
    }
}
