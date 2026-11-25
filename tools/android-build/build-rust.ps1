# Build the Rust core for Android and stage everything Gradle needs.
#
# Usage (from the repo root):
#   powershell -ExecutionPolicy Bypass -File tools/android-build/build-rust.ps1
#   powershell -ExecutionPolicy Bypass -File tools/android-build/build-rust.ps1 -Configuration Debug
#
# Outputs (all gitignored):
#   android/app/src/main/jniLibs/<abi>/libcore_ffi.so
#   android/app/build/generated/uniffi/kotlin/id/warung/monitor/core/core_ffi.kt
#
# Requires: rustup target add <triple> for each ABI, ANDROID_NDK_HOME (or the
# usual SDK layout), cargo-ndk.

[CmdletBinding()]
param(
    [ValidateSet("Release", "Debug")]
    [string]$Configuration = "Release",
    [string]$ApiLevel = "26"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$rustRoot = Join-Path $repoRoot "rust-core"
$jniLibs = Join-Path $repoRoot "android\app\src\main\jniLibs"
$bindingsDir = Join-Path $repoRoot "android\app\build\generated\uniffi\kotlin"

$abis = @(
    @{ Triple = "aarch64-linux-android"; Abi = "arm64-v8a" },
    @{ Triple = "armv7-linux-androideabi"; Abi = "armeabi-v7a" },
    @{ Triple = "x86_64-linux-android"; Abi = "x86_64" }
)

$isDebug = $Configuration -eq "Debug"
$profile = if ($isDebug) { "debug" } else { "release" }

Push-Location $rustRoot
try {
    foreach ($target in $abis) {
        Write-Host "==> core-ffi $($target.Abi) ($profile)"
        if ($isDebug) {
            cargo ndk -t $target.Abi -P $ApiLevel build -p core-ffi
        }
        else {
            cargo ndk -t $target.Abi -P $ApiLevel build -p core-ffi --release
        }
        if ($LASTEXITCODE -ne 0) {
            throw "cargo ndk failed for $($target.Abi)"
        }

        $source = Join-Path $rustRoot "target\$($target.Triple)\$profile\libcore_ffi.so"
        if (-not (Test-Path -LiteralPath $source)) {
            throw "missing native library: $source"
        }

        $destination = Join-Path $jniLibs $target.Abi
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Copy-Item -LiteralPath $source -Destination (Join-Path $destination "libcore_ffi.so") -Force
    }

    # UniFFI reads metadata from `.symtab`, which the release profile strips.
    # Always generate bindings from an unstripped debug library.
    Write-Host "==> building unstripped library for binding generation"
    cargo ndk -t arm64-v8a -P $ApiLevel build -p core-ffi
    if ($LASTEXITCODE -ne 0) {
        throw "cargo ndk (debug) failed"
    }

    Write-Host "==> generating Kotlin bindings"
    New-Item -ItemType Directory -Force -Path $bindingsDir | Out-Null
    cargo run -q -p core-ffi --features cli --bin uniffi-bindgen -- generate `
        --library "target\aarch64-linux-android\debug\libcore_ffi.so" `
        --language kotlin `
        --out-dir $bindingsDir `
        --no-format
    if ($LASTEXITCODE -ne 0) {
        throw "uniffi-bindgen failed"
    }
}
finally {
    Pop-Location
}

Write-Host "Rust core staged for Android."
Write-Host "  jniLibs : $jniLibs"
Write-Host "  bindings: $bindingsDir"
