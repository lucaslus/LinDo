#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Packages the Flutter Windows x64 release build of Lindo into a WiX v3 MSI installer.

.DESCRIPTION
  Harvests build\windows\x64\runner\Release (Lindo.exe, flutter_windows.dll, plugin DLLs, data\**),
  attaches Start Menu & Desktop shortcuts using windows\runner\resources\app_icon.ico, registers
  lindo:// and discourse:// custom URL protocols, and preserves the historical Lindo UpgradeCode
  (9a4e7c31-62b8-4d95-b7f2-8c1e3409a520) for seamless in-place upgrades.
#>

param(
  [Parameter(Mandatory = $false)]
  [string]$Version = "",

  [Parameter(Mandatory = $false)]
  [string]$ReleaseDir = "build/windows/x64/runner/Release",

  [Parameter(Mandatory = $false)]
  [string]$OutFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

# 1. Resolve version string & numeric X.Y.Z MSI Product/@Version
$CleanVersion = ($Version -replace '^v', '').Trim()
if ([string]::IsNullOrWhiteSpace($CleanVersion)) {
  $PubspecPath = Join-Path $RepoRoot "pubspec.yaml"
  if (Test-Path $PubspecPath) {
    $VerMatch = Select-String -Path $PubspecPath -Pattern '^version:\s*([^\s+]+)' | Select-Object -First 1
    if ($VerMatch) {
      $CleanVersion = $VerMatch.Matches[0].Groups[1].Value.Trim()
    }
  }
}
if ([string]::IsNullOrWhiteSpace($CleanVersion)) {
  $CleanVersion = "0.3.0"
}

if ($CleanVersion -match '^(\d+\.\d+\.\d+)') {
  $MsiVersion = $Matches[1]
} elseif ($CleanVersion -match '^(\d+\.\d+)') {
  $MsiVersion = "$($Matches[1]).0"
} else {
  $MsiVersion = "0.3.0"
}

# 2. Resolve input ReleaseDir, app icon, and output MSI path
$CandidateReleaseDir = if ([System.IO.Path]::IsPathRooted($ReleaseDir)) {
  $ReleaseDir
} else {
  Join-Path $RepoRoot $ReleaseDir
}
if (-not (Test-Path $CandidateReleaseDir)) {
  throw "Windows release directory not found: $CandidateReleaseDir"
}
$ResolvedReleaseDir = (Resolve-Path $CandidateReleaseDir).Path.TrimEnd('\', '/')

$ExePath = Join-Path $ResolvedReleaseDir "Lindo.exe"
$FlutterDllPath = Join-Path $ResolvedReleaseDir "flutter_windows.dll"
$DataDirPath = Join-Path $ResolvedReleaseDir "data"

if (-not (Test-Path $ExePath)) {
  throw "Expected executable not found: $ExePath"
}
if (-not (Test-Path $FlutterDllPath)) {
  throw "Expected Flutter engine DLL not found: $FlutterDllPath"
}
if (-not (Test-Path $DataDirPath)) {
  throw "Expected Flutter data directory not found: $DataDirPath"
}

$IconCandidates = @(
  (Join-Path $RepoRoot "windows/runner/resources/app_icon.ico"),
  (Join-Path $RepoRoot "assets/desktop/icon.ico")
)
$ResolvedIconPath = $IconCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $ResolvedIconPath) {
  throw "Could not find Lindo .ico file under windows/runner/resources/app_icon.ico or assets/desktop/icon.ico"
}
$ResolvedIconPath = (Resolve-Path $ResolvedIconPath).Path

if ([string]::IsNullOrWhiteSpace($OutFile)) {
  $OutFile = "dist/lindo-$CleanVersion-windows-x64.msi"
}
$ResolvedOutFile = if ([System.IO.Path]::IsPathRooted($OutFile)) {
  $OutFile
} else {
  Join-Path $RepoRoot $OutFile
}
$OutDir = Split-Path -Parent $ResolvedOutFile
if (-not (Test-Path $OutDir)) {
  New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

# 3. Locate WiX Toolset v3 binaries (heat.exe, candle.exe, light.exe)
$WixSearchDirs = @()
if (-not [string]::IsNullOrWhiteSpace($env:WIX)) {
  $WixSearchDirs += (Join-Path $env:WIX "bin")
}
$WixSearchDirs += @(
  "C:\Program Files (x86)\WiX Toolset v3.14\bin",
  "C:\Program Files (x86)\WiX Toolset v3.11\bin",
  "C:\Program Files\WiX Toolset v3.14\bin",
  "C:\Program Files\WiX Toolset v3.11\bin"
)

$WixBinDir = $WixSearchDirs | Where-Object {
  (Test-Path (Join-Path $_ "heat.exe")) -and
  (Test-Path (Join-Path $_ "candle.exe")) -and
  (Test-Path (Join-Path $_ "light.exe"))
} | Select-Object -First 1

if (-not $WixBinDir) {
  $HeatCmd = Get-Command "heat.exe" -ErrorAction SilentlyContinue
  if ($HeatCmd) {
    $WixBinDir = Split-Path -Parent $HeatCmd.Source
  }
}

if (-not $WixBinDir) {
  throw "WiX Toolset v3 (heat.exe, candle.exe, light.exe) was not found. Install WiX Toolset v3.14 or set `$env:WIX."
}

$HeatExe = Join-Path $WixBinDir "heat.exe"
$CandleExe = Join-Path $WixBinDir "candle.exe"
$LightExe = Join-Path $WixBinDir "light.exe"

Write-Host "==> Packaging Lindo Windows MSI"
Write-Host "    Release version : $CleanVersion (MSI ProductVersion: $MsiVersion)"
Write-Host "    Source dir      : $ResolvedReleaseDir"
Write-Host "    Icon            : $ResolvedIconPath"
Write-Host "    WiX bin         : $WixBinDir"
Write-Host "    Output MSI      : $ResolvedOutFile"

$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lindo-msi-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

try {
  $HarvestWxs = Join-Path $TempDir "HarvestedRelease.wxs"
  $ProductWxs = Join-Path $TempDir "Product.wxs"
  $HarvestWixObj = Join-Path $TempDir "HarvestedRelease.wixobj"
  $ProductWixObj = Join-Path $TempDir "Product.wixobj"

  # 4. Harvest all files in build\windows\x64\runner\Release
  & $HeatExe dir $ResolvedReleaseDir `
    -nologo `
    -cg HarvestedAppFiles `
    -dr INSTALLFOLDER `
    -gg `
    -sfrag `
    -srd `
    -sreg `
    -scom `
    -var var.SourceDir `
    -out $HarvestWxs
  if ($LASTEXITCODE -ne 0) {
    throw "heat.exe failed with exit code $LASTEXITCODE"
  }

  # 5. Generate main WiX Product manifest with exact UpgradeCode 9a4e7c31-62b8-4d95-b7f2-8c1e3409a520
  $ProductXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*"
           Name="Lindo"
           Language="1033"
           Codepage="65001"
           Version="$MsiVersion"
           Manufacturer="Lindo"
           UpgradeCode="9a4e7c31-62b8-4d95-b7f2-8c1e3409a520">
    <Package InstallerVersion="500"
             Compressed="yes"
             InstallScope="perUser"
             Platform="x64"
             Description="Lindo Desktop Client"
             Comments="Lindo $CleanVersion" />

    <MajorUpgrade DowngradeErrorMessage="A newer version of [ProductName] is already installed."
                  AllowSameVersionUpgrades="yes" />
    <MediaTemplate EmbedCab="yes" CompressionLevel="high" />

    <Icon Id="LindoAppIcon.ico" SourceFile="`$(var.IconPath)" />
    <Property Id="ARPPRODUCTICON" Value="LindoAppIcon.ico" />
    <Property Id="ARPURLINFOABOUT" Value="https://linux.do" />
    <Property Id="ARPNOMODIFY" Value="1" />

    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="LocalAppDataFolder">
        <Directory Id="INSTALLFOLDER" Name="Lindo" />
      </Directory>
      <Directory Id="ProgramMenuFolder">
        <Directory Id="ApplicationProgramsFolder" Name="Lindo" />
      </Directory>
      <Directory Id="DesktopFolder" Name="Desktop" />
    </Directory>

    <DirectoryRef Id="ApplicationProgramsFolder">
      <Component Id="StartMenuShortcutComponent" Guid="5f4c82d1-39e4-4c88-9327-194a86e204b1" Win64="yes">
        <Shortcut Id="ApplicationStartMenuShortcut"
                  Name="Lindo"
                  Description="Lindo"
                  Target="[INSTALLFOLDER]Lindo.exe"
                  WorkingDirectory="INSTALLFOLDER"
                  Icon="LindoAppIcon.ico" />
        <RemoveFolder Id="RemoveApplicationProgramsFolder" Directory="ApplicationProgramsFolder" On="uninstall" />
        <RegistryValue Root="HKCU"
                       Key="Software\Lindo"
                       Name="StartMenuShortcut"
                       Type="integer"
                       Value="1"
                       KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <DirectoryRef Id="DesktopFolder">
      <Component Id="DesktopShortcutComponent" Guid="7b9a24e3-8120-41f0-b41d-609e75a128c2" Win64="yes">
        <Shortcut Id="ApplicationDesktopShortcut"
                  Name="Lindo"
                  Description="Lindo"
                  Target="[INSTALLFOLDER]Lindo.exe"
                  WorkingDirectory="INSTALLFOLDER"
                  Icon="LindoAppIcon.ico" />
        <RegistryValue Root="HKCU"
                       Key="Software\Lindo"
                       Name="DesktopShortcut"
                       Type="integer"
                       Value="1"
                       KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <DirectoryRef Id="INSTALLFOLDER">
      <Component Id="UrlProtocolRegistrationComponent" Guid="2d83a7c4-64b9-4e1a-95d8-4138c7e09b54" Win64="yes">
        <RegistryValue Root="HKCU"
                       Key="Software\Lindo"
                       Name="InstallLocation"
                       Type="string"
                       Value="[INSTALLFOLDER]"
                       KeyPath="yes" />
        <RegistryKey Root="HKCU" Key="Software\Classes\lindo">
          <RegistryValue Type="string" Value="URL:Lindo Protocol" />
          <RegistryValue Name="URL Protocol" Type="string" Value="" />
          <RegistryKey Key="DefaultIcon">
            <RegistryValue Type="string" Value="&quot;[INSTALLFOLDER]Lindo.exe&quot;,0" />
          </RegistryKey>
          <RegistryKey Key="shell\open\command">
            <RegistryValue Type="string" Value="&quot;[INSTALLFOLDER]Lindo.exe&quot; &quot;%1&quot;" />
          </RegistryKey>
        </RegistryKey>
        <RegistryKey Root="HKCU" Key="Software\Classes\discourse">
          <RegistryValue Type="string" Value="URL:Discourse Auth Protocol" />
          <RegistryValue Name="URL Protocol" Type="string" Value="" />
          <RegistryKey Key="DefaultIcon">
            <RegistryValue Type="string" Value="&quot;[INSTALLFOLDER]Lindo.exe&quot;,0" />
          </RegistryKey>
          <RegistryKey Key="shell\open\command">
            <RegistryValue Type="string" Value="&quot;[INSTALLFOLDER]Lindo.exe&quot; &quot;%1&quot;" />
          </RegistryKey>
        </RegistryKey>
      </Component>
    </DirectoryRef>

    <Feature Id="MainApplication" Title="Lindo" Level="1">
      <ComponentGroupRef Id="HarvestedAppFiles" />
      <ComponentRef Id="StartMenuShortcutComponent" />
      <ComponentRef Id="DesktopShortcutComponent" />
      <ComponentRef Id="UrlProtocolRegistrationComponent" />
    </Feature>
  </Product>
</Wix>
"@

  [System.IO.File]::WriteAllText($ProductWxs, $ProductXml, (New-Object System.Text.UTF8Encoding($false)))

  # 6. Compile .wxs -> .wixobj
  & $CandleExe -nologo -arch x64 `
    "-dSourceDir=$ResolvedReleaseDir" `
    "-dIconPath=$ResolvedIconPath" `
    -out $ProductWixObj `
    $ProductWxs
  if ($LASTEXITCODE -ne 0) {
    throw "candle.exe failed on Product.wxs with exit code $LASTEXITCODE"
  }

  & $CandleExe -nologo -arch x64 `
    "-dSourceDir=$ResolvedReleaseDir" `
    "-dIconPath=$ResolvedIconPath" `
    -out $HarvestWixObj `
    $HarvestWxs
  if ($LASTEXITCODE -ne 0) {
    throw "candle.exe failed on HarvestedRelease.wxs with exit code $LASTEXITCODE"
  }

  # 7. Link .wixobj -> .msi
  & $LightExe -nologo `
    -spdb `
    -sice:ICE38 `
    -sice:ICE43 `
    -sice:ICE57 `
    -sice:ICE64 `
    -sice:ICE91 `
    -out $ResolvedOutFile `
    $ProductWixObj `
    $HarvestWixObj
  if ($LASTEXITCODE -ne 0) {
    throw "light.exe failed with exit code $LASTEXITCODE"
  }

  $MsiItem = Get-Item $ResolvedOutFile
  Write-Host "==> Successfully created $($MsiItem.FullName) ($([math]::Round($MsiItem.Length / 1MB, 2)) MB)"
} finally {
  if (Test-Path $TempDir) {
    Remove-Item -Recurse -Force $TempDir -ErrorAction SilentlyContinue
  }
}
