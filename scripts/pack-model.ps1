param(
    [Parameter(Mandatory)][ValidateSet('sensevoice', 'silero-vad', 'speaker', 'en-zh', 'ja-en')][string]$Id,
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$License,
    [Parameter(Mandatory)][string]$SourceURL,
    [Parameter(Mandatory)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
$entries = [ordered]@{}
function Find-One([string]$Pattern) {
    $matches = @(Get-ChildItem -LiteralPath $source -File | Where-Object { $_.Name -like $Pattern })
    if ($matches.Count -ne 1) { throw "Expected exactly one $Pattern in $source" }
    return $matches[0]
}
switch ($Id) {
    'sensevoice' { $entries['model.onnx'] = Find-One '*.onnx'; $entries['tokens.txt'] = Find-One 'tokens.txt' }
    'silero-vad' { $entries['silero_vad.onnx'] = Find-One '*.onnx' }
    'speaker' { $entries['speaker.onnx'] = Find-One '*.onnx' }
    default {
        $model = Find-One 'model.*.bin'; $shortlist = Find-One 'lex.*.bin'
        $vocabs = @(Get-ChildItem -LiteralPath $source -File | Where-Object { $_.Name -like '*vocab*.spm' })
        if ($vocabs.Count -notin @(1, 2)) { throw 'Expected one shared vocabulary or source and target vocabularies' }
        $entries[$model.Name] = $model; $entries[$shortlist.Name] = $shortlist
        foreach ($vocab in $vocabs) { $entries[$vocab.Name] = $vocab }
    }
}
foreach ($notice in @(Get-ChildItem -LiteralPath $source -File | Where-Object { $_.Name -match '^(LICENSE|NOTICE|COPYING|SOURCE)(\..*)?$' })) {
    $entries[$notice.Name] = $notice
}
$total = 0L
$files = @($entries.GetEnumerator() | ForEach-Object {
    $file = $_.Value; $total += $file.Length
    if ($file.Length -lt 1 -or $file.Length -gt 512MB) { throw "Model asset exceeds import limits: $($file.Name)" }
    # Required integrity metadata for model packaging, not source-code verification.
    @{ path = $_.Key; size = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
if ($total -gt 1GB -or $files.Count -gt 64) { throw 'Model package exceeds import limits' }
$manifest = @{ id = $Id; version = $Version; abi = 'arm64-v8a'; license = $License; sourceURL = $SourceURL; files = $files }
$json = [System.Text.Encoding]::UTF8.GetBytes(($manifest | ConvertTo-Json -Depth 6))
if ($json.Length -gt 64KB -or $total + $json.Length -gt 1GB) { throw 'Manifest or complete uncompressed package exceeds import limits' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::Open($OutputFile, [System.IO.FileMode]::CreateNew)
$zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $entry = $zip.CreateEntry('manifest.json'); $out = $entry.Open()
    try { $out.Write($json, 0, $json.Length) } finally { $out.Dispose() }
    foreach ($pair in $entries.GetEnumerator()) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $pair.Value.FullName, $pair.Key,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose(); $stream.Dispose() }
Write-Output "Model package created: $OutputFile ($Id, $total bytes). Weights and their license require separate validation."
