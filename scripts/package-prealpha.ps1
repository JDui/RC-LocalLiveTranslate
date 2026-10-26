#requires -Version 7.0
# Compatibility entry point for historical prealpha build commands.
param([string]$Version = '0.1.0-prealpha.6', [string]$Apk = '', [switch]$BundledModels, [string]$OutputFile = '')
& (Join-Path $PSScriptRoot 'package-release.ps1') -Version $Version -Apk $Apk -BundledModels:$BundledModels -OutputFile $OutputFile
