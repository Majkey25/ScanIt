[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet("internal", "github")]
    [string]$Flavor,

    [Parameter(Mandatory, Position = 1)]
    [string]$ArtifactPath,

    [switch]$AllowUnsigned,

    [string]$SdkRoot,

    [string]$BundletoolPath,

    [string]$ExpectedRevision
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$androidNamespace = "http://schemas.android.com/apk/res/android"
$expectedVersionCode = "40"
$expectedMinSdk = "29"
$expectedTargetSdk = "36"
$publicFlavor = $Flavor -eq "github"
$sdkRootWasExplicit = $PSBoundParameters.ContainsKey("SdkRoot")
$blockedPublicCodePattern =
    '(?i)(gemini|generativelanguage\.googleapis\.com|com[./]android[./]billingclient|com[./]google[./]android[./](?:gms|libraries)[./]ads|com[./]google[./]android[./]ump|googlemobileads|admob|user-messaging-platform)'
$usesPermissionElementPattern = '^uses-permission(?:-sdk-(?:\d+|m))?$'
$usesPermissionLinePattern = "^uses-permission(?:-sdk-(?:\d+|m))?: name='([^']+)'(?:\s|$)"
$scannedArchiveEntryPattern = '(?i)\.(dex|xml|pb|arsc|txt|md)$'
$maxArchiveEntries = 10000
$maxScannedArchiveEntryBytes = 32MB
$maxScannedArchiveTotalBytes = 128MB

function Test-IsPermissionLine {
    param(
        [Parameter(Mandatory)][string]$Line,
        [Parameter(Mandatory)][string]$Permission
    )

    $match = [regex]::Match($Line, $usesPermissionLinePattern)
    return $match.Success -and $match.Groups[1].Value -eq $Permission
}

function Test-IsUsesPermissionElement {
    param([Parameter(Mandatory)][string]$Name)

    return $Name -match $usesPermissionElementPattern
}

