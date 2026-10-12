$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
if (-not (Test-Path (Join-Path $toolsRoot 'jdk'))) {
    $release = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $jdkUrl = $release[0].binary.package.link
    Invoke-WebRequest $jdkUrl -OutFile (Join-Path $toolsRoot 'jdk.zip')
    Expand-Archive -LiteralPath (Join-Path $toolsRoot 'jdk.zip') -DestinationPath (Join-Path $toolsRoot 'java') -Force
    $jdkDir = Get-ChildItem (Join-Path $toolsRoot 'java') -Directory | Select-Object -First 1
    Move-Item -LiteralPath $jdkDir.FullName -Destination (Join-Path $toolsRoot 'jdk')
}
if (-not (Test-Path (Join-Path $toolsRoot 'gradle-8.11.1'))) {
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' -OutFile (Join-Path $toolsRoot 'gradle.zip')
    Expand-Archive -LiteralPath (Join-Path $toolsRoot 'gradle.zip') -DestinationPath $toolsRoot -Force
}
if (-not (Test-Path (Join-Path $toolsRoot 'android-sdk/cmdline-tools/latest/bin/sdkmanager.bat'))) {
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile (Join-Path $toolsRoot 'android-cli.zip')
    $cliRoot = Join-Path $toolsRoot 'android-sdk/cmdline-tools'
    Expand-Archive -LiteralPath (Join-Path $toolsRoot 'android-cli.zip') -DestinationPath $cliRoot -Force
    Move-Item -LiteralPath (Join-Path $cliRoot 'cmdline-tools') -Destination (Join-Path $cliRoot 'latest')
}
$env:JAVA_HOME = Join-Path $toolsRoot 'jdk'
$env:ANDROID_HOME = Join-Path $toolsRoot 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-home'
1..20 | ForEach-Object { 'y' } | & "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --licenses
if ($LASTEXITCODE -ne 0) { throw 'SDK license acceptance failed' }
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.14206865' 'cmake;3.22.1'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
Write-Host 'Android build tools ready in .tools.'
