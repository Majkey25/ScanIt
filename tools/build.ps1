[CmdletBinding()]
param(
    [switch]$SkipClean,

    [switch]$AllowUnsigned,

    [string]$BundletoolPath
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$javaOutput = @(& java --version 2>&1)
$javaExitCode = $LASTEXITCODE
$javaVersion = [string]$javaOutput[0]

if ($javaExitCode -ne 0 -or $javaVersion -notmatch '^(?:openjdk|java) 17(?:\.|\s)') {
    throw "JDK 17 must be active on PATH. Found: $javaVersion"
}

$tasks = @(
    ":app:testInternalDebugUnitTest"
    ":app:lintInternalDebug"
    ":app:assembleInternalDebug"
    ":app:lintPlayRelease"
    ":app:bundlePlayRelease"
    ":app:lintGithubRelease"
    ":app:assembleGithubRelease"
    ":app:bundleGithubRelease"
)
if (-not $SkipClean) {
    $tasks = @("clean") + $tasks
}

Push-Location $projectRoot
try {
    $gradle = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        ".\gradlew.bat"
    } else {
        "./gradlew"
    }
    & $gradle @tasks "--no-daemon" "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle quality gate failed with exit code $LASTEXITCODE."
    }

    $verify = Join-Path $PSScriptRoot "verify-release.ps1"
    $githubApk = if (Test-Path -LiteralPath "keystore.properties" -PathType Leaf) {
        "app/build/outputs/apk/github/release/app-github-release.apk"
    } else {
        "app/build/outputs/apk/github/release/app-github-release-unsigned.apk"
    }
    & $verify internal "app/build/outputs/apk/internal/debug/app-internal-debug.apk" -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath
    & $verify play "app/build/outputs/bundle/playRelease/app-play-release.aab" -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath
    & $verify github $githubApk -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath
    & $verify github "app/build/outputs/bundle/githubRelease/app-github-release.aab" -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath
} finally {
    Pop-Location
}