function Assert-ReleasePolicyConfiguration {
    foreach ($fixture in @(
        "com/google/android/ump/UserMessagingPlatform"
        "com.google.android.gms.ads.MobileAds"
        "com.google.android.libraries.ads.mobile.sdk.MobileAds"
        "com/android/billingclient/api/BillingClient"
        "generativelanguage.googleapis.com"
        "GeminiActivity"
    )) {
        if ($fixture -notmatch $blockedPublicCodePattern) {
            throw "Verifier policy does not block its required fixture: $fixture"
        }
    }
    foreach ($fixture in @(
        "com/google/android/gms/mlkit/vision"
        "android.permission.ACCESS_NETWORK_STATE"
    )) {
        if ($fixture -match $blockedPublicCodePattern) {
            throw "Verifier policy blocks an allowed fixture: $fixture"
        }
    }
    foreach ($elementName in @(
        "uses-permission"
        "uses-permission-sdk-23"
        "uses-permission-sdk-m"
    )) {
        if (-not (Test-IsUsesPermissionElement -Name $elementName)) {
            throw "Verifier policy does not inspect permission element: $elementName"
        }
    }
    if (-not (Test-IsPermissionLine -Line "uses-permission-sdk-23: name='android.permission.INTERNET'" -Permission "android.permission.INTERNET")) {
        throw "Verifier policy does not inspect APK uses-permission-sdk-23 lines."
    }
    if (Test-IsPermissionLine -Line "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'" -Permission "android.permission.INTERNET") {
        throw "Verifier permission policy rejects an unrelated APK permission."
    }
    try {
        Assert-ExactUsesPermissions `
            -Actual @("android.permission.ACCESS_NETWORK_STATE", "android.permission.CAMERA") `
            -Expected @("android.permission.ACCESS_NETWORK_STATE") `
            -Flavor "self-test"
        throw "Verifier permission policy did not reject an unexpected permission."
    } catch {
        if ($_.Exception.Message -notmatch 'Unexpected: android\.permission\.CAMERA') {
            throw
        }
    }
    try {
        Assert-ArchiveEntryCount -Count ($maxArchiveEntries + 1)
        throw "Verifier archive policy did not reject too many entries."
    } catch {
        if ($_.Exception.Message -notmatch 'too many entries') {
            throw
        }
    }
    try {
        Add-ScannedArchiveBytes -EntryName "classes.dex" -EntryBytes ($maxScannedArchiveEntryBytes + 1) -CurrentBytes 0
        throw "Verifier archive policy did not reject an oversized scanned entry."
    } catch {
        if ($_.Exception.Message -notmatch 'scanned entry is too large') {
            throw
        }
    }
    try {
        Add-ScannedArchiveBytes -EntryName "resources.arsc" -EntryBytes 2 -CurrentBytes ($maxScannedArchiveTotalBytes - 1)
        throw "Verifier archive policy did not reject excessive scanned bytes."
    } catch {
        if ($_.Exception.Message -notmatch 'scanned content is too large') {
            throw
        }
    }
}

function Get-ApkUsesPermissions {
    param([Parameter(Mandatory)][string[]]$Lines)

    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $usesPermissionLinePattern)
        if ($match.Success) {
            $match.Groups[1].Value
        }
    }
}

function Get-ExpectedUsesPermissions {
    param(
        [Parameter(Mandatory)][string]$Flavor,
        [Parameter(Mandatory)][string]$Package
    )

    $dynamicReceiverPermission = "$Package.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    if ($Flavor -eq "internal") {
        return @(
            $dynamicReceiverPermission
            "android.permission.ACCESS_NETWORK_STATE"
            "android.permission.INTERNET"
        )
    }
    return @(
        "android.permission.ACCESS_NETWORK_STATE"
        $dynamicReceiverPermission
    )
}

function Assert-ExactUsesPermissions {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Actual,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Expected,
        [Parameter(Mandatory)][string]$Flavor
    )

    $actualUnique = @($Actual | Sort-Object -Unique)
    $expectedUnique = @($Expected | Sort-Object -Unique)
    $missing = @($expectedUnique | Where-Object { $_ -notin $actualUnique })
    $unexpected = @($actualUnique | Where-Object { $_ -notin $expectedUnique })
    $duplicates = @($Actual | Group-Object | Where-Object Count -GT 1 | Select-Object -ExpandProperty Name)
    if ($missing.Count -eq 0 -and $unexpected.Count -eq 0 -and $duplicates.Count -eq 0) {
        return
    }
    $missingText = if ($missing.Count -eq 0) { "none" } else { $missing -join ", " }
    $unexpectedText = if ($unexpected.Count -eq 0) { "none" } else { $unexpected -join ", " }
    $duplicateText = if ($duplicates.Count -eq 0) { "none" } else { $duplicates -join ", " }
    throw "Artifact uses-permission set differs for $Flavor. Missing: $missingText. Unexpected: $unexpectedText. Duplicates: $duplicateText."
}

function Assert-ArchiveEntryCount {
    param([Parameter(Mandatory)][int]$Count)

    if ($Count -gt $maxArchiveEntries) {
        throw "Archive contains too many entries: $Count; maximum is $maxArchiveEntries."
    }
}

function Add-ScannedArchiveBytes {
    param(
        [Parameter(Mandatory)][string]$EntryName,
        [Parameter(Mandatory)][long]$EntryBytes,
        [Parameter(Mandatory)][long]$CurrentBytes
    )

    if ($EntryBytes -lt 0 -or $CurrentBytes -lt 0) {
        throw "Archive contains an invalid scanned entry size: $EntryName."
    }
    if ($EntryBytes -gt $maxScannedArchiveEntryBytes) {
        throw "Archive scanned entry is too large: $EntryName."
    }
    if (
        $CurrentBytes -gt $maxScannedArchiveTotalBytes -or
        $EntryBytes -gt $maxScannedArchiveTotalBytes - $CurrentBytes
    ) {
        throw "Archive scanned content is too large."
    }
    return $CurrentBytes + $EntryBytes
}

Assert-ReleasePolicyConfiguration

switch ($Flavor) {
    "internal" {
        $expectedPackage = "com.majkeylab.scanit.internal"
        $expectedVersionName = "1.8.0-internal"
    }
    "github" {
        $expectedPackage = "com.majkeylab.scanit.github"
        $expectedVersionCode = "40"
        $expectedVersionName = "1.8.0"
    }
}
$expectedPermissions = @(Get-ExpectedUsesPermissions -Flavor $Flavor -Package $expectedPackage)

if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) {
    throw "Release artifact was not found: $ArtifactPath"
}
$artifact = (Resolve-Path -LiteralPath $ArtifactPath).Path
$artifactType = [IO.Path]::GetExtension($artifact).TrimStart(".").ToLowerInvariant()
if ($artifactType -notin @("apk", "aab")) {
    throw "Only APK and AAB artifacts are supported."
}
if ($Flavor -eq "internal" -and $artifactType -ne "apk") {
    throw "The internal flavor must be verified as an APK."
}
$expectedPublicRevision = $null
if ($publicFlavor) {
    if ([string]::IsNullOrWhiteSpace($ExpectedRevision)) {
        $projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
        $revisionOutput = @(& git -C $projectRoot rev-parse --verify HEAD 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Git could not resolve the expected public artifact revision."
        }
        $ExpectedRevision = [string]$revisionOutput[0]
    }
    $expectedPublicRevision = $ExpectedRevision.Trim().ToLowerInvariant()
    if ($expectedPublicRevision -notmatch '^[0-9a-f]{40}$') {
        throw "ExpectedRevision must be a full 40-character Git commit SHA."
    }
}

function Get-AndroidBuildTool {
    param([Parameter(Mandatory)][string]$Name)

    $buildToolsRoot = Join-Path $SdkRoot "build-tools"
    $buildTools =
        Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Where-Object Name -Match '^\d+(\.\d+){1,2}$' |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if ($null -eq $buildTools) {
        throw "No stable Android build-tools installation was found in $buildToolsRoot."
    }

    $fileName = switch ($Name) {
        "apksigner" { if ($isWindowsHost) { "apksigner.bat" } else { "apksigner" } }
        default { if ($isWindowsHost) { "$Name.exe" } else { $Name } }
    }
    $tool = Join-Path $buildTools.FullName $fileName
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android tool is missing: $tool"
    }
    return $tool
}

function Get-AndroidSdkTool {
    param([Parameter(Mandatory)][string]$Name)

    $fileName = if ($isWindowsHost) { "$Name.bat" } else { $Name }
    $tool = Join-Path $SdkRoot "cmdline-tools/latest/bin/$fileName"
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android SDK tool is missing: $tool"
    }
    return $tool
}

function Resolve-AndroidSdkRoot {
    if ($sdkRootWasExplicit) {
        if (-not [string]::IsNullOrWhiteSpace($SdkRoot) -and (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
            return (Resolve-Path -LiteralPath $SdkRoot).Path
        }
        throw "The explicit Android SDK directory is invalid: $SdkRoot"
    }

    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
    $localProperties = Join-Path $projectRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $localProperties) {
            $match = [regex]::Match($line, '^\s*sdk\.dir\s*=\s*(.+?)\s*$')
            if ($match.Success) {
                $localSdkRoot = $match.Groups[1].Value.Replace('\:', ':').Replace('\\', '\')
                $candidates += $localSdkRoot
                break
            }
        }
    }

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Set ANDROID_HOME or ANDROID_SDK_ROOT, or add sdk.dir to local.properties."
}

function Get-Bundletool {
    if (-not [string]::IsNullOrWhiteSpace($BundletoolPath)) {
        if (-not (Test-Path -LiteralPath $BundletoolPath -PathType Leaf)) {
            throw "bundletool was not found: $BundletoolPath"
        }
        return (Resolve-Path -LiteralPath $BundletoolPath).Path
    }

    $version = "1.18.3"
    $expectedHash = "A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29"
    $cacheRoot = Join-Path ([IO.Path]::GetTempPath()) "scanit-release-tools"
    $cachedTool = Join-Path $cacheRoot "bundletool-all-$version.jar"
    $validCache =
        (Test-Path -LiteralPath $cachedTool -PathType Leaf) -and
        ((Get-FileHash -LiteralPath $cachedTool -Algorithm SHA256).Hash -eq $expectedHash)
    if (-not $validCache) {
        New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
        $download = "$cachedTool.download-$PID"
        try {
            $previousProgressPreference = $ProgressPreference
            $ProgressPreference = "SilentlyContinue"
            Invoke-WebRequest -Uri "https://github.com/google/bundletool/releases/download/$version/bundletool-all-$version.jar" -OutFile $download
            $ProgressPreference = $previousProgressPreference
            if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $expectedHash) {
                throw "Downloaded bundletool checksum does not match the pinned release."
            }
            Move-Item -LiteralPath $download -Destination $cachedTool -Force
        } finally {
            $ProgressPreference = $previousProgressPreference
            Remove-Item -LiteralPath $download -Force -ErrorAction SilentlyContinue
        }
    }
    return $cachedTool
}

function Get-ArchiveFacts {
    param([Parameter(Mandatory)][string]$Path)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        Assert-ArchiveEntryCount -Count $archive.Entries.Count
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $hasBlockedText = $false
        $scannedArchiveBytes = [long]0
        foreach ($entry in $archive.Entries) {
            if (-not $names.Add($entry.FullName)) {
                throw "Archive contains a duplicate entry: $($entry.FullName)"
            }
            if ($entry.FullName -match '(^|/)\.\.(/|$)|^/|\\|^[A-Za-z]:') {
                throw "Archive contains an unsafe path: $($entry.FullName)"
            }
            if (
                $publicFlavor -and
                $entry.Name -match $scannedArchiveEntryPattern
            ) {
                $scannedArchiveBytes = Add-ScannedArchiveBytes `
                    -EntryName $entry.FullName `
                    -EntryBytes $entry.Length `
                    -CurrentBytes $scannedArchiveBytes
                $stream = $entry.Open()
                try {
                    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false)
                    try {
                        $entryText = $reader.ReadToEnd()
                        if ($entryText -match $blockedPublicCodePattern) {
                            $hasBlockedText = $true
                        }
                    } finally {
                        $reader.Dispose()
                    }
                } finally {
                    $stream.Dispose()
                }
            }
        }

        $requiredEntries = if ($artifactType -eq "apk") {
            @("AndroidManifest.xml", "resources.arsc")
        } else {
            @("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/resources.pb")
        }
        foreach ($requiredEntry in $requiredEntries) {
            if (-not $names.Contains($requiredEntry)) {
                throw "Archive is missing required entry: $requiredEntry"
            }
        }
        if ($publicFlavor -and $hasBlockedText) {
            throw "Public release artifact contains blocked Gemini, Ads, Billing, or consent SDK residue."
        }
        if ($publicFlavor) {
            $legalPrefix = if ($artifactType -eq "apk") { "assets" } else { "base/assets" }
            foreach ($legalEntry in @(
                "$legalPrefix/legal/THIRD_PARTY_NOTICES.md"
                "$legalPrefix/legal/LICENSES/Apache-2.0.txt"
                "$legalPrefix/legal/LICENSES/MIT.txt"
            )) {
                $packagedEntry = $archive.GetEntry($legalEntry)
                if ($null -eq $packagedEntry -or $packagedEntry.Length -eq 0) {
                    throw "Public artifact is missing packaged legal content: $legalEntry"
                }
            }
            $mitEntry = $archive.GetEntry("$legalPrefix/legal/LICENSES/MIT.txt")
            $mitStream = $mitEntry.Open()
            try {
                $mitReader = [IO.StreamReader]::new($mitStream, [Text.Encoding]::UTF8, $true)
                try {
                    $mitText = $mitReader.ReadToEnd()
                } finally {
                    $mitReader.Dispose()
                }
            } finally {
                $mitStream.Dispose()
            }
            if (
                $mitText -notmatch 'Permission is hereby granted' -or
                $mitText -notmatch 'THE SOFTWARE IS PROVIDED "AS IS"'
            ) {
                throw "Public artifact contains an incomplete MIT license."
            }
            $versionControlPath = if ($artifactType -eq "apk") {
                "META-INF/version-control-info.textproto"
            } else {
                "base/root/META-INF/version-control-info.textproto"
            }
            $versionControlEntry = $archive.GetEntry($versionControlPath)
            if ($null -eq $versionControlEntry -or $versionControlEntry.Length -eq 0) {
                throw "Public artifact is missing non-empty version-control provenance: $versionControlPath"
            }
            $versionControlStream = $versionControlEntry.Open()
            try {
                $versionControlReader = [IO.StreamReader]::new($versionControlStream, [Text.Encoding]::UTF8, $true)
                try {
                    $versionControlText = $versionControlReader.ReadToEnd()
                } finally {
                    $versionControlReader.Dispose()
                }
            } finally {
                $versionControlStream.Dispose()
            }
            if ($versionControlText -match '(?i)generate_error_reason|NO_VALID_GIT_FOUND') {
                throw "Public artifact contains invalid version-control provenance: $versionControlPath"
            }
            $revisionMatches = [regex]::Matches(
                $versionControlText,
                '(?m)^\s*revision:\s*"([0-9a-fA-F]{40})"\s*$'
            )
            if ($revisionMatches.Count -ne 1) {
                throw "Public artifact must contain exactly one full Git revision: $versionControlPath"
            }
            $packagedRevision = $revisionMatches[0].Groups[1].Value.ToLowerInvariant()
            if ($packagedRevision -ne $expectedPublicRevision) {
                throw "Public artifact revision '$packagedRevision' does not match expected revision '$expectedPublicRevision'."
            }
        }
        $dexPattern = if ($artifactType -eq "apk") { '^classes\d*\.dex$' } else { '^base/dex/classes\d*\.dex$' }
        if (-not ($names | Where-Object { $_ -match $dexPattern })) {
            throw "Archive contains no base DEX file."
        }

        $mapping = $archive.GetEntry("BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map")
        if ($artifactType -eq "aab" -and $publicFlavor -and ($null -eq $mapping -or $mapping.Length -eq 0)) {
            throw "Public AAB is missing its non-empty R8 mapping."
        }
        $hasManifestSignature = $names.Contains("META-INF/MANIFEST.MF")
        $hasSignatureFile = [bool]($names | Where-Object { $_ -match '(?i)^META-INF/.+\.SF$' })
        $hasSignatureBlock = [bool]($names | Where-Object { $_ -match '(?i)^META-INF/.+\.(RSA|DSA|EC)$' })
        return [pscustomobject]@{
            HasBlockedText = $hasBlockedText
            HasManifestSignature = $hasManifestSignature
            HasSignatureFile = $hasSignatureFile
            HasSignatureBlock = $hasSignatureBlock
        }
    } finally {
        $archive.Dispose()
    }
}

