# C-TDS nightly build pipeline, local edition (WBS 2.2.6, spec: ADR-012).
# Stages: N1 env self-check -> N2 quality gates (reuses scripts/gates, must be
# GREEN) -> N3 backend package -> N4 frontend build -> N5 SBOM -> N6 image tags
# -> N7 summary report. Any stage failure stops the run, writes a partial
# report and keeps the scene (logs + artifacts produced so far).
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/pipeline/nightly-build.ps1
# Exit codes: 0 = all green, 1 = stage failure, 2 = environment self-check failure.
param(
    [string]$RepoRoot = ""
)
$ErrorActionPreference = "Stop"

if ($RepoRoot -eq "") {
    if ($PSScriptRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
    else { $RepoRoot = (Get-Location).Path }
}
if (-not (Test-Path (Join-Path $RepoRoot "pom.xml"))) {
    Write-Output "[CONFIG-ERROR] RepoRoot not found or pom.xml missing: $RepoRoot"
    exit 2
}

$CyclonedxMaven = "2.9.3"
$CyclonedxNpm = "6.0.1"

$results = New-Object System.Collections.Generic.List[object]
$script:OutDir = ""
$script:StampDir = ""
$script:FailedStage = ""
$script:FailureExcerpt = ""

function Add-Stage([string]$stage, [string]$verdict, [string]$detail) {
    $script:results.Add([pscustomobject]@{ Stage = $stage; Verdict = $verdict; Detail = $detail })
}

# ---------- N1 environment self-check ----------
# Probe via cmd /c with stderr merged inside cmd: under $ErrorActionPreference
# =Stop a raw `native 2>&1` turns the first stderr line into a terminating
# error (PS 5.1), and Select-Object -First would kill the pipeline early and
# corrupt $LASTEXITCODE - so drain fully and validate with expectation regexes.
function Test-Tool([string]$name, [string]$cmdLine, [string]$requirement, [string]$expect, [int]$minMajor) {
    $out = @()
    try { $out = @(& cmd /c $cmdLine) } catch { }
    if ($LASTEXITCODE -ne 0 -or $out.Count -eq 0) {
        return $false, ($name + " NOT FOUND (requirement: " + $requirement + ")")
    }
    $first = ("$($out[0])").Trim()
    if ($expect -ne "" -and $first -notmatch $expect) {
        return $false, ($name + " unexpected version: [" + $first + "] (requirement: " + $requirement + ")")
    }
    if ($minMajor -gt 0) {
        if ($first -match "(\d+)") {
            if ([int]$Matches[1] -lt $minMajor) {
                return $false, ($name + " too old: [" + $first + "] (requirement: " + $requirement + ")")
            }
        }
    }
    return $true, ($name + ": " + $first)
}

$missing = @()
$envInfo = @()
$probeDefs = @(
    @{ name = "java"; cmd = "java -version 2>&1";   req = "JDK 17";      expect = '"17\.'; minMajor = 0 },
    @{ name = "mvn";  cmd = "mvn -v 2>&1";         req = "Maven 3.9";   expect = "3\.9";  minMajor = 0 },
    @{ name = "node"; cmd = "node --version 2>&1"; req = ">= 20";       expect = "";      minMajor = 20 },
    @{ name = "npm";  cmd = "npm --version 2>&1";  req = "npm 9+";      expect = "";      minMajor = 9 },
    @{ name = "git";  cmd = "git --version 2>&1";  req = "any";         expect = "git version"; minMajor = 0 }
)
foreach ($d in $probeDefs) {
    $ok, $msg = Test-Tool $d.name $d.cmd $d.req $d.expect $d.minMajor
    if (-not $ok) { $missing += $msg }
    $envInfo += $msg
}
if ($missing.Count -gt 0) {
    foreach ($m in $missing) { Write-Output ("[NIGHTLY][ENV-ERROR] " + $m) }
    Write-Output "[NIGHTLY] environment self-check failed; fix all tools above and rerun (exit 2)"
    exit 2
}

$dockerMsg = "docker: not available (not blocking; image build belongs to WBS 2.5.1)"
try {
    $dOut = @(& cmd /c "docker --version 2>&1")
    if ($LASTEXITCODE -eq 0 -and $dOut.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace("$($dOut[0])")) { $dockerMsg = "docker: " + ("$($dOut[0])").Trim() }
} catch { }
$envInfo += $dockerMsg

# Output directory: build-output/<yyyymmdd-HHmmss>, append -N on same-second collision
$script:StampDir = Get-Date -Format "yyyyMMdd-HHmmss"
$script:OutDir = Join-Path $RepoRoot ("build-output\" + $script:StampDir)
$seq = 0
while (Test-Path $script:OutDir) {
    $seq++
    $script:OutDir = Join-Path $RepoRoot ("build-output\" + $script:StampDir + "-" + $seq)
}
New-Item -ItemType Directory -Path $script:OutDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $script:OutDir "logs") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $script:OutDir "reports") -Force | Out-Null
Write-Host ("[NIGHTLY] output directory: " + $script:OutDir)

# ---------- summary report (written on success AND on failure) ----------
function Write-Report([bool]$success) {
    $reportPath = Join-Path $script:OutDir "nightly-report.md"
    $lines = @()
    if ($success) {
        $lines += "# C-TDS Nightly Build Report (Local Edition) " + $script:StampDir
        $lines += ""
        $lines += "- Conclusion: **PASS** (all stages green)"
    } else {
        $lines += "# C-TDS Nightly Build Report (Local Edition) " + $script:StampDir + " (PARTIAL)"
        $lines += ""
        $lines += "- Conclusion: **FAIL** - stopped at stage: " + $script:FailedStage + "; artifacts produced so far are kept below (failure scene)"
    }
    $lines += "- Output directory: " + $script:OutDir
    $lines += ""
    $lines += "## Environment"
    $envInfo | ForEach-Object { $lines += ("- " + $_) }
    $lines += ("- cyclonedx-maven-plugin: " + $CyclonedxMaven + " / cyclonedx-npm: " + $CyclonedxNpm)
    $lines += ""
    $lines += "## Stages"
    $lines += ""
    $lines += "| Stage | Result | Notes |"
    $lines += "| --- | --- | --- |"
    $results | ForEach-Object { $lines += ("| " + $_.Stage + " | " + $_.Verdict + " | " + $_.Detail + " |") }
    if (-not $success -and -not [string]::IsNullOrWhiteSpace($script:FailureExcerpt)) {
        $lines += ""
        $lines += "## Failure Excerpt (last 30 log lines)"
        $lines += '```'
        $script:FailureExcerpt -split "`n" | ForEach-Object { $lines += $_ }
        $lines += '```'
    }
    $lines += ""
    $lines += "## Artifact List"
    $artifacts = @(Get-ChildItem -Path $script:OutDir -Recurse -File | Where-Object { $_.FullName -notlike "*\logs\*" -and $_.Name -ne "nightly-report.md" })
    $lines += ("Total " + $artifacts.Count + " files:")
    $artifacts | ForEach-Object {
        $rel = $_.FullName.Substring($script:OutDir.Length).TrimStart('\').Replace('\', '/')
        $note = ""
        if ($rel -like "backend/example-service/*.jar") { $note = " (runnable: Spring Boot fat jar, spec hifi N3-2)" }
        $lines += ("- " + $rel + " (" + [Math]::Round($_.Length / 1KB, 1) + " KB)" + $note)
    }
    if ($success) {
        $lines += ""
        $lines += "## Image Tag List"
        $lines += 'See `image-tags.txt` (naming spec ADR-012 section 3.2).'
        $lines += "Note: connector-sdk line is a PLACEHOLDER (SDK lives in a separate repo, ADR-002) - not a pullable image tag."
        $lines += ""
        $lines += "## SBOM Reconciliation"
        $lines += 'sha256 ledger: `sbom/SHA256SUMS.txt`'
    }
    $lines += ""
    $lines += "## Quality Gate Report"
    $lines += 'Copy: `reports/gate-report.md`'
    $lines | Set-Content -Path $reportPath -Encoding UTF8
    Write-Host ("[NIGHTLY] report: " + $reportPath)
}

# ---------- stage runner (fail-fast with scene kept) ----------
# Conventions that keep this correct on Windows PowerShell 5.1:
#   1. Bodies take ($repoRoot, $outDir) as plain params - no $script: access.
#   2. Console progress uses Write-Host (host stream), never Write-Output,
#      otherwise the caller's `$ok = Invoke-Stage ...` captures the progress
#      lines and a FAIL boolean becomes a truthy array (fail-fast silently dies).
#   3. Inside a body $ErrorActionPreference is relaxed to Continue: native tools
#      (npm/npx/vite) write progress to stderr, and under Stop PS 5.1 raises a
#      terminating NativeCommandError for the first stderr line. Failure is
#      signalled via $LASTEXITCODE checks + explicit guards, never by stderr noise.
#   4. Stage bodies MUST NOT call `exit` (it would end the whole script); they
#      set $script:stageCode to nonzero and return on failure.
function Invoke-Stage([string]$stage, [scriptblock]$body) {
    $script:stageCode = 0
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $logFile = Join-Path $script:OutDir ("logs\" + $stage + ".log")
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $body $RepoRoot $script:OutDir 2>&1 | Out-File -FilePath $logFile -Encoding utf8
    } catch {
        $script:stageCode = 1
        ("[THROWN] " + $_.Exception.Message) | Out-File -FilePath $logFile -Append -Encoding utf8
    } finally {
        $ErrorActionPreference = $prevEap
    }
    $sw.Stop()
    $secs = ([int]$sw.Elapsed.TotalSeconds).ToString() + "s"
    if ($script:stageCode -ne 0) {
        $script:FailedStage = $stage
        $script:FailureExcerpt = ""
        if (Test-Path $logFile) {
            $tail = @(Get-Content -Path $logFile -Tail 30 -ErrorAction SilentlyContinue)
            $script:FailureExcerpt = ($tail -join "`n")
        }
        Add-Stage $stage "FAIL" ("stage code " + $script:stageCode + " (" + $secs + "; log: logs/" + $stage + ".log)")
        Write-Host ("[NIGHTLY] " + $stage + " FAIL - stage code " + $script:stageCode + ", log: " + $logFile)
        Write-Report $false
        return $false
    }
    Add-Stage $stage "PASS" $secs
    Write-Host ("[NIGHTLY] " + $stage + " PASS - " + $secs)
    return $true
}

# ---------- N2 quality gates (existing runner, zero behavior change) ----------
$gateOk = Invoke-Stage "N2-gates" {
    param($repoRoot, $outDir)
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/gates/run-gates.ps1") -RepoRoot $repoRoot -ReportPath (Join-Path $outDir "reports/gate-report.md")
    if ($LASTEXITCODE -ne 0) { $script:stageCode = $LASTEXITCODE }
}
if (-not $gateOk) { Write-Host ("[NIGHTLY] stopped at " + $script:FailedStage + " (exit 1)"); exit 1 }

# ---------- N3 backend package ----------
$backendOk = Invoke-Stage "N3-backend-package" {
    param($repoRoot, $outDir)
    Set-Location $repoRoot
    & mvn -B -ntp package -DskipTests
    if ($LASTEXITCODE -ne 0) { $script:stageCode = $LASTEXITCODE; return }
    $dest = Join-Path $outDir "backend"
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Get-ChildItem -Path $repoRoot -Recurse -Filter *.jar | Where-Object {
        $_.FullName -like "*\target\*" -and
        $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar"
    } | ForEach-Object {
        $moduleDir = Split-Path -Parent (Split-Path -Parent $_.FullName)
        $modName = Split-Path -Leaf $moduleDir
        $modDest = Join-Path $dest $modName
        New-Item -ItemType Directory -Path $modDest -Force | Out-Null
        Copy-Item -Path $_.FullName -Destination $modDest -Force
    }
    $collected = @(Get-ChildItem -Path $dest -Recurse -Filter *.jar)
    if ($collected.Count -lt 1) { $script:stageCode = 1; Write-Host "[N3][ERROR] no jar collected into backend artifacts" }
}
if (-not $backendOk) { Write-Host ("[NIGHTLY] stopped at " + $script:FailedStage + " (exit 1)"); exit 1 }

# ---------- N4 frontend build ----------
$frontendOk = Invoke-Stage "N4-frontend-build" {
    param($repoRoot, $outDir)
    Set-Location (Join-Path $repoRoot "frontend")
    & npm run build
    if ($LASTEXITCODE -ne 0) { Set-Location $repoRoot; $script:stageCode = $LASTEXITCODE; return }
    if (-not (Test-Path (Join-Path $repoRoot "frontend\dist\index.html"))) { Set-Location $repoRoot; $script:stageCode = 1; Write-Host "[N4][ERROR] frontend/dist/index.html not produced"; return }
    $dest = Join-Path $outDir "frontend\dist"
    New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force | Out-Null
    Copy-Item -Path (Join-Path $repoRoot "frontend\dist") -Destination $dest -Recurse -Force
    Set-Location $repoRoot
}
if (-not $frontendOk) { Write-Host ("[NIGHTLY] stopped at " + $script:FailedStage + " (exit 1)"); exit 1 }

# ---------- N5 SBOM (backend aggregate + frontend lock-based) ----------
$sbomOk = Invoke-Stage "N5-sbom" {
    param($repoRoot, $outDir)
    Set-Location $repoRoot
    & mvn -B -ntp ("org.cyclonedx:cyclonedx-maven-plugin:" + $CyclonedxMaven + ":makeAggregateBom") -DskipTests
    if ($LASTEXITCODE -ne 0) { $script:stageCode = $LASTEXITCODE; return }
    $bDest = Join-Path $outDir "sbom\backend"
    New-Item -ItemType Directory -Path $bDest -Force | Out-Null
    foreach ($f in @("bom.json", "bom.xml")) {
        $src = Join-Path $repoRoot ("target\" + $f)
        if (Test-Path $src) { Copy-Item -Path $src -Destination (Join-Path $bDest $f) -Force }
    }
    Set-Location (Join-Path $repoRoot "frontend")
    & npx ("@cyclonedx/cyclonedx-npm@" + $CyclonedxNpm) --package-lock-only --output-file (Join-Path $outDir "sbom\frontend\bom.json")
    if ($LASTEXITCODE -ne 0) { Set-Location $repoRoot; $script:stageCode = $LASTEXITCODE; return }
    & npx ("@cyclonedx/cyclonedx-npm@" + $CyclonedxNpm) --package-lock-only --output-format XML --output-file (Join-Path $outDir "sbom\frontend\bom.xml")
    if ($LASTEXITCODE -ne 0) { Set-Location $repoRoot; $script:stageCode = $LASTEXITCODE; return }
    Set-Location $repoRoot
    New-Item -ItemType Directory -Path (Join-Path $outDir "sbom\frontend") -Force | Out-Null
    $fDest = Join-Path $outDir "sbom\frontend"
    if (-not ((Test-Path (Join-Path $fDest "bom.json")) -and (Test-Path (Join-Path $bDest "bom.json")))) {
        $script:stageCode = 1
        Write-Host "[N5][ERROR] expected bom.json not found after SBOM generation"
        return
    }
    # sha256 ledger for reconciliation (spec ADR-012 3.3)
    $sums = Join-Path $outDir "sbom\SHA256SUMS.txt"
    $hashLines = @()
    Get-ChildItem -Path (Join-Path $outDir "sbom") -Recurse -File -Include *.json, *.xml | ForEach-Object {
        $rel = $_.FullName.Substring($outDir.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLower()
        $hashLines += ($hash + "  " + $rel)
    }
    $hashLines | Set-Content -Path $sums -Encoding UTF8
}
if (-not $sbomOk) { Write-Host ("[NIGHTLY] stopped at " + $script:FailedStage + " (exit 1)"); exit 1 }

# ---------- N6 image tags (ADR-012 3.2, single source: image-tag.ps1) ----------
$tagsOk = Invoke-Stage "N6-image-tags" {
    param($repoRoot, $outDir)
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/pipeline/image-tag.ps1") -All -OutFile (Join-Path $outDir "image-tags.txt")
    if ($LASTEXITCODE -ne 0) { $script:stageCode = $LASTEXITCODE; return }
    if (-not (Test-Path (Join-Path $outDir "image-tags.txt"))) { $script:stageCode = 1; Write-Host "[N6][ERROR] image-tags.txt not written" }
}
if (-not $tagsOk) { Write-Host ("[NIGHTLY] stopped at " + $script:FailedStage + " (exit 1)"); exit 1 }

# ---------- N7 done: final report ----------
Add-Stage "N7-report" "PASS" "summary report written"
Write-Report $true
Write-Host "[NIGHTLY] PASS"
exit 0
