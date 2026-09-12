# C-TDS image tag computation (WBS 2.2.6, spec: ADR-012 section 3.2).
# Single source of truth for the canonical image tag naming convention.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pipeline\image-tag.ps1 -ModuleName example-service
#   powershell ... image-tag.ps1 -All                          # full list to stdout
#   powershell ... image-tag.ps1 -ModuleName example-service -Version 2.0.0-SNAPSHOT -OutFile <path>
# Output: one tag per line, e.g. ctds/example-service:2.0.0-SNAPSHOT-20260912-1430-1bfb279
param(
    [string]$ModuleName = "",
    [switch]$All,
    [string]$Version = "",
    [string]$OutFile = ""
)
$ErrorActionPreference = "Stop"

if ($PSScriptRoot) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
} else {
    $RepoRoot = (Get-Location).Path
}

# Module inventory per ADR-012 3.2. connector-sdk is a placeholder (separate
# repository strategy, ADR-002) and is listed for completeness only.
$MavenModules = @("errorcode", "pagination", "logging", "auth", "crypto", "idempotency", "std-adapter", "example-service")
$AllModules = ($MavenModules + @("frontend", "connector-sdk"))

# Root pom version
$pomPath = Join-Path $RepoRoot "pom.xml"
if (-not (Test-Path $pomPath)) {
    Write-Error "[CONFIG-ERROR] root pom.xml not found: $pomPath"
    exit 2
}
$pomXml = [xml](Get-Content -Path $pomPath -Raw -Encoding UTF8)
$MavenVersion = ($pomXml.project.version | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($MavenVersion)) {
    Write-Error "[CONFIG-ERROR] cannot read <version> from root pom.xml"
    exit 2
}

# Frontend version from package.json
$pkgPath = Join-Path $RepoRoot "frontend\package.json"
$FrontendVersion = ""
if (Test-Path $pkgPath) {
    $pkg = Get-Content -Path $pkgPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $FrontendVersion = $pkg.version
}

# Build timestamp (yyyymmdd-hhmm) and git short hash + dirty suffix
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$gitArgs = @("-C", $RepoRoot, "rev-parse", "--short", "HEAD")
$gitOut = & git @gitArgs 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace("$gitOut")) {
    Write-Error "[ENV-ERROR] git rev-parse failed; image tag requires a git commit (spec ADR-012 3.2)"
    exit 2
}
$shortHash = ("$gitOut").Trim()
$dirtySuffix = ""
& git -C $RepoRoot status --porcelain *> $null
if ($LASTEXITCODE -eq 0) {
    $statusLines = @(& git -C $RepoRoot status --porcelain)
    if ($statusLines.Count -gt 0) { $dirtySuffix = "-dirty" }
}

function Get-Tag([string]$Mod) {
    $ver = $MavenVersion
    if ($Mod -eq "frontend") { $ver = $FrontendVersion }
    if ($Mod -eq "connector-sdk") { $ver = "SDK-REPO-SEPARATE" }
    return "ctds/${Mod}:${ver}-${stamp}-${shortHash}${dirtySuffix}"
}

$lines = @()
if ($All) {
    foreach ($m in $AllModules) { $lines += ,(Get-Tag $m) }
} else {
    if ([string]::IsNullOrWhiteSpace($ModuleName)) {
        Write-Error "Usage: -ModuleName <name> or -All"
        exit 2
    }
    if ($AllModules -notcontains $ModuleName) {
        Write-Error ("[CONFIG-ERROR] unknown module: " + $ModuleName + " (known: " + ($AllModules -join ", ") + ")")
        exit 2
    }
    $lines += ,(Get-Tag $ModuleName)
}

if (-not [string]::IsNullOrWhiteSpace($OutFile)) {
    $lines | Set-Content -Path $OutFile -Encoding UTF8
}
$lines | ForEach-Object { Write-Output $_ }
exit 0