function Assert-Metadata {
    param(
        [Parameter(Mandatory)][string]$Package,
        [Parameter(Mandatory)][string]$VersionCode,
        [Parameter(Mandatory)][string]$VersionName,
        [Parameter(Mandatory)][string]$MinSdk,
        [Parameter(Mandatory)][string]$TargetSdk
    )

    $actual = [ordered]@{
        package = $Package
        versionCode = $VersionCode
        versionName = $VersionName
        minSdk = $MinSdk
        targetSdk = $TargetSdk
    }
    $expected = [ordered]@{
        package = $expectedPackage
        versionCode = $expectedVersionCode
        versionName = $expectedVersionName
        minSdk = $expectedMinSdk
        targetSdk = $expectedTargetSdk
    }
    foreach ($key in $expected.Keys) {
        if ($actual[$key] -ne $expected[$key]) {
            throw "Artifact $key is '$($actual[$key])'; expected '$($expected[$key])' for $Flavor."
        }
    }
}

$facts = Get-ArchiveFacts -Path $artifact
$signatureStatus = "signed"
if ($artifactType -eq "apk") {
    $SdkRoot = Resolve-AndroidSdkRoot
    $aapt2 = Get-AndroidBuildTool -Name "aapt2"
    $zipalign = Get-AndroidBuildTool -Name "zipalign"
    $apksigner = Get-AndroidBuildTool -Name "apksigner"

    $badging = @(& $aapt2 dump badging $artifact 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not inspect the APK: $($badging -join [Environment]::NewLine)"
    }
    $badgingText = $badging -join [Environment]::NewLine
    $packageLine = [regex]::Match($badgingText, '(?m)^package:.+$').Value
    $minSdkLine = [regex]::Match($badgingText, "(?m)^minSdkVersion:'([^']+)'").Groups[1].Value
    $targetSdkLine = [regex]::Match($badgingText, "(?m)^targetSdkVersion:'([^']+)'").Groups[1].Value
    $packageMatch = [regex]::Match($packageLine, "name='([^']+)'")
    $versionCodeMatch = [regex]::Match($packageLine, "versionCode='([^']+)'")
    $versionNameMatch = [regex]::Match($packageLine, "versionName='([^']+)'")
    Assert-Metadata -Package $packageMatch.Groups[1].Value -VersionCode $versionCodeMatch.Groups[1].Value -VersionName $versionNameMatch.Groups[1].Value -MinSdk $minSdkLine -TargetSdk $targetSdkLine

    if ($publicFlavor -and $badgingText -match '(?m)^application-debuggable$') {
        throw "Public release APK must not be debuggable."
    }
    $actualPermissions = @(Get-ApkUsesPermissions -Lines $badging)
    Assert-ExactUsesPermissions -Actual $actualPermissions -Expected $expectedPermissions -Flavor $Flavor
    $resources = @(& $aapt2 dump resources $artifact 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not inspect APK resources."
    }
    if ($publicFlavor -and (($resources -join [Environment]::NewLine) -match $blockedPublicCodePattern)) {
        throw "Public release APK contains blocked Gemini, Ads, Billing, or consent SDK resources."
    }
    if ($publicFlavor) {
        $apkanalyzer = Get-AndroidSdkTool -Name "apkanalyzer"
        $registrarCode = @(& $apkanalyzer dex code --class "com.google.mlkit.common.internal.CommonComponentRegistrar" $artifact 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "apkanalyzer could not inspect the ML Kit component registrar: $($registrarCode -join [Environment]::NewLine)"
        }
        if (($registrarCode -join [Environment]::NewLine) -notmatch '(?m)^\.method public constructor <init>\(\)V\r?$') {
            throw "Public release APK is missing the ML Kit component registrar public no-arg constructor."
        }
    }

    & $zipalign -c -P 16 4 $artifact
    if ($LASTEXITCODE -ne 0) {
        throw "APK alignment verification failed."
    }
    $signatureOutput = @(& $apksigner verify --verbose --print-certs $artifact 2>&1)
    $signatureExitCode = $LASTEXITCODE
    if ($signatureExitCode -ne 0) {
        $unsignedArtifact = ($signatureOutput -join [Environment]::NewLine) -match 'Missing META-INF/MANIFEST\.MF'
        $ciUnsignedArtifact =
            $publicFlavor -and
            $AllowUnsigned -and
            $env:CI -eq "true" -and
            [IO.Path]::GetFileName($artifact) -match '-unsigned\.apk$'
        if ($unsignedArtifact -and $ciUnsignedArtifact) {
            $signatureStatus = "unsigned (explicitly allowed)"
        } elseif ($unsignedArtifact) {
            throw "Unsigned public APKs require CI=true, -AllowUnsigned, and a *-unsigned.apk filename."
        } else {
            throw "APK signature verification failed: $($signatureOutput -join [Environment]::NewLine)"
        }
    }
} else {
    $java = (Get-Command java -ErrorAction Stop).Source
    $bundletool = Get-Bundletool
    $manifestOutput = @(& $java -jar $bundletool dump manifest "--bundle=$artifact" --module=base 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "bundletool could not inspect the AAB manifest: $($manifestOutput -join [Environment]::NewLine)"
    }
    [xml]$manifestXml = $manifestOutput -join [Environment]::NewLine
    $manifest = $manifestXml.DocumentElement
    $usesSdk = $manifest.SelectSingleNode("uses-sdk")
    $application = $manifest.SelectSingleNode("application")
    Assert-Metadata -Package $manifest.GetAttribute("package") -VersionCode $manifest.GetAttribute("versionCode", $androidNamespace) -VersionName $manifest.GetAttribute("versionName", $androidNamespace) -MinSdk $usesSdk.GetAttribute("minSdkVersion", $androidNamespace) -TargetSdk $usesSdk.GetAttribute("targetSdkVersion", $androidNamespace)

    if ($publicFlavor -and $application.GetAttribute("debuggable", $androidNamespace) -match '^(true|0xffffffff)$') {
        throw "Public release AAB must not be debuggable."
    }
    $actualPermissions = @(
        foreach ($permissionElement in $manifest.ChildNodes) {
            if (
                $permissionElement.NodeType -eq [Xml.XmlNodeType]::Element -and
                (Test-IsUsesPermissionElement -Name $permissionElement.LocalName)
            ) {
                $permissionElement.GetAttribute("name", $androidNamespace)
            }
        }
    )
    Assert-ExactUsesPermissions -Actual $actualPermissions -Expected $expectedPermissions -Flavor $Flavor
    $resources = @(& $java -jar $bundletool dump resources "--bundle=$artifact" 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "bundletool could not inspect AAB resources."
    }
    if ($publicFlavor -and (($resources -join [Environment]::NewLine) -match $blockedPublicCodePattern)) {
        throw "Public release AAB contains blocked Gemini, Ads, Billing, or consent SDK resources."
    }

    $signatureParts = @($facts.HasManifestSignature, $facts.HasSignatureFile, $facts.HasSignatureBlock)
    $signed = -not ($signatureParts -contains $false)
    $partiallySigned = ($signatureParts -contains $true) -and -not $signed
    if ($partiallySigned) {
        throw "AAB contains an incomplete JAR signature."
    }
    if ($signed) {
        $jarsigner = (Get-Command jarsigner -ErrorAction Stop).Source
        $signatureOutput = @(& $jarsigner -verify -strict -certs $artifact 2>&1)
        $signatureExitCode = $LASTEXITCODE
        # Exit 4 means the signer chain is not trusted; content integrity still passed.
        if ($signatureExitCode -notin @(0, 4)) {
            $signatureSummary = ($signatureOutput | Select-Object -First 12) -join [Environment]::NewLine
            throw "AAB signature verification failed with exit code ${signatureExitCode}: $signatureSummary"
        }
    } elseif ($publicFlavor -and $AllowUnsigned -and $env:CI -eq "true") {
        $signatureStatus = "unsigned (explicitly allowed)"
    } else {
        throw "Unsigned public AABs require CI=true and -AllowUnsigned."
    }
}

$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $artifact
[pscustomobject]@{
    Flavor = $Flavor
    Artifact = $artifact
    Signature = $signatureStatus
    SHA256 = $hash.Hash
}
