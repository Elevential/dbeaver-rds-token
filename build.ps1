<#
.SYNOPSIS
    Builds the DBeaver AWS RDS IAM auth plugin jar on Windows by compiling
    directly against the bundles inside an installed DBeaver, and optionally
    registers it into that DBeaver so it loads on next start.

.DESCRIPTION
    PowerShell equivalent of build.sh for native Windows (no WSL needed).

.PARAMETER Install
    After building, copy the jar into DBeaver's plugins\ folder and register it
    in configuration\...\bundles.info (the reliable install method).

.NOTES
    Env overrides:
      $env:ECLIPSE_HOME  dir containing plugins\ (a DBeaver install root)
      $env:DBEAVER_APP   DBeaver install dir   (default: C:\Program Files\DBeaver)
      $env:JAVA_HOME     a JDK 21+ (needs javac)
      $env:VERSION       plugin version        (default: 1.0.0)

    Examples:
      .\build.ps1
      .\build.ps1 -Install
#>
[CmdletBinding()]
param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot

$Bsn     = 'com.example.dbeaver.ext.rdsiam'
$Version = if ($env:VERSION) { $env:VERSION } else { '1.0.0' }
$JarName = "${Bsn}_${Version}.jar"

# --- Resolve the DBeaver "Eclipse home" (the dir that contains plugins\). ------
$DbeaverApp = if ($env:DBEAVER_APP) { $env:DBEAVER_APP } else { 'C:\Program Files\DBeaver' }
if ($env:ECLIPSE_HOME -and (Test-Path (Join-Path $env:ECLIPSE_HOME 'plugins'))) {
    $EclipseHome = $env:ECLIPSE_HOME
}
elseif (Test-Path (Join-Path $DbeaverApp 'Contents\Eclipse\plugins')) {
    $EclipseHome = Join-Path $DbeaverApp 'Contents\Eclipse'
}
elseif (Test-Path (Join-Path $DbeaverApp 'plugins')) {
    $EclipseHome = $DbeaverApp
}
else {
    throw "Could not find a DBeaver 'plugins' folder. Set `$env:ECLIPSE_HOME (dir containing plugins\) or `$env:DBEAVER_APP."
}
$Plugins = Join-Path $EclipseHome 'plugins'

# --- Locate a JDK 21+ with javac. ---------------------------------------------
function Resolve-Javac {
    if ($env:JAVA_HOME) {
        $jc = Join-Path $env:JAVA_HOME 'bin\javac.exe'
        if (Test-Path $jc) { return $jc }
    }
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "Need a JDK 21+ with javac. Set `$env:JAVA_HOME."
}
$Javac = Resolve-Javac
$JavaBin = Split-Path $Javac -Parent
$Jar = Join-Path $JavaBin 'jar.exe'
Write-Host "Using javac: $Javac"
Write-Host "DBeaver home: $EclipseHome"

# --- Build the compile classpath from the exact bundles we depend on. ---------
function First-Jar([string]$pattern) {
    $item = Get-ChildItem -Path (Join-Path $Plugins $pattern) -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($item) { return $item.FullName } else { return $null }
}
$cpPatterns = @(
    'org.jkiss.dbeaver.model_*.jar',
    'org.jkiss.dbeaver.ui_*.jar',
    'org.jkiss.dbeaver.ui.editors.connection_*.jar',
    'org.eclipse.swt_*.jar',
    'org.eclipse.swt.*win32*.jar',
    'org.eclipse.jface_*.jar',
    'org.eclipse.core.runtime_*.jar',
    'org.eclipse.equinox.common_*.jar',
    'org.eclipse.osgi_*.jar'
)
$cpEntries = @()
foreach ($p in $cpPatterns) {
    $j = First-Jar $p
    if ($j) { $cpEntries += $j }
}
# Windows classpath separator is ';'
$Cp = [string]::Join(';', $cpEntries)

# --- Compile. -----------------------------------------------------------------
$Build = Join-Path $Here 'target'
if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
$Classes = Join-Path $Build 'classes'
New-Item -ItemType Directory -Force -Path $Classes | Out-Null

$Sources = @(Get-ChildItem -Path (Join-Path $Here 'src') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })

Write-Host 'Compiling...'
# Pass source paths directly (only a handful of files) to avoid @argfile
# quoting/BOM/backslash pitfalls on Windows.
& $Javac --release 21 -cp $Cp -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

# --- Package the OSGi bundle jar. ---------------------------------------------
Write-Host "Packaging $JarName ..."
$Stage = Join-Path $Build 'stage'
New-Item -ItemType Directory -Force -Path (Join-Path $Stage 'OSGI-INF\l10n') | Out-Null
Copy-Item -Recurse -Force (Join-Path $Classes '*') $Stage
Copy-Item -Force (Join-Path $Here 'plugin.xml') $Stage
$l10n = Join-Path $Here 'OSGI-INF\l10n\bundle.properties'
if (Test-Path $l10n) { Copy-Item -Force $l10n (Join-Path $Stage 'OSGI-INF\l10n\') }

$OutJar = Join-Path $Here $JarName
$Manifest = Join-Path $Here 'META-INF\MANIFEST.MF'
& $Jar --create --file $OutJar --manifest $Manifest -C $Stage .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
Write-Host "Built: $OutJar"

# --- Optional install (plugins\ + bundles.info). ------------------------------
if ($Install) {
    $Bi = Join-Path $EclipseHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
    Copy-Item -Force $OutJar $Plugins
    if (Test-Path $Bi) {
        Copy-Item -Force $Bi "$Bi.bak-rdsiam"
        $line = "$Bsn,$Version,plugins/$JarName,4,false"
        $kept = Get-Content $Bi | Where-Object { $_ -notmatch "^$([regex]::Escape($Bsn))," }
        # Write UTF-8 WITHOUT BOM (Set-Content -Encoding UTF8 adds a BOM on PS 5.1,
        # which corrupts the simpleconfigurator's bundles.info header).
        [System.IO.File]::WriteAllLines($Bi, [string[]]($kept + $line))
        Write-Host "Registered in: $Bi"
    }
    else {
        Write-Warning "bundles.info not found at $Bi - jar copied to plugins\ only."
    }
    Write-Host "Installed: $(Join-Path $Plugins $JarName)"
    Write-Host 'Now fully quit DBeaver and relaunch once with -clean:'
    Write-Host "  `"$(Join-Path $EclipseHome 'dbeaver.exe')`" -clean"
}
