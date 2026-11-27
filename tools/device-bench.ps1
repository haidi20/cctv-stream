# Run the on-device inference benchmark.
#
# Usage (from the repo root):
#   powershell -ExecutionPolicy Bypass -File tools/device-bench.ps1 -Model "$env:TEMP\yolo11n.onnx"
#
# Pushes the ONNX model into the app's external files dir (never into the repo
# or the APK), runs the instrumented timing test, and prints the report.
# The model itself is not committed: *.onnx is gitignored.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Model,
    [string]$Package = "id.warung.monitor",
    [string]$Filter = "id.warung.monitor.engine.DeviceInferenceBenchmarkTest"
)

# adb writes progress ("1 file pushed...") to stderr, and PowerShell maps stderr
# to an ErrorRecord. With $ErrorActionPreference = "Stop" a *successful* push
# would abort the run, so this script keeps "Continue" and detects failures from
# $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }
if (-not (Test-Path -LiteralPath $Model)) { throw "model not found: $Model" }

& $adb shell run-as $Package mkdir -p files *> $null
& $adb push $Model "/sdcard/Android/data/$Package/files/yolo11n.onnx" *> $null
if ($LASTEXITCODE -ne 0) { throw "adb push failed" }

& $adb shell am instrument -w -e class $Filter "$Package.test/androidx.test.runner.AndroidJUnitRunner" *>&1
if ($LASTEXITCODE -ne 0) { throw "instrumentation failed" }

& $adb shell cat "/sdcard/Android/data/$Package/files/cctv-benchmark.txt"