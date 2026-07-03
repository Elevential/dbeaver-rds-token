<#
.SYNOPSIS
    Generates a p2 update site (Eclipse repository) for the RDS IAM plugin on
    Windows, so end users can install it from DBeaver's UI
    (Help -> Install New Software -> Add URL).

.DESCRIPTION
    PowerShell equivalent of make-update-site.sh. Reuses the p2 publisher and
    Equinox launcher bundled inside DBeaver - no Maven/Tycho needed.

    Output: .\update-site\  (host its contents at a public URL).

.NOTES
    Env overrides:
      $env:ECLIPSE_HOME  dir containing plugins\ (a DBeaver install root)
      $env:DBEAVER_APP   DBeaver install dir   (default: C:\Program Files\DBeaver)
      $env:VERSION       plugin/feature version (default: 1.0.0)
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot

$Bsn       = 'com.example.dbeaver.ext.rdsiam'
$FeatureId = "$Bsn.feature"
$Version   = if ($env:VERSION) { $env:VERSION } else { '1.0.0' }

# --- Resolve DBeaver "Eclipse home" (dir containing plugins\). -----------------
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
    throw "Could not find a DBeaver 'plugins' folder. Set `$env:ECLIPSE_HOME or `$env:DBEAVER_APP."
}
$Plugins = Join-Path $EclipseHome 'plugins'

# --- Launcher + java. ---------------------------------------------------------
$Launcher = Get-ChildItem -Path (Join-Path $Plugins 'org.eclipse.equinox.launcher_*.jar') -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $Launcher) { throw "Equinox launcher not found under $Plugins" }

function Resolve-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    $bundled = Join-Path $EclipseHome 'jre\bin\java.exe'
    if (Test-Path $bundled) { return $bundled }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "No java.exe found. Set `$env:JAVA_HOME."
}
$JavaBin = Resolve-Java
$JarTool = Join-Path (Split-Path $JavaBin -Parent) 'jar.exe'

# --- 1) Build the plugin jar (keep version in sync). --------------------------
$env:VERSION = $Version
& (Join-Path $Here 'build.ps1')
if ($LASTEXITCODE -ne 0) { throw "build.ps1 failed ($LASTEXITCODE)" }

# --- 2) Build the feature jar (feature.xml at jar root). ----------------------
$Build = Join-Path $Here 'target'
New-Item -ItemType Directory -Force -Path $Build | Out-Null
$FeatJar = Join-Path $Build "${FeatureId}_${Version}.jar"
& $JarTool --create --file $FeatJar -C (Join-Path $Here 'feature') feature.xml
if ($LASTEXITCODE -ne 0) { throw "feature jar failed ($LASTEXITCODE)" }
Write-Host "Built feature: $FeatJar"

# --- 3) Stage source tree with plugins\ and features\. ------------------------
$Src = Join-Path $Build 'site-source'
if (Test-Path $Src) { Remove-Item -Recurse -Force $Src }
New-Item -ItemType Directory -Force -Path (Join-Path $Src 'plugins') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Src 'features') | Out-Null
Copy-Item -Force (Join-Path $Here "${Bsn}_${Version}.jar") (Join-Path $Src 'plugins\')
Copy-Item -Force $FeatJar (Join-Path $Src 'features\')

# --- 4) Publish features + bundles, then the category, into update-site\. ------
$Repo = Join-Path $Here 'update-site'
if (Test-Path $Repo) { Remove-Item -Recurse -Force $Repo }
New-Item -ItemType Directory -Force -Path $Repo | Out-Null
$Config = Join-Path $Build 'p2-config'
if (Test-Path $Config) { Remove-Item -Recurse -Force $Config }
New-Item -ItemType Directory -Force -Path $Config | Out-Null

# p2 wants file: URLs with forward slashes.
$RepoUrl = 'file:///' + ($Repo -replace '\\', '/')
$CatUrl  = 'file:///' + ((Join-Path $Here 'feature\category.xml') -replace '\\', '/')

Write-Host 'Publishing bundles + features...'
& $JavaBin -jar $Launcher -nosplash -consoleLog -clean -configuration $Config `
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -metadataRepository $RepoUrl -artifactRepository $RepoUrl `
    -metadataRepositoryName 'DBeaver RDS IAM Update Site' `
    -artifactRepositoryName 'DBeaver RDS IAM Update Site' `
    -source $Src -compress -publishArtifacts
if ($LASTEXITCODE -ne 0) { throw "FeaturesAndBundlesPublisher failed ($LASTEXITCODE)" }

Write-Host 'Publishing category...'
& $JavaBin -jar $Launcher -nosplash -consoleLog -clean -configuration $Config `
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher `
    -metadataRepository $RepoUrl `
    -categoryDefinition $CatUrl -compress
if ($LASTEXITCODE -ne 0) { throw "CategoryPublisher failed ($LASTEXITCODE)" }

Write-Host ''
Write-Host "Update site generated at: $Repo"
Get-ChildItem $Repo | Format-Table -AutoSize
Write-Host 'Host the CONTENTS of that folder at a public URL, then users install via:'
Write-Host '  Help -> Install New Software -> Add -> <that URL>'
