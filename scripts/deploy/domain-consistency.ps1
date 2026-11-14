# C-TDS deployment-domain full consistency check (WBS-3.9.3, B9-R4 deploy-time carrier).
# Carrier decision (P7, orchestrator 2026-11-14): standalone script - deploy.ps1 (2.5.2,
# accepted) stays untouched; this script runs after it, or on its own for status switches.
#
# What it does:
#   A1 self-check: kubectl + cluster reachable + repo layout found.
#   A2 harvest ACTUAL facts: schemas wired in deploy/k8s manifests (CTDS_DB_URL values),
#      running deployments from the cluster, gateway route paths (reference only).
#   A3 compare against DECLARED values (parameters, = ctds.domain.* env you inject):
#      schema list and service list must match exactly (both directions, itemized).
#   A4 lifecycle registration guard: -Action requires -ChangeOrderNo and -Operator
#      (B9-R3 three keys all-or-nothing - mirrors the application fail-closed emitter).
#   A5 append a deployment record with before/after status and version (B9-4 evidence #2)
#      to build-output/deploy/domain-record.md.
#
# Exit codes: 0 = consistent (or nothing declared -> skipped with note), 1 = mismatch,
# 2 = self-check failure.
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\domain-consistency.ps1 `
#     -DeclaredSchemas "ctds_evidence,ctds_subject" -DeclaredServices "evidence-service,subject-service"
#   powershell ... domain-consistency.ps1 -Action RETIRE -ExpectedStatus RETIRED `
#     -ChangeOrderNo "CHG-2026-001" -Operator "ops-a" -DeclaredVersion "V1.0"
param(
    [string]$DeclaredSchemas = "",
    [string]$DeclaredServices = "",
    [string]$ExpectedStatus = "ACTIVE",
    [string]$DeclaredVersion = "",
    [string]$DeclaredDomainId = "",
    [string]$Action = "",
    [string]$ChangeOrderNo = "",
    [string]$Operator = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$script:StepCount = 0
$script:Failures = New-Object System.Collections.Generic.List[string]

function Add-Step([string]$step, [string]$verdict, [string]$detail) {
    $script:StepCount++
    Write-Host ("[DOMAIN-{0}] {1} {2}" -f $step, $verdict, $detail)
    if ($verdict -eq "FAIL") {
        $script:Failures.Add($detail) | Out-Null
    }
}

# Native commands run through cmd /c with stderr merged inside cmd (PS 5.1 discipline,
# scripts/deploy/deploy.ps1 Invoke-Native precedent). Drain fully, judge by exit code.
function Invoke-Native([string]$cmdLine) {
    $output = cmd /c "$cmdLine 2>&1"
    return @{ ExitCode = $LASTEXITCODE; Output = ($output -join "`r`n") }
}

function Split-List([string]$raw) {
    if ([string]::IsNullOrWhiteSpace($raw)) { return @() }
    return ($raw.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
}

Write-Host "[DOMAIN] deployment-domain consistency check (WBS-3.9.3 B9-R4)"

# ---------- A1 self-check ----------
$repoRoot = (Get-Location).Path
$manifestDir = Join-Path $repoRoot "deploy\k8s"
$gatewayYml = Join-Path $repoRoot "services\gateway-service\src\main\resources\application.yml"
$recordPath = Join-Path $repoRoot "build-output\deploy\domain-record.md"

$check = Invoke-Native "kubectl get nodes -o name"
if ($check.ExitCode -ne 0) {
    Add-Step "A1" "FAIL" "kubectl/cluster not reachable: $($check.Output)"
    Write-Host "[DOMAIN] FAIL (self-check) - fix the environment, then re-run"
    exit 2
}
if (-not (Test-Path $manifestDir)) {
    Add-Step "A1" "FAIL" "manifest directory not found: $manifestDir"
    Write-Host "[DOMAIN] FAIL (self-check)"
    exit 2
}
if (-not (Test-Path $gatewayYml)) {
    Add-Step "A1" "FAIL" "gateway config not found: $gatewayYml"
    Write-Host "[DOMAIN] FAIL (self-check)"
    exit 2
}
Add-Step "A1" "PASS" "kubectl reachable, manifest dir and gateway config found"

# ---------- A2 harvest actual facts ----------
$actualSchemas = New-Object System.Collections.Generic.List[string]
Get-ChildItem $manifestDir -Filter "*-deployment.yaml" | ForEach-Object {
    $lines = Get-Content -Path $_.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "name:\s*CTDS_DB_URL") {
            for ($j = $i + 1; $j -lt [Math]::Min($i + 3, $lines.Count); $j++) {
                if ($lines[$j] -match "jdbc:mysql://[^/]+/([A-Za-z0-9_]+)") {
                    $actualSchemas.Add($Matches[1]) | Out-Null
                    break
                }
            }
        }
    }
}
Add-Step "A2" "PASS" ("schemas wired in manifests: " + (($actualSchemas | Sort-Object -Unique) -join ", "))

