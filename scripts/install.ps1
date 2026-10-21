param([string]$Serial, [switch]$ConnectGptService, [ValidateSet('Release', 'Debug')][string]$Variant = 'Release', [string]$Apk)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'python.ps1')
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskApk = if ($Apk) { $Apk } elseif ($Variant -eq 'Release') { Join-Path $taskRoot 'outputs\release\mediary.apk' } else { Join-Path $taskRoot 'android\app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path -LiteralPath $taskApk)) { throw 'Download the release APK or run scripts/build.ps1 for the selected variant first.' }
if ($ConnectGptService -and $Variant -ne 'Debug') { throw 'Local HTTP development service connections require -Variant Debug.' }
if ($Variant -eq 'Release') {
    $taskPython = Get-MediaryPython
    $taskPythonArgs = @($taskPython.Arguments)
    & $taskPython.Executable @taskPythonArgs (Join-Path $PSScriptRoot 'android_release.py') verify --apk $taskApk
    if ($LASTEXITCODE -ne 0) { throw 'Release APK verification failed.' }
}
$taskPackage = if ($Variant -eq 'Release') { 'app.mediary' } else { 'app.mediary.debug' }
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
& $taskAdb -s $Serial shell am start -n ($taskPackage + '/app.mediary.MainActivity')
if ($LASTEXITCODE -ne 0) { throw 'Could not launch Mediary.' }
