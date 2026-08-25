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

$worktreeStatus = @(& git -C $projectRoot status --porcelain=v1 --untracked-files=all 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Git could not inspect the source worktree state."
}
if ($worktreeStatus.Count -ne 0) {
    throw "Release builds require a clean source worktree. Commit, remove, or revert source changes first."
}
$revisionOutput = @(& git -C $projectRoot rev-parse --verify HEAD 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Git could not resolve the release revision."
}
$expectedRevision = ([string]$revisionOutput[0]).Trim().ToLowerInvariant()
if ($expectedRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Git returned an invalid release revision."
}

$tasks = @(
    ":app:testInternalDebugUnitTest"
    ":app:lintInternalDebug"
    ":app:assembleInternalDebug"
    ":app:testBetaDebugUnitTest"
    ":app:lintBetaRelease"
    ":app:assembleBetaRelease"
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
    $betaApk = if (Test-Path -LiteralPath "keystore.properties" -PathType Leaf) {
        "app/build/outputs/apk/beta/release/app-beta-release.apk"
    } else {
        "app/build/outputs/apk/beta/release/app-beta-release-unsigned.apk"
    }
    & $verify internal "app/build/outputs/apk/internal/debug/app-internal-debug.apk" -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath
    & $verify beta $betaApk -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath -ExpectedRevision $expectedRevision
    & $verify play "app/build/outputs/bundle/playRelease/app-play-release.aab" -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath -ExpectedRevision $expectedRevision
    & $verify github $githubApk -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath -ExpectedRevision $expectedRevision
    & $verify github "app/build/outputs/bundle/githubRelease/app-github-release.aab" -AllowUnsigned:$AllowUnsigned -BundletoolPath $BundletoolPath -ExpectedRevision $expectedRevision
} finally {
    Pop-Location
}
