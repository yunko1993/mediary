param([switch]$SkipLint)
$ErrorActionPreference = 'Stop'
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
    if (-not $SkipLint) { $taskChecks += ':app:lintDebug' }
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
Copy-Item -LiteralPath (Join-Path $taskAndroid 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $taskOutputs 'mediary-0.2.2-debug.apk') -Force
$taskDigest = (Get-FileHash -LiteralPath (Join-Path $taskOutputs 'mediary-0.2.2-debug.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $taskOutputs 'mediary-0.2.2-debug.apk.sha256') -Value ($taskDigest + '  mediary-0.2.2-debug.apk') -Encoding ascii
if (-not $SkipLint) { Copy-Item -LiteralPath (Join-Path $taskAndroid 'app\build\reports\lint-results-debug.html') -Destination (Join-Path $taskOutputs 'lint-results-debug.html') -Force }
Write-Output ('APK: ' + (Join-Path $taskOutputs 'mediary-0.2.2-debug.apk'))
