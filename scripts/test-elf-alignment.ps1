$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'elf-alignment.ps1')
$valid = [byte[]]::new(120)
([byte[]]@(127,69,76,70,2,1)).CopyTo($valid, 0)
[BitConverter]::GetBytes([uint16]183).CopyTo($valid, 18)
[BitConverter]::GetBytes([uint64]64).CopyTo($valid, 32)
[BitConverter]::GetBytes([uint16]56).CopyTo($valid, 54)
[BitConverter]::GetBytes([uint16]1).CopyTo($valid, 56)
[BitConverter]::GetBytes([uint32]1).CopyTo($valid, 64)
[BitConverter]::GetBytes([uint64]16384).CopyTo($valid, 112)
Assert-Arm64Elf16Kb -Bytes $valid -Name 'synthetic-aligned'
$cases = @(
    @{ name = '4KB'; edit = { param($b) [BitConverter]::GetBytes([uint64]4096).CopyTo($b, 112) } },
    @{ name = 'offset'; edit = { param($b) [BitConverter]::GetBytes([uint64]1).CopyTo($b, 72) } },
    @{ name = 'wrong-machine'; edit = { param($b) [BitConverter]::GetBytes([uint16]62).CopyTo($b, 18) } },
    @{ name = 'out-of-bounds'; edit = { param($b) [BitConverter]::GetBytes([uint64]1000).CopyTo($b, 32) } },
    @{ name = 'short-header'; edit = { param($b) [BitConverter]::GetBytes([uint16]1).CopyTo($b, 54) } },
    @{ name = 'no-load'; edit = { param($b) [BitConverter]::GetBytes([uint32]0).CopyTo($b, 64) } }
)
foreach ($case in $cases) {
    $bytes = [byte[]]$valid.Clone()
    & $case.edit $bytes
    $rejected = $false
    try { Assert-Arm64Elf16Kb -Bytes $bytes -Name $case.name | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw "ELF validator accepted invalid fixture: $($case.name)" }
}
$rejected = $false
try { Assert-Arm64Elf16Kb -Bytes ([byte[]]::new(8)) -Name 'truncated' | Out-Null } catch { $rejected = $true }
if (!$rejected) { throw 'ELF validator accepted a truncated fixture' }
Write-Output '8 in-memory ELF alignment checks PASS (synthetic headers, not execution proof)'
