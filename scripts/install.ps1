param([string]$Serial, [switch]$ConnectGptService)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskApk = Join-Path $taskRoot 'outputs\mediary-0.1.0-debug.apk'
if (-not (Test-Path -LiteralPath $taskApk)) { throw 'Build the APK first: .\scripts\build.ps1' }
$taskAdb = Join-Path $taskRoot 'work\tools\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $taskAdb)) { $taskAdb = (Get-Command adb -ErrorAction Stop).Source }
$taskDevices = & $taskAdb devices
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect connected Android devices.' }
$taskReady = @($taskDevices | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
})
if ($Serial) {
    if ($Serial -notin $taskReady) { throw 'Selected phone is unavailable. Check USB debugging and authorize this computer on the phone.' }
} elseif ($taskReady.Count -eq 1) {
    $Serial = $taskReady[0]
} elseif ($taskReady.Count -eq 0) {
    throw 'No authorized phone connected. Connect via USB, enable USB debugging, then authorize this computer on the phone.'
} else {
    throw 'Multiple phones connected. Choose one with -Serial <device-id>.'
}
& $taskAdb -s $Serial install -r $taskApk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Check the confirmation screen on the phone.' }
if ($ConnectGptService) {
    & $taskAdb -s $Serial reverse tcp:8787 tcp:8787
    if ($LASTEXITCODE -ne 0) { throw 'Local GPT service connection failed.' }
}
& $taskAdb -s $Serial shell am start -n 'app.mediary/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Could not launch Mediary.' }
