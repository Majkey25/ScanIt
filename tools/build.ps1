[CmdletBinding()]
param(
    [switch]$SkipClean
)

$ErrorActionPreference = "Stop"
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$javaOutput = @(& java --version)
$javaExitCode = $LASTEXITCODE
$javaVersion = $javaOutput[0]

if ($javaExitCode -ne 0 -or $javaVersion -notmatch '^(?:openjdk|java) 17(?:\.|\s)') {
    throw "JDK 17 must be active on PATH. Found: $javaVersion"
}

$tasks = @(
    ":app:testDebugUnitTest"
    ":app:lintDebug"
    ":app:assembleDebug"
    ":app:bundleRelease"
)
if (-not $SkipClean) {
    $tasks = @("clean") + $tasks
}

Push-Location $projectRoot
try {
    & ".\gradlew.bat" @tasks "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle quality gate failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
