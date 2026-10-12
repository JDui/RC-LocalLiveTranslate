function Assert-Arm64Elf16Kb([byte[]]$Bytes, [string]$Name) {
    if ($Bytes.Length -lt 64 -or $Bytes[0] -ne 127 -or $Bytes[1] -ne 69 -or $Bytes[2] -ne 76 -or
        $Bytes[3] -ne 70 -or $Bytes[4] -ne 2 -or $Bytes[5] -ne 1 -or [BitConverter]::ToUInt16($Bytes, 18) -ne 183) {
        throw "Not an ARM64 ELF64 little-endian library: $Name"
    }
    $phoff = [BitConverter]::ToUInt64($Bytes, 32)
    $phsize = [BitConverter]::ToUInt16($Bytes, 54)
    $phcount = [BitConverter]::ToUInt16($Bytes, 56)
    if ($phsize -lt 56 -or $phcount -eq 0 -or $phoff -gt $Bytes.Length -or
        $phsize * [long]$phcount -gt $Bytes.Length - [long]$phoff) { throw "Invalid ELF program header table: $Name" }
    $loads = 0
    for ($i = 0; $i -lt $phcount; $i++) {
        $offset = [int]($phoff + $i * $phsize)
        if ([BitConverter]::ToUInt32($Bytes, $offset) -ne 1) { continue }
        $loads++
        $fileOffset = [BitConverter]::ToUInt64($Bytes, $offset + 8)
        $virtualAddress = [BitConverter]::ToUInt64($Bytes, $offset + 16)
        $alignment = [BitConverter]::ToUInt64($Bytes, $offset + 48)
        if ($alignment -lt 16384 -or $fileOffset % 16384 -ne $virtualAddress % 16384) {
            throw "PT_LOAD is not 16 KB aligned: $Name"
        }
    }
    if (!$loads) { throw "ELF contains no loadable segments: $Name" }
    Write-Output "ELF 16 KB PASS: $Name"
}