$deployments = Invoke-Native "kubectl get deployments -o name"
$actualServices = @()
if ($deployments.ExitCode -eq 0) {
    $actualServices = $deployments.Output -split "`r?`n" |
        ForEach-Object { $_.Trim() -replace "^deployment\.apps/", "" } |
        Where-Object { $_ -like "ctds-*" } |
        ForEach-Object { $_ -replace "^ctds-", "" -replace "-service$", "-service" }
}
Add-Step "A2" "PASS" ("cluster deployments (ctds-*): " + (($actualServices | Sort-Object -Unique) -join ", "))

$routeLines = Select-String -Path $gatewayYml -Pattern "Path=(/api/v1/[^\s""']+)" -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
Add-Step "A2" "PASS" ("gateway routes (reference): " + ($routeLines -join ", "))

# ---------- A3 declared vs actual ----------
# @() wrapper: Split-List's empty output unwinds to $null, and .Count on $null
# fails under StrictMode (caught in the 2026-11-14 smoke run).
# Local names differ from the parameters on purpose: PowerShell variables are
# CASE-INSENSITIVE, so $declaredSchemas IS the [string]-typed parameter
# $DeclaredSchemas - assigning the array back would coerce it to a string
# (caught in the 2026-11-14 smoke run; see runbook-adjacent lesson).
$schemaList = @(Split-List $DeclaredSchemas)
$serviceList = @(Split-List $DeclaredServices)

if ($schemaList.Count -eq 0 -and $serviceList.Count -eq 0) {
    Add-Step "A3" "PASS" ("no declared ctds.domain.* lists provided -> consistency check skipped" +
        " (default ACTIVE zero-behavior baseline; provide -DeclaredSchemas/-DeclaredServices to check)")
} else {
    if ($schemaList.Count -gt 0) {
        $actualUnique = @($actualSchemas | Sort-Object -Unique)
        $missingInActual = @($schemaList | Where-Object { $actualUnique -notcontains $_ })
        $missingInDeclared = @($actualUnique | Where-Object { $schemaList -notcontains $_ })
        if ($missingInActual.Count -eq 0 -and $missingInDeclared.Count -eq 0) {
            Add-Step "A3" "PASS" ("schema list matches manifests exactly: " + ($schemaList -join ", "))
        } else {
            if ($missingInActual.Count -gt 0) {
                Add-Step "A3" "FAIL" ("declared schemas absent from manifests: " + ($missingInActual -join ", "))
            }
            if ($missingInDeclared.Count -gt 0) {
                Add-Step "A3" "FAIL" ("manifest schemas absent from declared list: " + ($missingInDeclared -join ", "))
            }
        }
    }
    if ($serviceList.Count -gt 0) {
        $actualUniqueServices = @($actualServices | Sort-Object -Unique)
        $missingServices = @($serviceList | Where-Object { $actualUniqueServices -notcontains $_ })
        if ($missingServices.Count -eq 0) {
            Add-Step "A3" "PASS" ("service list covered by cluster deployments: " + ($serviceList -join ", "))
        } else {
            Add-Step "A3" "FAIL" ("declared services not running in cluster: " + ($missingServices -join ", "))
        }
    }
}

