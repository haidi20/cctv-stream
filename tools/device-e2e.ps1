# Run the real-device dev-mode end-to-end test.
#
# Usage (from the repo root):
#   powershell -ExecutionPolicy Bypass -File tools/device-e2e.ps1 -Model "$env:TEMP\yolo11n.onnx"
#
# Pushes the ONNX model and the dev clip into the app's external files dir
# (never into the repo or the APK), runs the instrumented pipeline test, and
# prints the report. Both artifacts are gitignored.
#
# The test plays the clip through Media3, drives the production pump at 10, 15,
# and 24 FPS, and feeds those frames to the real Rust engine with the real model.
# Because `push_frame` infers synchronously, the measured capture rate is
# bounded by inference cost (~4 FPS for YOLO11n at 640); the report prints the
# real captured/analysis rates instead of assuming the requested one.
# Instrumented tests cannot run without an attached device.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Model,
    [string]$Video = "testdata\videos\istockphoto-678973826-640_adpp_is.mp4",
    [string]$Package = "id.warung.monitor",
    [string]$Filter = "id.warung.monitor.engine.DevVideoPipelineTest"
)

$ErrorActionPreference = "Stop"
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }
if (-not (Test-Path -LiteralPath $Model)) { throw "model not found: $Model" }
if (-not (Test-Path -LiteralPath $Video)) { throw "video not found: $Video" }


$deviceLines = & $adb devices
# `adb devices` on Windows ends each line with CRLF, so a raw `$` never matches
# and the guard would reject an attached phone. Trim first.
$attached = $deviceLines | Where-Object { $_.Trim() -match '^\S+\s+device$' }
if (-not $attached) { throw "no device attached (check USB debugging)" }

$external = "/sdcard/Android/data/$Package/files"
& $adb shell mkdir -p $external | Out-Null
& $adb push $Model "$external/yolo11n.onnx"
if ($LASTEXITCODE -ne 0) { throw "adb push model failed" }
& $adb push $Video "$external/istockphoto-678973826-640_adpp_is.mp4"
if ($LASTEXITCODE -ne 0) { throw "adb push video failed" }

& $adb shell am instrument -w -e class $Filter "$Package.test/androidx.test.runner.AndroidJUnitRunner"
if ($LASTEXITCODE -ne 0) { throw "instrumentation failed" }

& $adb shell run-as $Package cat "$external/cctv-dev-e2e.txt"