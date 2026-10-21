param([switch]$SkipLint, [ValidateSet('Debug', 'Release')][string]$Variant = 'Debug', [string]$SigningConfig)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'python.ps1')
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskAndroid = Join-Path $taskRoot 'android'
if (-not $env:JAVA_HOME) {
    $taskJava = (Get-Command java -ErrorAction Stop).Source
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
}
$env:GRADLE_USER_HOME = Join-Path $taskRoot 'work\gradle-home'
Push-Location $taskAndroid
try {
    $taskChecks = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug', '--console=plain')
    if ($Variant -eq 'Release') { $taskChecks += @(':app:testReleaseUnitTest', ':app:assembleRelease') }
    if (-not $SkipLint) { $taskChecks += ':app:lintDebug' }
    if (-not $SkipLint -and $Variant -eq 'Release') { $taskChecks += ':app:lintRelease' }
    & .\gradlew.bat @taskChecks
    if ($LASTEXITCODE -ne 0) { throw 'Android checks failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $taskRoot 'server')
try {
    & node --test
    if ($LASTEXITCODE -ne 0) { throw 'Server tests failed.' }
} finally { Pop-Location }
$taskOutputs = Join-Path $taskRoot 'outputs'
New-Item -ItemType Directory -Path $taskOutputs -Force | Out-Null
if ($Variant -eq 'Release') {
    $taskPreviousConfig = $env:MEDIARY_SIGNING_CONFIG
    try {
        if ($SigningConfig) { $env:MEDIARY_SIGNING_CONFIG = $SigningConfig }
        $taskPython = Get-MediaryPython
        $taskPythonArgs = @($taskPython.Arguments)
        & $taskPython.Executable @taskPythonArgs (Join-Path $PSScriptRoot 'android_release.py') sign
        if ($LASTEXITCODE -ne 0) { throw 'Release signing or verification failed.' }
    } finally { $env:MEDIARY_SIGNING_CONFIG = $taskPreviousConfig }
    Write-Output ('APK: ' + (Join-Path $taskOutputs 'release\mediary.apk'))
} else {
    $taskMetadata = Get-Content -LiteralPath (Join-Path $taskAndroid 'app\build\outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
    $taskName = 'mediary-' + $taskMetadata.elements[0].versionName + '.apk'
    $taskDestination = Join-Path $taskOutputs $taskName
    Copy-Item -LiteralPath (Join-Path $taskAndroid 'app\build\outputs\apk\debug\app-debug.apk') -Destination $taskDestination -Force
    $taskDigest = (Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($taskDestination + '.sha256') -Value ($taskDigest + '  ' + $taskName) -Encoding ascii
    Write-Output ('APK: ' + $taskDestination)
}
if (-not $SkipLint) { Copy-Item -LiteralPath (Join-Path $taskAndroid 'app\build\reports\lint-results-debug.html') -Destination (Join-Path $taskOutputs 'lint-results-debug.html') -Force }
