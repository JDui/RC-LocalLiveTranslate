$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$testRoot = [IO.Path]::GetFullPath((Join-Path $root '.tools/model-package-tests'))
$directory = Join-Path $testRoot ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$checksum = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
$passed = 0
function Expect-Failure([scriptblock]$Action, [string]$Name) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    if (!$failed) { throw "Expected rejection: $Name" }
    $script:passed++
}
function Check-Package([string]$Path, $Files) {
    & (Join-Path $PSScriptRoot 'verify-model-package.ps1') -Package $Path -ExpectedId 'en-zh' -ExpectedVersion 'fixture' -ExpectedFiles $Files
}
function Make-Archive([string]$Name, $Manifest, $Entries) {
    $path = Join-Path $directory $Name
    $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $zip.CreateEntry('manifest.json'); $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write(($Manifest | ConvertTo-Json -Depth 8)) } finally { $writer.Dispose() }
        foreach ($name in $Entries) {
            $entry = $zip.CreateEntry($name); $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
            try { $writer.Write('abc') } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose(); $stream.Dispose() }
    return $path
}
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    . (Join-Path $PSScriptRoot 'model-assets.ps1')
    $cached = Join-Path $directory 'cached-model.onnx'
    [IO.File]::WriteAllText($cached, 'abc', [Text.UTF8Encoding]::new($false))
    $asset = @{ name = 'cached-model.onnx'; size = 3; sha256 = $checksum; url = 'https://example.org/unused-fixture' }
    Get-Asset $directory $asset -OfflineMode $true
    $passed++
    Expect-Failure { Get-Asset $directory @{ name = 'missing-model.onnx'; size = 3; sha256 = $checksum; url = 'https://example.org/unused-fixture' } -OfflineMode $true } 'offline missing model'
    Expect-Failure { Get-Asset $directory @{ name = 'cached-model.onnx'; size = 3; sha256 = ('0' * 64); url = 'https://example.org/unused-fixture' } -OfflineMode $true } 'corrupt cached model'
    Expect-Failure { Get-Asset $directory @{ name = '../escaped.onnx'; size = 3; sha256 = $checksum; url = 'https://example.org/unused-fixture' } } 'unsafe download name'
    Expect-Failure { Get-Asset $directory @{ name = 'unsafe-host.onnx'; size = 3; sha256 = $checksum; url = 'https://example.org/unused-fixture' } } 'unapproved download host'
    Expect-Failure { Get-Asset $directory @{ name = 'unsafe-scheme.onnx'; size = 3; sha256 = $checksum; url = 'http://github.com/unused-fixture' } } 'non-HTTPS download'
    foreach ($vocabs in @(@('vocab.enzh.spm'), @('srcvocab.enzh.spm', 'trgvocab.enzh.spm'))) {
        $source = Join-Path $directory "source-$($vocabs.Count)"
        New-Item -ItemType Directory -Path $source | Out-Null
        $names = @('model.enzh.bin', 'lex.enzh.bin') + $vocabs + @('SOURCE.json')
        foreach ($name in $names) { [IO.File]::WriteAllText((Join-Path $source $name), 'abc', [Text.UTF8Encoding]::new($false)) }
        $archive = Join-Path $directory "valid-$($vocabs.Count).zip"
        & (Join-Path $PSScriptRoot 'pack-model.ps1') -Id 'en-zh' -SourceDirectory $source -Version 'fixture' -License 'test-only' -SourceURL 'https://example.org/model-fixture' -OutputFile $archive
        $expected = @($names | ForEach-Object { @{ name = $_; size = 3; sha256 = $checksum } })
        Check-Package $archive $expected
        $passed++
        Expect-Failure { & (Join-Path $PSScriptRoot 'pack-model.ps1') -Id 'en-zh' -SourceDirectory $source -Version 'fixture' -License 'test-only' -SourceURL 'https://example.org/model-fixture' -OutputFile $archive } 'overwrite existing package'
    }
    $manifest = @{ id = 'en-zh'; version = 'fixture'; abi = 'arm64-v8a'; license = 'test-only'; sourceURL = 'https://example.org/model-fixture';
        files = @(@{ path = 'model.enzh.bin'; size = 3; sha256 = $checksum }, @{ path = 'SOURCE.json'; size = 3; sha256 = $checksum }) }
    $expected = @(@{ name = 'model.enzh.bin'; size = 3; sha256 = $checksum })
    $duplicate = Make-Archive 'duplicate.zip' $manifest @('model.enzh.bin', 'model.enzh.bin', 'SOURCE.json')
    Expect-Failure { Check-Package $duplicate $expected } 'duplicate ZIP entry'
    $traversal = Make-Archive 'traversal.zip' $manifest @('../escaped', 'model.enzh.bin', 'SOURCE.json')
    Expect-Failure { Check-Package $traversal $expected } 'path traversal'
    $extra = Make-Archive 'extra.zip' $manifest @('extra.bin', 'model.enzh.bin', 'SOURCE.json')
    Expect-Failure { Check-Package $extra $expected } 'undeclared entry'
    $missing = Make-Archive 'missing.zip' $manifest @('model.enzh.bin')
    Expect-Failure { Check-Package $missing $expected } 'missing source notice'
    $valid = Make-Archive 'identity.zip' $manifest @('model.enzh.bin', 'SOURCE.json')
    Expect-Failure { Check-Package $valid @(@{ name = 'model.enzh.bin'; size = 3; sha256 = ('0' * 64) }) } 'upstream hash mismatch'
    $manifest.files[0].sha256 = '0' * 64
    $corrupt = Make-Archive 'corrupt.zip' $manifest @('model.enzh.bin', 'SOURCE.json')
    Expect-Failure { Check-Package $corrupt $expected } 'corrupt manifest digest'
    $manifest.files[0].sha256 = $checksum; $manifest.files[0].size = 4
    $size = Make-Archive 'size.zip' $manifest @('model.enzh.bin', 'SOURCE.json')
    Expect-Failure { Check-Package $size $expected } 'incorrect length'
    Write-Output "Model packaging checks passed: $passed"
} finally {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (!$resolved.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
