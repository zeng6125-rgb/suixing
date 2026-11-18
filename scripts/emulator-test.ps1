<#
JAgent emulator controls. Uses an existing Release APK; never builds or selects a phone.
  ./scripts/emulator-test.ps1 Setup
  ./scripts/emulator-test.ps1 Install -ApkPath ./verify-out/JAgent-0.9.2.apk
  ./scripts/emulator-test.ps1 ResetFrames
  ./scripts/emulator-test.ps1 Report
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Setup', 'Start', 'Install', 'ResetFrames', 'Report')]
    [string]$Action = 'Start',
    [string]$ApkPath,
    [string]$SdkPath,
    [int]$Api = 36,
    [string]$FrameInput
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$avdName = "JAgent_Perf_API$Api"
$package = 'com.suixing.agent'

function Get-SdkPath {
    if ($SdkPath) { return $SdkPath }
    $properties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { return $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    return Join-Path $env:LOCALAPPDATA 'Android/Sdk'
}

function Invoke-Tool([string]$Path, [string[]]$Arguments) {
    & $Path @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Path" }
}

function Invoke-EmulatorAdb([string]$Serial, [string[]]$Arguments) {
    if ($Serial -notmatch '^emulator-\d+$') { throw 'Only a dedicated emulator may be selected.' }
    Invoke-Tool $script:adb (@('-s', $Serial) + $Arguments)
}

function Find-Emulator([switch]$IncludeOffline) {
    foreach ($line in (Invoke-Tool $script:adb @('devices'))) {
        if ($line -match '^(emulator-\d+)\s+(device|offline)\b') {
            $serial = $Matches[1]
            if ($Matches[2] -eq 'offline' -and !$IncludeOffline) { continue }
            $name = @(Invoke-EmulatorAdb $serial @('emu', 'avd', 'name'))[0]
            if ($name.Trim() -eq $avdName) { return $serial }
        }
    }
}

function Setup-Emulator {
    $cli = (Get-Command android -ErrorAction Stop).Source
    $image = "system-images/android-$Api/google_apis/x86_64"
    if (!(Test-Path -LiteralPath $script:emulator)) {
        Invoke-Tool $cli @("--sdk=$script:sdk", 'sdk', 'install', 'emulator') | Out-Host
    }
    if (!(Test-Path -LiteralPath (Join-Path $script:sdk $image))) {
        Invoke-Tool $cli @("--sdk=$script:sdk", 'sdk', 'install', $image) | Out-Host
    }
    $avds = @(Invoke-Tool $script:emulator @('-list-avds'))
    if ($avdName -notin $avds) {
        $manager = Join-Path $script:sdk 'cmdline-tools/latest/bin/avdmanager.bat'
        if (!$env:JAVA_HOME) {
            $jdk = Get-ChildItem -LiteralPath (Join-Path (Split-Path $script:sdk -Parent) 'jdk') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
        }
        'no' | & $manager create avd --name $avdName --package "system-images;android-$Api;google_apis;x86_64" --device pixel_5 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Could not create $avdName." }
    }
    $avdRoot = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android/avd' }
    $avdPath = (Get-Content -LiteralPath (Join-Path $avdRoot "$avdName.ini") | Where-Object { $_ -match '^path=' } | Select-Object -First 1).Substring(5)
    $configPath = Join-Path $avdPath 'config.ini'
    $config = [IO.File]::ReadAllText($configPath)
    $keyboardConfig = if ($config -match '(?m)^hw\.keyboard=') { $config -replace '(?m)^hw\.keyboard=[^\r\n]*', 'hw.keyboard=yes' } else { $config.TrimEnd() + "`nhw.keyboard=yes`n" }
    if ($keyboardConfig -ne $config) { [IO.File]::WriteAllText($configPath, $keyboardConfig, [Text.UTF8Encoding]::new($false)) }
    Write-Host "Ready: $avdName ($script:sdk)"
}

function Start-Emulator {
    Setup-Emulator
    $serial = Find-Emulator -IncludeOffline
    if (!$serial) {
        Invoke-Tool $script:emulator @('-accel-check') | Out-Host
        Start-Process -FilePath $script:emulator -ArgumentList @('-avd', $avdName, '-gpu', 'auto', '-memory', '2048', '-no-boot-anim', '-dns-server', '223.5.5.5') -WindowStyle Normal
    }
    $reconnected = $false
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        $serial = Find-Emulator -IncludeOffline
        if ($serial) {
            if ((Invoke-Tool $script:adb @('devices')) -match "^$serial\s+offline\b") {
                if (!$reconnected) {
                    Invoke-EmulatorAdb $serial @('reconnect') | Out-Host
                    $reconnected = $true
                }
            } else {
                $boot = @(Invoke-EmulatorAdb $serial @('shell', 'getprop', 'sys.boot_completed'))[0]
                if ($boot -and $boot.Trim() -eq '1') { return $serial }
            }
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out starting $avdName; check the emulator window."
}

function Save-FrameReport([string]$Raw, [string]$Serial) {
    function Metric([string]$Pattern) {
        $match = [regex]::Match($Raw, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)
        if ($match.Success) { return [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture) }
        return $null
    }
    $frames = Metric '^Total frames rendered:\s*(\d+)'
    $sample = $null -ne $frames -and $frames -gt 0
    $environment = $null
    if ($Serial -ne 'fixture') {
        $installed = (Invoke-EmulatorAdb $Serial @('shell', 'dumpsys', 'package', $package)) -join "`n"
        $environment = [ordered]@{
            versionName = [regex]::Match($installed, 'versionName=(\S+)').Groups[1].Value
            versionCode = [regex]::Match($installed, 'versionCode=(\d+)').Groups[1].Value
            api = ((Invoke-EmulatorAdb $Serial @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
            fingerprint = ((Invoke-EmulatorAdb $Serial @('shell', 'getprop', 'ro.build.fingerprint')) -join '').Trim()
            abi = ((Invoke-EmulatorAdb $Serial @('shell', 'getprop', 'ro.product.cpu.abi')) -join '').Trim()
            gpuRequestedMode = 'auto'
            gpuBackend = ((Invoke-EmulatorAdb $Serial @('shell', 'getprop', 'ro.hardware.egl')) -join '').Trim()
        }
    }
    $report = [ordered]@{
        capturedAt = [DateTimeOffset]::Now.ToString('o')
        avd = $avdName; serial = $Serial; package = $package
        sourcePackage = [regex]::Match($Raw, 'Graphics info for pid \d+ \[([^\]]+)\]').Groups[1].Value
        environment = $environment
        sampleAvailable = $sample; frames = $frames
        jankyFrames = if ($sample) { Metric '^Janky frames:\s*(\d+)' } else { $null }
        jankPercent = if ($sample) { Metric '^Janky frames:\s*\d+\s*\(([\d.]+)%\)' } else { $null }
        p50Ms = if ($sample) { Metric '^50th percentile:\s*([\d.]+)ms' } else { $null }
        p95Ms = if ($sample) { Metric '^95th percentile:\s*([\d.]+)ms' } else { $null }
        p99Ms = if ($sample) { Metric '^99th percentile:\s*([\d.]+)ms' } else { $null }
    }
    $directory = Join-Path $repoRoot 'verify-out/emulator-tests'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $base = Join-Path $directory ('frames-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff'))
    [IO.File]::WriteAllText("$base.txt", $Raw, [Text.UTF8Encoding]::new($false))
    $json = $report | ConvertTo-Json
    [IO.File]::WriteAllText("$base.json", $json, [Text.UTF8Encoding]::new($false))
    Write-Output $json
    if (!$sample) { Write-Host 'No frame samples. ResetFrames, interact with JAgent, then Report.' }
    Write-Host "Saved: $base.json"
}

if ($Action -eq 'Report' -and $FrameInput) {
    Save-FrameReport (Get-Content -LiteralPath $FrameInput -Raw) 'fixture'
    exit 0
}
$script:sdk = Get-SdkPath
$script:adb = Join-Path $script:sdk 'platform-tools/adb.exe'
$script:emulator = Join-Path $script:sdk 'emulator/emulator.exe'
switch ($Action) {
    'Setup' { Setup-Emulator }
    'Start' { $serial = Start-Emulator; Write-Host "Started: $avdName ($serial)" }
    'Install' {
        if (!$ApkPath) { $ApkPath = Join-Path $repoRoot 'app/build/outputs/apk/release/app-release.apk' }
        $apk = (Resolve-Path -LiteralPath $ApkPath).Path
        $serial = Start-Emulator
        Invoke-EmulatorAdb $serial @('install', '-r', $apk) | Out-Host
        Invoke-EmulatorAdb $serial @('shell', 'am', 'start', '-n', "$package/.MainActivity") | Out-Host
        Write-Host "Installed and opened: $apk ($serial)"
    }
    'ResetFrames' {
        $serial = Find-Emulator
        if (!$serial) { throw "Start $avdName first." }
        Invoke-EmulatorAdb $serial @('shell', 'dumpsys', 'gfxinfo', $package, 'reset') | Out-Null
        Write-Host 'Frame counters reset. Interact with JAgent, then Report.'
    }
    'Report' {
        $serial = Find-Emulator
        if (!$serial) { throw "Start $avdName first." }
        $raw = Invoke-EmulatorAdb $serial @('shell', 'dumpsys', 'gfxinfo', $package)
        Save-FrameReport ($raw -join "`n") $serial
    }
}
