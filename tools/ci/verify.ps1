# One-command verification gate for cctv-stream.
#
# Usage (from the repo root):
#   powershell -ExecutionPolicy Bypass -File tools/ci/verify.ps1
#   powershell -ExecutionPolicy Bypass -File tools/ci/verify.ps1 -SkipNdk -SkipAudit
#
# Stages:
#   contracts : fmt, clippy, test, audit, deny for the standalone contracts crate
#   rust      : fmt, clippy, test for rust-core (workspace, rtsp, core-ffi cli)
#   ndk       : cross-compile core-ffi for the three Android ABIs
#   android   : Gradle unit tests, lint, assembleDebug
#
# Exit code is 0 only when every selected stage passes. Stages needing hardware
# (instrumented tests, real camera, soak) are intentionally not part of this
# script; see docs/status.md for what remains unverified.
#
# Audience: CI runners and humans. Agent sessions still run Gradle through the
# `gradle-run` wrapper (AGENTS.md section 2); use -SkipAndroid there and invoke
# the Gradle tasks through the wrapper instead.

[CmdletBinding()]
param(
    [switch]$SkipContracts,
    [switch]$SkipRust,
    [switch]$SkipNdk,
    [switch]$SkipAndroid,
    [switch]$SkipAudit
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$contractsRoot = Join-Path $repoRoot "contracts"
$rustRoot = Join-Path $repoRoot "rust-core"
$androidRoot = Join-Path $repoRoot "android"

$script:failures = New-Object System.Collections.Generic.List[string]

function Invoke-Step {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Exe,
        [string[]]$ExeArgs = @()
    )

    Write-Host ""
    Write-Host "==> $Name"
    Push-Location $WorkingDirectory
    try {
        & $Exe @ExeArgs
        $code = $LASTEXITCODE
        if ($null -eq $code) { $code = 0 }
    }
    catch {
        Write-Host ("    ERROR: " + $_.Exception.Message)
        $code = 1
    }
    finally {
        Pop-Location
    }

    if ($code -ne 0) {
        Write-Host ("    FAILED (exit " + $code + ")")
        $script:failures.Add($Name) | Out-Null
    }
    else {
        Write-Host "    ok"
    }
}

Write-Host "cctv-stream verification gate"
Write-Host ("repo: " + $repoRoot)

if (-not $SkipContracts) {
    Invoke-Step "contracts: fmt" $contractsRoot "cargo" @("fmt", "--all", "--", "--check")
    Invoke-Step "contracts: clippy" $contractsRoot "cargo" @("clippy", "--all-targets", "--", "-D", "warnings")
    Invoke-Step "contracts: test" $contractsRoot "cargo" @("test")
    if (-not $SkipAudit) {
        Invoke-Step "contracts: audit" $contractsRoot "cargo" @("audit")
        Invoke-Step "contracts: deny" $contractsRoot "cargo" @("deny", "check")
    }
}

if (-not $SkipRust) {
    Invoke-Step "rust: fmt" $rustRoot "cargo" @("fmt", "--all", "--", "--check")
    Invoke-Step "rust: clippy workspace" $rustRoot "cargo" @("clippy", "--workspace", "--all-targets", "--", "-D", "warnings")
    Invoke-Step "rust: test workspace" $rustRoot "cargo" @("test", "--workspace")
    Invoke-Step "rust: clippy rtsp" $rustRoot "cargo" @("clippy", "-p", "stream-engine", "--all-targets", "--features", "rtsp", "--", "-D", "warnings")
    Invoke-Step "rust: test rtsp" $rustRoot "cargo" @("test", "-p", "stream-engine", "--features", "rtsp")
    Invoke-Step "rust: clippy core-ffi cli" $rustRoot "cargo" @("clippy", "-p", "core-ffi", "--features", "cli", "--all-targets", "--", "-D", "warnings")
    if (-not $SkipAudit) {
        Invoke-Step "rust: audit" $rustRoot "cargo" @("audit")
        Invoke-Step "rust: deny" $rustRoot "cargo" @("deny", "check")
    }
}

if (-not $SkipNdk) {
    Invoke-Step "ndk: arm64-v8a" $rustRoot "cargo" @("ndk", "-t", "arm64-v8a", "-P", "26", "build", "-p", "core-ffi", "--release")
    Invoke-Step "ndk: armv7" $rustRoot "cargo" @("ndk", "-t", "armv7-linux-androideabi", "-P", "26", "build", "-p", "core-ffi", "--release")
    Invoke-Step "ndk: x86_64" $rustRoot "cargo" @("ndk", "-t", "x86_64", "-P", "26", "build", "-p", "core-ffi", "--release")
}

if (-not $SkipAndroid) {
    Invoke-Step "android: unit tests" $androidRoot ".\gradlew.bat" @(":core:model:test", ":core:network:test", ":core:ui:test", ":core:database:testDebugUnitTest", ":feature:cameras:testDebugUnitTest", ":feature:tables:testDebugUnitTest", ":feature:auth:testDebugUnitTest", ":feature:home:testDebugUnitTest", ":feature:onboarding:testDebugUnitTest", ":app:testDebugUnitTest", "--console=plain", "--no-scan")
    Invoke-Step "android: lint" $androidRoot ".\gradlew.bat" @(":app:lintDebug", "--console=plain", "--no-scan")
    Invoke-Step "android: assembleDebug" $androidRoot ".\gradlew.bat" @(":app:assembleDebug", "--console=plain", "--no-scan")
}

Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host "ALL SELECTED GATES PASSED"
    exit 0
}

Write-Host ("GATES FAILED (" + $script:failures.Count + "):")
foreach ($failure in $script:failures) {
    Write-Host ("  - " + $failure)
}
exit 1