# ---------- A4 lifecycle registration guard (B9-R3 mirror) ----------
if ($Action -ne "") {
    $keys = @(@($Action, $ChangeOrderNo, $Operator) | Where-Object { $_ -ne "" }).Count
    if ($keys -gt 0 -and $keys -lt 3) {
        Add-Step "A4" "FAIL" ("lifecycle registration incomplete: -Action/-ChangeOrderNo/-Operator" +
            " must be all empty or all set (B9-R3); got " + $keys + " of 3")
    } else {
        if (@("OPEN", "UPGRADE", "RETIRE", "RESTORE") -notcontains $Action.ToUpperInvariant()) {
            Add-Step "A4" "FAIL" ("illegal -Action value: " + $Action + " (OPEN/UPGRADE/RETIRE/RESTORE only)")
        } else {
            Add-Step "A4" "PASS" ("lifecycle registration complete: " + $Action +
                " / " + $ChangeOrderNo + " / " + $Operator)
        }
    }
}
if (@("ACTIVE", "RETIRED") -notcontains $ExpectedStatus.ToUpperInvariant()) {
    Add-Step "A4" "FAIL" ("illegal -ExpectedStatus value: " + $ExpectedStatus + " (ACTIVE/RETIRED only)")
}

# ---------- A5 deployment record (B9-4 evidence: before/after values) ----------
$previousStatus = "n/a"
$previousVersion = "n/a"
if (Test-Path $recordPath) {
    # Positional split parse (three smoke-run lessons: a greedy regex mis-captures the
    # source column; dropping empty cells shifts indexes when domain id is blank; and
    # the "before" chain reads the LAST record's AFTER columns, not its own before).
    # Fragments: [0]="" [1]=time [2]=action [3]=change [4]=operator [5]=domain
    # [6]=status-after [7]=version-after [8]=status-before [9]=version-before
    # [10]=verdict [11]=source [12]="" (verdict column added in review 2 F3)
    $lastRecord = Get-Content -Path $recordPath |
        Where-Object { $_ -like "| * |*" } | Select-Object -Last 1
    if ($lastRecord) {
        $parts = $lastRecord.Split("|")
        if ($parts.Count -ge 11) {
            $previousStatus = $parts[6].Trim()
            $previousVersion = $parts[7].Trim()
        }
    }
}
New-Item -ItemType Directory -Force -Path (Split-Path $recordPath) | Out-Null
# Record verdict (review 2 F3): every appended line carries the check verdict of its
# own run, so a FAIL run never masquerades as an executed status switch in the chain.
$recordVerdict = "PASS"
if ($script:Failures.Count -gt 0) { $recordVerdict = "FAIL" }
$recordLine = "| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} |" -f @(
    (Get-Date -Format "yyyy-MM-dd HH:mm:ss"),
    $Action, $ChangeOrderNo, $Operator, $DeclaredDomainId,
    $ExpectedStatus.ToUpperInvariant(), $DeclaredVersion,
    $previousStatus, $previousVersion, $recordVerdict, "domain-consistency.ps1")
if (-not (Test-Path $recordPath)) {
    $header = @("# C-TDS deployment-domain lifecycle record (B9-4 evidence: change order +",
        "# deployment record with before/after values; audit event is the third evidence,",
        "# written by the service on startup when ctds.domain.* registration keys are set).",
        "# verdict = this run's own check result (a FAIL row marks a switch that did NOT apply).",
        "",
        "| time (local) | action | change order | operator | domain id | status (after) | version (after) | status (before) | version (before) | verdict | source |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    Set-Content -Path $recordPath -Value $header -Encoding UTF8
}
Add-Content -Path $recordPath -Value $recordLine -Encoding UTF8
Add-Step "A5" "PASS" ("deployment record appended (before: status " + $previousStatus +
    " / version " + $previousVersion + " -> after: status " + $ExpectedStatus.ToUpperInvariant() +
    " / version " + $DeclaredVersion + ") to " + $recordPath)

# ---------- verdict ----------
Write-Host ("[DOMAIN] steps: " + $script:StepCount + ", failures: " + $script:Failures.Count)
if ($script:Failures.Count -gt 0) {
    Write-Host "[DOMAIN] FAIL - fix the items above, then re-run"
    exit 1
}
Write-Host "[DOMAIN] PASS"
exit 0
