# Run the real-device resource soak driven by a video clip.
#
# Usage (from the repo root):
#   powershell -ExecutionPolicy Bypass -File tools/device-soak.ps1 -Model "$env:TEMP\yolo11n.onnx"
#   powershell -ExecutionPolicy Bypass -File tools/device-soak.ps1 -Model "$env:TEMP\yolo11n.onnx" -DurationMin 480 -IntervalSec 300
#
# Pushes the ONNX model and the dev clip into the app's external files dir
# (never into the repo or the APK), runs the instrumented soak, and prints the
# 60-second CSV plus the summary report. Both artifacts are gitignored.
#
# The test plays the clip through Media3, drives the production pump at the
# documented 2 FPS, and samples PSS, Java/native heap, Rust RSS, CPU, battery
# level/temperature, thermal status, app-data size, frames in/sampled/dropped
# and inference p50/p95 once per interval.
#
# Protocol and pass criteria: docs/performance.md sections 10.2-10.5.
# The run stops early on battery temperature >= 48 C or thermal SEVERE+, per
# section 10.3 step 7. Instrumented tests cannot run without an attached device.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Model,
    [string]$Video = "testdata\videos\istockphoto-1067633772-640_adpp_is.mp4",
    [ValidateRange(1, 720)][int]$DurationMin = 30,
    [ValidateRange(10, 600)][int]$IntervalSec = 60,
    [string]$Package = "id.warung.monitor",
    [string]$Filter = "id.warung.monitor.engine.DeviceSoakTest",
    [string]$OutDir
)

$ErrorActionPreference = "Stop"
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }
if (-not (Test-Path -LiteralPath $Model)) { throw "model not found: $Model" }
if (-not (Test-Path -LiteralPath $Video)) { throw "video not found: $Video" }


# A locked screen stops the instrumented activity window, so Media3 never gets
# a TextureView surface and the test fails for a reason unrelated to the app.
# `wm dismiss-keyguard` cannot unlock a secured Samsung keyguard over adb.
$keyguard = & $adb shell dumpsys window policy 2>$null | Select-String 'mIsShowing=true'
if ($keyguard) { throw "device is locked: unlock it physically, then rerun" }
$deviceLines = & $adb devices
# `adb devices` on Windows ends each line with CRLF, so a raw `$` never matches
# and the guard would reject an attached phone. Trim first.
$attached = $deviceLines | Where-Object { $_.Trim() -match '^\S+\s+device$' }
if (-not $attached) { throw "no device attached (check USB debugging)" }

$external = "/sdcard/Android/data/$Package/files"
& $adb shell mkdir -p $external | Out-Null
& $adb push $Model "$external/yolo11n.onnx"
if ($LASTEXITCODE -ne 0) { throw "adb push model failed" }
& $adb push $Video "$external/istockphoto-1067633772-640_adpp_is.mp4"
if ($LASTEXITCODE -ne 0) { throw "adb push video failed" }

# The test holds the activity window awake for the whole run; without that a
# locked screen destroys the capture texture and the soak records no frames.
# Battery drain is therefore an upper bound, and the screen-off half of the
# 10.3 protocol stays a manual follow-up on a device.
& $adb shell am instrument -w `
    -e class $Filter `
    -e soakDurationMin $DurationMin `
    -e soakIntervalSec $IntervalSec `
    "$Package.test/androidx.test.runner.AndroidJUnitRunner"
if ($LASTEXITCODE -ne 0) { throw "instrumentation failed" }

Write-Output "=== cctv-soak.txt ==="
& $adb shell cat "$external/cctv-soak.txt"
Write-Output "=== cctv-soak.csv ==="
& $adb shell cat "$external/cctv-soak.csv"

if ($OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    & $adb pull "$external/cctv-soak.csv" (Join-Path $OutDir "cctv-soak.csv")
    if ($LASTEXITCODE -ne 0) { throw "adb pull csv failed" }
    & $adb pull "$external/cctv-soak.txt" (Join-Path $OutDir "cctv-soak.txt")
    if ($LASTEXITCODE -ne 0) { throw "adb pull report failed" }
    Write-Output "saved to $OutDir"
}