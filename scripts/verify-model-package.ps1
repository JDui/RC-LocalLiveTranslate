param(
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$ExpectedId,
    [Parameter(Mandatory)][string]$ExpectedVersion,
    [Parameter(Mandatory)][object[]]$ExpectedFiles
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Package).Path)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    if (@($names | Select-Object -Unique).Count -ne $names.Count) { throw 'Duplicate ZIP entries' }
    if ($names.Count -gt 64 -or ($zip.Entries | Measure-Object -Property Length -Sum).Sum -gt 1GB) { throw 'Package exceeds import limits' }
    foreach ($name in $names) {
        if ($name -notmatch '^[a-zA-Z0-9_.-]+$' -or $name -in @('.', '..')) { throw "Unexpected ZIP path: $name" }
    }
    $entry = $zip.GetEntry('manifest.json')
    if (!$entry -or $entry.Length -gt 64KB) { throw 'Missing or oversized manifest' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($manifest.id -cne $ExpectedId -or $manifest.version -cne $ExpectedVersion -or $manifest.abi -cne 'arm64-v8a' -or
        [string]::IsNullOrWhiteSpace($manifest.license) -or [string]::IsNullOrWhiteSpace($manifest.sourceURL)) { throw 'Unexpected package identity' }
    if (@($manifest.files).Count -lt 1 -or @($manifest.files).Count -gt 64) { throw 'Invalid manifest asset count' }
    $declared = @('manifest.json')
    foreach ($spec in $manifest.files) {
        if ($spec.path -in $declared) { throw 'Duplicate manifest path' }
        $declared += $spec.path
        $asset = $zip.GetEntry($spec.path)
        if (!$asset -or $asset.Length -ne $spec.size -or $asset.Length -gt 512MB -or $asset.Length -lt 1 -or $spec.sha256 -notmatch '^[a-f0-9]{64}$') { throw "Invalid asset: $($spec.path)" }
        $digest = [Security.Cryptography.SHA256]::Create()
        $stream = $asset.Open()
        try { $actual = [BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose(); $digest.Dispose() }
        if ($actual -ne $spec.sha256) { throw "Corrupt ZIP asset: $($spec.path)" }
    }
    if (@(Compare-Object $names $declared).Count -ne 0) { throw 'Undeclared or missing ZIP entries' }
    foreach ($expected in $ExpectedFiles) {
        $matches = @($manifest.files | Where-Object { $_.path -ceq $expected.name })
        if ($matches.Count -ne 1 -or $matches[0].size -ne $expected.size -or $matches[0].sha256 -cne $expected.sha256) { throw "Upstream identity mismatch: $($expected.name)" }
    }
    if (!$zip.GetEntry('SOURCE.json')) { throw 'Package lacks model source notice' }
    Write-Output "Verified package: $ExpectedId / $ExpectedVersion / $($manifest.files.Count) assets"
} finally { $zip.Dispose() }
