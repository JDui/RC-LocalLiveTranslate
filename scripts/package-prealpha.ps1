#requires -Version 7.0
param([string]$Version = '0.1.0-prealpha.3', [string]$Apk = '', [switch]$BundledModels, [string]$OutputFile = '')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+-prealpha\.\d+$') { throw 'An explicit prealpha version is required' }
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
if (Test-Path -LiteralPath (Join-Path $tools 'jdk')) { $env:JAVA_HOME = Join-Path $tools 'jdk' }
if (!$Apk) { $Apk = Join-Path $root 'app/build/outputs/apk/release/app-release-unsigned.apk' }
$sdk = if (Test-Path -LiteralPath (Join-Path $tools 'android-sdk')) { Join-Path $tools 'android-sdk' } else { $env:ANDROID_HOME }
$suffix = if ($IsWindows) { '.exe' } else { '' }
$aapt = Join-Path $sdk "build-tools/36.0.0/aapt$suffix"
$apksigner = Join-Path $sdk ('build-tools/36.0.0/apksigner' + $(if ($IsWindows) { '.bat' } else { '' }))
$keytool = if (Test-Path -LiteralPath (Join-Path $tools 'jdk')) { Join-Path $tools "jdk/bin/keytool$suffix" } else { Join-Path $env:JAVA_HOME "bin/keytool$suffix" }
foreach ($required in @($Apk, $aapt, $apksigner, $keytool)) { if (!(Test-Path -LiteralPath $required)) { throw "Missing release input/tool: $required" } }
if ($BundledModels) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk))
    try {
        foreach ($id in @('sensevoice', 'silero-vad', 'speaker', 'hy-mt2')) {
            $entry = $archive.GetEntry("assets/bundled-models/$id.zip")
            if (!$entry -or $entry.Length -eq 0) { throw "Missing bundled model: $id" }
        }
    } finally { $archive.Dispose() }
}
$badging = & $aapt dump badging $Apk
if ($LASTEXITCODE -ne 0 -or !($badging | Select-String -SimpleMatch "name='com.jd.rc.localtranslate'") -or
    !($badging | Select-String -SimpleMatch "versionName='$Version'") -or ($badging | Select-String '^application-debuggable')) {
    throw 'Release APK identity/version/debuggability does not match the requested prealpha'
}
$releases = Join-Path $tools 'releases'
New-Item -ItemType Directory -Force -Path $releases | Out-Null
$variant = if ($BundledModels) { "-bundled" } else { "" }
$output = Join-Path $releases "RC-LocalLiveTranslate-$Version-arm64$variant.apk"
if ($OutputFile) { $output = [IO.Path]::GetFullPath($OutputFile) }
if (Test-Path -LiteralPath $output) { throw 'Release artifact exists; preserve it and use a new version for new artifacts' }
$signing = Join-Path $tools 'signing'
if (!(Test-Path -LiteralPath $signing)) {
    New-Item -ItemType Directory -Path $signing | Out-Null
    if ($IsWindows) {
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.SetOwner($user)
        foreach ($identity in @($user, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl',
                'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        }
        Set-Acl -LiteralPath $signing -AclObject $acl
    } else { & chmod 700 $signing; if ($LASTEXITCODE -ne 0) { throw 'Cannot protect signing directory' } }
}
$keystore = Join-Path $signing 'rc-localtranslate.jks'
$credentials = Join-Path $signing 'credentials.json'
if ((Test-Path -LiteralPath $keystore) -ne (Test-Path -LiteralPath $credentials)) { throw 'Incomplete signing material: preserve existing files; do not generate a replacement identity' }
$previousPassword = $env:RCLLT_SIGNING_PASSWORD
$temporary = "$output.signing-$([Guid]::NewGuid().ToString('N'))"
try {
    if (!(Test-Path -LiteralPath $keystore)) {
        $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $env:RCLLT_SIGNING_PASSWORD = $password
        & $keytool -genkeypair -storetype JKS -keystore $keystore -alias rc-localtranslate -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=RC-LocalLiveTranslate, O=JDui' -storepass:env RCLLT_SIGNING_PASSWORD -keypass:env RCLLT_SIGNING_PASSWORD -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing identity generation failed; preserve any generated material for inspection' }
        [IO.File]::WriteAllText($credentials, (@{ password = $password; alias = 'rc-localtranslate' } | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        if (!$IsWindows) { & chmod 600 $keystore $credentials; if ($LASTEXITCODE -ne 0) { throw 'Cannot protect signing files' } }
    }
    $config = Get-Content -LiteralPath $credentials -Raw | ConvertFrom-Json
    if (!$config.password -or $config.alias -cne 'rc-localtranslate') { throw 'Invalid local signing metadata' }
    $env:RCLLT_SIGNING_PASSWORD = $config.password
    & $apksigner sign --ks $keystore --ks-key-alias $config.alias --ks-pass env:RCLLT_SIGNING_PASSWORD --key-pass env:RCLLT_SIGNING_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $temporary $Apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & $apksigner verify --verbose $temporary
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    & (Join-Path $PSScriptRoot 'verify-native.ps1') -Apk $temporary
    Move-Item -LiteralPath $temporary -Destination $output
    Write-Output "Signed prealpha APK: $output"
} finally {
    $env:RCLLT_SIGNING_PASSWORD = $previousPassword
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
