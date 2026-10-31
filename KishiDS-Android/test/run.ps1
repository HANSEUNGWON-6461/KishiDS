<#
.SYNOPSIS
  Runs the plain-JVM checks of the Android app's core (no phone, no emulator): config block, live protocol, telemetry, profiles,
  stock-firmware import, calibration and the DFU transfer against a simulated bootloader.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
$out = Join-Path $env:TEMP "kishids-selftest"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $out | Out-Null
$java = "$root\app\src\main\java\com\electrotamp\kishids"
$sources = @(Get-ChildItem "$java\core", "$java\v2pro" -Filter *.java | ForEach-Object { $_.FullName }) + "$PSScriptRoot\SelfTest.java", "$PSScriptRoot\V2ProSelfTest.java"
& javac --release 8 -Xlint:-options -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { exit 1 }
& java -cp $out V2ProSelfTest
if ($LASTEXITCODE -ne 0) { exit 1 }
$fw = "$root\..\firmware-research\ds4-firmware\kishi_ds4.bin"
$stock = "$root\..\firmware-research\official-images\assets_legacy_02.70.bin"   # optional: only present with Razer's app extracted
$argList = @($fw); if (Test-Path $stock) { $argList += $stock }
& java -cp $out SelfTest @argList
exit $LASTEXITCODE
