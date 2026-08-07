[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",

    [string]$SdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $SdkRoot = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
    throw "Set ANDROID_HOME or ANDROID_SDK_ROOT to a valid Android SDK directory."
}

$apk = Resolve-Path -LiteralPath $ApkPath
$buildToolsRoot = Join-Path $SdkRoot "build-tools"
$buildTools =
    Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Where-Object Name -Match '^\d+(\.\d+){1,2}$' |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) {
    throw "No stable Android build-tools installation was found in $buildToolsRoot."
}

$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
foreach ($tool in @($aapt2, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android tool is missing: $tool"
    }
}

$badging = & $aapt2 dump badging $apk
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 could not inspect the APK."
}
$badging | Select-String -Pattern '^package:', '^minSdkVersion', '^targetSdkVersion', '^uses-permission', 'application-debuggable'
$badgingText = $badging -join [Environment]::NewLine
$expectations = [ordered]@{
    package = "package: name='cz\.mates\.skendopdf'"
    versionName = "versionName='1\.0\.0-preview\.1'"
    minSdk = "minSdkVersion:'35'"
    targetSdk = "targetSdkVersion:'36'"
    debuggable = 'application-debuggable'
}
foreach ($expectation in $expectations.GetEnumerator()) {
    if ($badgingText -notmatch $expectation.Value) {
        throw "APK does not match expected $($expectation.Key)."
    }
}

& $zipalign -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) {
    throw "APK alignment verification failed."
}

& $apksigner verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed."
}

Get-FileHash -Algorithm SHA256 -LiteralPath $apk
