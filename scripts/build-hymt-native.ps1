#requires -Version 7.0
param([int]$Jobs = 2, [ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
$source = Join-Path $tools 'llama-source'
$commit = '1e411d8f5a1e23525fa3265dfb4bd76265465397'
if (!(Test-Path -LiteralPath (Join-Path $source '.git'))) {
    git clone --no-checkout --filter=blob:none https://github.com/ggml-org/llama.cpp.git $source
    if ($LASTEXITCODE -ne 0) { throw 'Cannot acquire llama.cpp source' }
    git -c "safe.directory=$($source.Replace('\','/'))" -C $source fetch --depth 1 origin $commit
    if ($LASTEXITCODE -ne 0) { throw 'Cannot acquire pinned STQ source' }
    git -c "safe.directory=$($source.Replace('\','/'))" -C $source checkout --detach $commit
    if ($LASTEXITCODE -ne 0) { throw 'Cannot materialize STQ source' }
}
$actual = git -c "safe.directory=$($source.Replace('\','/'))" -C $source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -cne $commit) { throw 'Unexpected llama.cpp revision; preserve the checkout' }
$sdk = if (Test-Path (Join-Path $tools 'android-sdk')) { Join-Path $tools 'android-sdk' } else { $env:ANDROID_HOME }
$suffix = if ($IsWindows) { '.exe' } else { '' }
$cmake = Join-Path $sdk "cmake/3.22.1/bin/cmake$suffix"
$ninja = Join-Path $sdk "cmake/3.22.1/bin/ninja$suffix"
$build = Join-Path $tools $(if ($Abi -eq 'arm64-v8a') { 'hymt-android' } else { 'hymt-android-x86' })
& $cmake -S (Join-Path $root 'app/src/main/cpp') -B $build -G Ninja "-DHY_SOURCE=$($source.Replace('\','/'))" `
    "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$sdk/ndk/29.0.14206865/build/cmake/android.toolchain.cmake" `
    "-DANDROID_ABI=$Abi" '-DANDROID_PLATFORM=android-28' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { throw 'Hy-MT2 native configuration failed' }
& $cmake --build $build --target rc_hymt --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Hy-MT2 JNI build failed' }
$family = if ($Abi -eq 'arm64-v8a') { 'hymt' } else { 'hymt-validation' }
$native = Join-Path $tools "native/$family/jniLibs/$Abi"
New-Item -ItemType Directory -Force $native | Out-Null
Copy-Item -LiteralPath (Join-Path $build 'librc_hymt.so') -Destination $native
$licenses = Join-Path $tools "native/$family/licenses"
if ($Abi -eq 'arm64-v8a') { $licenses = Join-Path $tools 'native/assets/licenses/hymt' }
New-Item -ItemType Directory -Force $licenses | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $licenses 'llama-MIT.txt')
Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $licenses 'ggml-MIT.txt')
@{ source = "https://github.com/ggml-org/llama.cpp/tree/$commit"; commit = $commit;
    stqKernel = 'https://github.com/ggml-org/llama.cpp/pull/22836'; gpu = $false; network = $false;
    readerAdapter = 'app/src/main/cpp/hy-stq-reader.cmake'; storedStqType = 42; runtimeStqType = 43; abi = $Abi;
    contextTokens = 1024; maxNewTokens = 256; deadlineSeconds = 60; sampling = 'greedy' } |
    ConvertTo-Json -Depth 4 | Set-Content (Join-Path $licenses 'SOURCE.txt')
Write-Output "Hy-MT2 STQ $Abi JNI ready; no network tools or model weights bundled."
