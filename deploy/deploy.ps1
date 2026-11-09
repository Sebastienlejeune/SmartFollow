<#
.SYNOPSIS
Builds the SmartFollow server and deploys it to the Lightsail instance, or rolls it back.

.DESCRIPTION
The instance, SSH key and public address come from the parameters, or else from
deploy/deploy.local.psd1 (not in git; copy deploy.local.example.psd1).
1. Runs the server tests and builds server/build/distributions/smartfollow-server.zip.
2. Copies the package to the instance, keeps the running version as
   /opt/smartfollow/smartfollow-server.previous, installs the new one and restarts the service.
3. Checks that the service runs and answers, locally and through the public HTTPS address.
Secrets from /etc/smartfollow/smartfollow.env are only used on the instance, never printed.

.EXAMPLE
.\deploy\deploy.ps1
.\deploy\deploy.ps1 -SkipBuild
.\deploy\deploy.ps1 -Rollback
#>
param(
    [string]$HostName,
    [string]$KeyPath,
    [string]$PublicUrl,
    # Deploy the package already built instead of building it again.
    [switch]$SkipBuild,
    # Restore the version that was running before the last deployment.
    [switch]$Rollback
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$localConfig = Join-Path $PSScriptRoot "deploy.local.psd1"
$local = if (Test-Path $localConfig) { Import-PowerShellDataFile $localConfig } else { @{} }
if (-not $HostName) { $HostName = $local.HostName }
if (-not $KeyPath) { $KeyPath = $local.KeyPath }
if (-not $PublicUrl) { $PublicUrl = $local.PublicUrl }
if (-not $HostName -or -not $KeyPath -or -not $PublicUrl) {
    throw "Set HostName, KeyPath and PublicUrl in $localConfig (see deploy.local.example.psd1) or pass them as parameters"
}
# A data file cannot expand variables: "~" stands for the home folder.
$KeyPath = $KeyPath -replace '^~', $HOME
$package = Join-Path $root "server\build\distributions\smartfollow-server.zip"
$target = "ubuntu@$HostName"
$sshOptions = @("-i", $KeyPath, "-o", "BatchMode=yes")

function Invoke-Remote([string]$script) {
    # PowerShell pipes text to native commands with Windows line endings, which break bash:
    # they are stripped on the instance.
    $script | ssh @sshOptions $target "tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) { throw "Remote step failed (exit code $LASTEXITCODE)" }
}

$check = @'
sleep 20
if ! systemctl is-active --quiet smartfollow; then
  echo "service: FAILED"
  journalctl -u smartfollow -n 30 --no-pager -o cat
  exit 1
fi
echo "service: active"
TOKEN=$(sudo grep '^SMARTFOLLOW_API_TOKEN=' /etc/smartfollow/smartfollow.env | cut -d= -f2-)
echo "status: $(curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/status | grep -oE '"(running|fresh|lastError)":[^,}]*' | tr '\n' ' ')"
'@

if (-not (Test-Path $KeyPath)) { throw "SSH key not found: $KeyPath" }

if ($Rollback) {
    Write-Host "Rolling back to the previous version..."
    Invoke-Remote (@'
set -eu
test -d /opt/smartfollow/smartfollow-server.previous || { echo "no previous version to restore"; exit 1; }
sudo rm -rf /opt/smartfollow/smartfollow-server
sudo mv /opt/smartfollow/smartfollow-server.previous /opt/smartfollow/smartfollow-server
sudo systemctl restart smartfollow
'@ + "`n" + $check)
} else {
    if (-not $SkipBuild) {
        if ($local.JavaHome) { $env:JAVA_HOME = $local.JavaHome }
        if ($local.GradleUserHome) { $env:GRADLE_USER_HOME = $local.GradleUserHome }
        Write-Host "Testing and building the server..."
        Push-Location $root
        try {
            & .\gradlew.bat :server:test :server:distZip --console=plain -q
            if ($LASTEXITCODE -ne 0) { throw "Build or tests failed" }
        } finally {
            Pop-Location
        }
    }
    if (-not (Test-Path $package)) { throw "Package not found: $package" }

    Write-Host "Copying the package..."
    scp @sshOptions $package "${target}:"
    if ($LASTEXITCODE -ne 0) { throw "Copy failed" }

    Write-Host "Installing and restarting..."
    Invoke-Remote (@'
set -eu
sudo rm -rf /opt/smartfollow/smartfollow-server.previous
sudo mv /opt/smartfollow/smartfollow-server /opt/smartfollow/smartfollow-server.previous
sudo unzip -q ~/smartfollow-server.zip -d /opt/smartfollow
sudo systemctl restart smartfollow
'@ + "`n" + $check)
}

Write-Host "Checking the public address..."
$page = Invoke-WebRequest "$PublicUrl/" -UseBasicParsing -TimeoutSec 30
Write-Host "web page: $($page.StatusCode)"
try {
    Invoke-WebRequest "$PublicUrl/api/status" -UseBasicParsing -TimeoutSec 30 | Out-Null
    throw "The API answered without a token"
} catch [System.Net.WebException], [Microsoft.PowerShell.Commands.HttpResponseException] {
    Write-Host "api without token: $($_.Exception.Response.StatusCode.value__)"
}
Write-Host "Done."
