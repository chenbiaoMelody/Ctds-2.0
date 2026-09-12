# C-TDS monitoring baseline toolchain (WBS 2.5.3, spec: ADR-014).
# One command: self-check (cluster / three pinned images / platform app
# deployed) -> load images into the cluster node -> apply deploy/k8s-monitoring
# -> rollout wait -> metrics-link smoke (Prometheus API) -> Grafana NodePort
# 30082 patch + persistent port-forward + dashboard smoke -> end-to-end alert
# drill (scale backend to 0 -> firing -> Alertmanager received -> silence
# roundtrip -> restore -> resolved) -> business-readable report.
# -Teardown removes the ctds-monitoring namespace and stops the port-forwards.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\monitoring.ps1
#   powershell ... monitoring.ps1 -Teardown
# Exit codes: 0 = all green, 1 = deploy/smoke/drill failure, 2 = self-check failure.
param(
    [string]$RepoRoot = "",
    [int]$RolloutTimeoutSeconds = 120,
    [int]$DrillTimeoutSeconds = 300,
    [switch]$Teardown
)
$ErrorActionPreference = "Stop"

if ($RepoRoot -eq "") {
    if ($PSScriptRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
    else { $RepoRoot = (Get-Location).Path }
}
if (-not (Test-Path (Join-Path $RepoRoot "pom.xml"))) {
    Write-Host "[MONITOR][CONFIG-ERROR] RepoRoot not found or pom.xml missing: $RepoRoot"
    exit 2
}

# Pinned monitoring images (dependencies.md, ADR-014); Grafana entry port
# 30082 per hifi S1 (dev/test access follows the ADR-013 §8.5 port-forward
# convention: kind-mode clusters do not map NodePorts to localhost).
$MonitorImages = @("prom/prometheus:v3.13.3", "prom/alertmanager:v0.34.0", "grafana/grafana:13.2.1")
$GrafanaNodePort = 30082
$TempPorts = @{ prometheus = 19090; alertmanager = 19093 }

$results = New-Object System.Collections.Generic.List[object]
$script:selfOk = $true
$script:FailedStep = ""
$script:FailureExcerpt = ""

function Add-Step([string]$step, [string]$verdict, [string]$detail) {
    $script:results.Add([pscustomobject]@{ Step = $step; Verdict = $verdict; Detail = $detail })
}

# Native commands run through cmd /c with stderr merged inside cmd: under
# $ErrorActionPreference=Stop a raw `native 2>&1` turns the first stderr
# line into a terminating error (PS 5.1). Judge by exit code only.
function Invoke-Native([string]$cmdLine) {
    $out = @()
    try { $out = @(& cmd /c $cmdLine) } catch { }
    $rc = -1
    if ($null -ne $LASTEXITCODE) { $rc = $LASTEXITCODE }
    return @($out), $rc
}

function Report-Check([bool]$ok, [string]$msg) {
    if (-not $ok) { $script:selfOk = $false; Write-Host ("[MONITOR][CHECK-FAIL] " + $msg) }
    else { Write-Host ("[MONITOR][CHECK-OK] " + $msg) }
}

# Kill ONLY the recorded process if it is still a kubectl forwarder: pid
# files can go stale and the OS may reuse the id for an unrelated process.
function Stop-PortForwardPids($pidLines) {
    foreach ($p in $pidLines) {
        if ($p -match "^\d+$") {
            $proc = Get-Process -Id ([int]$p) -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -eq "kubectl") {
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

# UTF-8 WITHOUT BOM (PS 5.1 Set-Content UTF8 emits a BOM that kubectl rejects).
function Write-TextFile([string]$path, $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]]($lines | ForEach-Object { "$_" }))
}

if ($Teardown) {
    # Independent early path: stop recorded forwards, delete the namespace.
    $monRoot = Join-Path $RepoRoot "build-output\monitoring"
    if (Test-Path $monRoot) {
        foreach ($f in (Get-ChildItem $monRoot -Recurse -Filter "port-forward.pids" -ErrorAction SilentlyContinue)) {
            Stop-PortForwardPids (Get-Content $f.FullName -ErrorAction SilentlyContinue)
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host "[MONITOR] tearing down monitoring namespace ctds-monitoring..."
    $out, $code = Invoke-Native "kubectl delete namespace ctds-monitoring --timeout=120s 2>&1"
    foreach ($l in $out) { Write-Host ("  " + $l) }
    $ok = ($code -eq 0) -or ("$out" -match "NotFound")
    if ("$out" -match "NotFound") {
        Write-Host "[MONITOR] nothing to clean (namespace not found) - treated as success"
    }
    if (-not $ok) {
        Write-Host "[MONITOR] FAIL: teardown failed (exit $code)"
        exit 1
    }
    if (-not (Test-Path $monRoot)) { New-Item -ItemType Directory -Path $monRoot -Force | Out-Null }
    $tr = New-Object System.Collections.Generic.List[string]
    $tr.Add("# C-TDS monitoring teardown report (WBS 2.5.3)")
    $tr.Add("")
    $tr.Add("- Time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
    $tr.Add("- Namespace deleted: ctds-monitoring (exit " + $code + ")")
    $tr.Add("- Port-forwards: recorded pids stopped (kubectl processes only)")
    $tr.Add("- Result: **PASS**")
    $tr.Add("")
    $tr.Add("[MONITOR] PASS")
    [System.IO.File]::WriteAllLines((Join-Path $monRoot "teardown-report.md"), [string[]]$tr)
    Write-Host "[MONITOR] PASS (teardown)"
    exit 0
}

# ---------- S1 environment self-check ----------
Write-Host "[MONITOR] step 1/6 environment self-check..."
$out, $code = Invoke-Native "kubectl version --request-timeout=32s 2>&1"
$serverSeen = ($out | ForEach-Object { "$_" }) -match "Server Version"
Report-Check ($code -eq 0 -and [bool]$serverSeen) "kubectl available AND cluster reachable (if this fails: is the Docker Desktop built-in Kubernetes enabled? see deploy/runbook.md)"

foreach ($img in $MonitorImages) {
    $out, $code = Invoke-Native ("docker image inspect """ + $img + """ --format exists 2>&1")
    Report-Check ($code -eq 0) ("local image exists: " + $img + " (if missing: docker pull " + $img + " via the Docker Desktop mirror channel)")
}

# The dashboards need platform metrics and the drill needs a live backend:
# without the app the stack deploys but verifies nothing (hifi S3 decision).
$out, $code = Invoke-Native "kubectl get deployment ctds-backend -n default --request-timeout=16s 2>&1"
Report-Check ($code -eq 0) ("platform app deployed (ctds-backend in default namespace) - if missing: run deploy.ps1 first, monitoring.ps1 does not deploy the app itself")

$out, $code = Invoke-Native "curl.exe --version 2>&1"
Report-Check ($code -eq 0) ("curl.exe available for smoke test: " + "$($out[0])")

if (-not $selfOk) {
    Write-Host "[MONITOR] environment self-check failed - fix the items above and rerun (exit 2)."
    exit 2
}
Add-Step "S1 environment self-check" "PASS" ("cluster reachable / " + $MonitorImages.Count + " pinned images local / ctds-backend deployed / curl.exe")

# ---------- S2 load images into the cluster node ----------
# The Docker Desktop built-in cluster (kind mode) has its own containerd and
# does NOT see the docker daemon's local images (measured in the 2.5.2 drill).
Write-Host "[MONITOR] step 2/6 loading monitoring images into the cluster node..."
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$copyRoot = Join-Path (Join-Path $RepoRoot "build-output\monitoring") $stamp
New-Item -ItemType Directory -Path $copyRoot -Force | Out-Null
$nodeOut, $nc = Invoke-Native "kubectl get nodes --no-headers -o custom-columns=:metadata.name 2>&1"
$node = ""
if ($nc -eq 0 -and $nodeOut.Count -gt 0) { $node = ("$($nodeOut[0])").Trim() }
if ($node -eq "") {
    $script:FailedStep = "image-load"
    $script:FailureExcerpt = "could not resolve a cluster node name (kubectl get nodes)"
} else {
    foreach ($img in $MonitorImages) {
        $out, $code = Invoke-Native ("docker save """ + $img + """ | docker exec -i " + $node + " ctr --namespace k8s.io images import - 2>&1")
        if ($code -ne 0) {
            $script:FailedStep = "image-load"
            $script:FailureExcerpt = "docker save/import failed for " + $img + ":`n" + (($out | Select-Object -Last 5) -join "`n")
            break
        }
        $lsOut, $lc = Invoke-Native ("docker exec " + $node + " ctr --namespace k8s.io images ls 2>&1")
        $found = ($lsOut | ForEach-Object { "$_" }) -match [regex]::Escape($img)
        if ($lc -ne 0 -or -not [bool]$found) {
            $script:FailedStep = "image-load"
            $script:FailureExcerpt = "image " + $img + " not present in node containerd after import"
            break
        }
        Write-Host ("  loaded into node: " + $img)
    }
    if ($script:FailedStep -eq "") {
        Add-Step "S2 image load (docker save -> node ctr import)" "PASS" ("node: " + $node + ", " + $MonitorImages.Count + " monitoring images loaded and verified")
    }
}

# ---------- S3 apply + rollout ----------
if ($script:FailedStep -eq "") {
    Write-Host "[MONITOR] step 3/6 applying monitoring stack and waiting for rollout..."
    $k8sDir = Join-Path $RepoRoot "deploy\k8s-monitoring"
    $out, $code = Invoke-Native ("kubectl apply -f """ + $k8sDir + """ 2>&1")
    if ($code -ne 0) {
        $script:FailedStep = "apply"
        $script:FailureExcerpt = ($out | Select-Object -Last 10) -join "`n"
    } else {
        foreach ($l in $out) { Write-Host ("  " + $l) }
        Add-Step "S3a kubectl apply -f deploy/k8s-monitoring" "PASS" ("manifests: " + $k8sDir)
    }
}
if ($script:FailedStep -eq "") {
    foreach ($dep in @("ctds-prometheus", "ctds-alertmanager", "ctds-grafana")) {
        $out, $code = Invoke-Native ("kubectl -n ctds-monitoring rollout status deployment/" + $dep + " --timeout=" + $RolloutTimeoutSeconds + "s 2>&1")
        if ($code -ne 0) {
            $script:FailedStep = "rollout"
            $podOut, $null = Invoke-Native "kubectl -n ctds-monitoring get pods -o wide 2>&1"
            $script:FailureExcerpt = "rollout of " + $dep + " failed or timed out.`n" + (($podOut | Select-Object -Last 15) -join "`n")
            break
        }
        Write-Host ("  rollout ok: " + $dep)
    }
    if ($script:FailedStep -eq "") {
        Add-Step "S3b rollout status x3" "PASS" ("prometheus / alertmanager / grafana ready within " + $RolloutTimeoutSeconds + "s each")
    }
}

# ---------- detached port-forward helpers ----------
$kubectl = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
$pfPidFile = Join-Path $copyRoot "port-forward.pids"
if ([string]::IsNullOrEmpty($kubectl)) {
    if ($script:FailedStep -eq "") {
        $script:FailedStep = "port-forward"
        $script:FailureExcerpt = "kubectl not found on PATH"
    }
}
# Reap detached forwards from previous monitoring runs so reruns stay clean.
if (Test-Path (Join-Path $RepoRoot "build-output\monitoring")) {
    foreach ($f in (Get-ChildItem (Join-Path $RepoRoot "build-output\monitoring") -Recurse -Filter "port-forward.pids" -ErrorAction SilentlyContinue)) {
        if ($f.FullName -ne $pfPidFile) {
            Stop-PortForwardPids (Get-Content $f.FullName -ErrorAction SilentlyContinue)
        }
    }
}
$pf = New-Object System.Collections.Generic.List[string]
$pfPermanent = New-Object System.Collections.Generic.List[string]

function Start-Forward([string]$svc, [string]$port, [System.Collections.Generic.List[string]]$bucket) {
    $proc = Start-Process -FilePath $kubectl -ArgumentList @("-n", "ctds-monitoring", "port-forward", $svc, $port) -WindowStyle Hidden -PassThru
    $bucket.Add([string]$proc.Id)
    Write-Host ("  port-forward " + $port + " (pid " + $proc.Id + ")")
}

function Stop-TempForwards {
    foreach ($p in $pf) { Stop-PortForwardPids @($p) }
    $pf.Clear()
}

# ---------- S4 metrics-link smoke (Prometheus API) ----------
if ($script:FailedStep -eq "") {
    Write-Host "[MONITOR] step 4/6 metrics-link smoke (Prometheus API)..."
    Start-Forward "svc/ctds-prometheus" ($TempPorts.prometheus.ToString() + ":9090") $pf
    [System.IO.File]::WriteAllLines($pfPidFile, [string[]]($pf + $pfPermanent))
    Start-Sleep -Seconds 4
    $query = "http://localhost:" + $TempPorts.prometheus + "/api/v1/query?query=up%7Bjob%3D%22ctds-backend%22%7D"
    $out, $code = Invoke-Native ("curl.exe -s --max-time 10 """ + $query + """ 2>&1")
    $body = ($out | ForEach-Object { "$_" }) -join ""
    $ok = ($code -eq 0) -and ($body -match '"value":\["\d+(\.\d+)?","1"\]')
    if ($ok) {
        Add-Step "S4 metrics-link smoke" "PASS" ("Prometheus scrapes ctds-backend: up{job=""ctds-backend""} == 1 (annotation-based pod discovery)")
    } else {
        $script:FailedStep = "metrics-smoke"
        $script:FailureExcerpt = "expected up{job=ctds-backend}==1 from the Prometheus API, got: " + $body.Substring(0, [Math]::Min(300, $body.Length)) + " (check: backend exposes /actuator/prometheus and carries the prometheus.io/* annotations; port " + $TempPorts.prometheus + " not occupied by other software)"
    }
}

# ---------- S5 Grafana entry + dashboard smoke ----------
if ($script:FailedStep -eq "") {
    Write-Host "[MONITOR] step 5/6 Grafana NodePort patch + dashboard smoke..."
    # Base templates stay ClusterIP: the NodePort lives on the live object via
    # a tiny apply-file (idempotent), avoiding inline JSON quoting in cmd.
    $npFile = Join-Path $copyRoot "grafana-nodeport.yaml"
    Write-TextFile $npFile @(
        "apiVersion: v1",
        "kind: Service",
        "metadata:",
        "  name: ctds-grafana",
        "  namespace: ctds-monitoring",
        "spec:",
        "  type: NodePort",
        "  selector:",
        "    app.kubernetes.io/name: ctds-grafana",
        "  ports:",
        "    - port: 3000",
        "      targetPort: 3000",
        "      nodePort: " + $GrafanaNodePort
    )
    $out, $code = Invoke-Native ("kubectl apply -f """ + $npFile + """ 2>&1")
    if ($code -ne 0) {
        $script:FailedStep = "grafana-entry"
        $script:FailureExcerpt = "NodePort patch failed:`n" + (($out | Select-Object -Last 5) -join "`n")
    } else {
        # Persistent business entry: survives this script, stopped by -Teardown.
        Start-Forward "svc/ctds-grafana" ($GrafanaNodePort.ToString() + ":3000") $pfPermanent
        [System.IO.File]::WriteAllLines($pfPidFile, [string[]]($pf + $pfPermanent))
        Start-Sleep -Seconds 4
        $checks = @(
            @{ Name = "Grafana health via http://localhost:30082/api/health"; Url = "http://localhost:30082/api/health"; Expect = "200" },
            @{ Name = "baseline dashboard provisioned (/api/dashboards/uid/ctds-baseline)"; Url = "http://localhost:30082/api/dashboards/uid/ctds-baseline"; Expect = "200" }
        )
        $summary = @()
        foreach ($c in $checks) {
            $out, $code = Invoke-Native ("curl.exe -s -o NUL -w %{http_code} --max-time 10 " + $c.Url)
            $httpCode = ""
            if ($out.Count -gt 0) { $httpCode = ("$($out[0])").Trim() }
            $ok = ($code -eq 0) -and ($httpCode -eq $c.Expect)
            $summary += ($c.Name + " => " + $httpCode)
            if (-not $ok) {
                $script:FailedStep = "grafana-smoke"
                $script:FailureExcerpt = $c.Name + ": expected " + $c.Expect + ", got HTTP " + $httpCode + " (curl exit " + $code + "). If unreachable, check whether localhost port " + $GrafanaNodePort + " is occupied by other software."
                break
            }
        }
        if ($script:FailedStep -eq "") {
            Add-Step "S5 grafana entry + dashboard smoke" "PASS" (($summary -join "; ") + "; entry forward detached (survives this script, stopped by -Teardown)")
        }
    }
}

# ---------- S6 end-to-end alert drill ----------
# The drill scales the platform backend down by design; ANY later failure must
# still restore it (the app must never be left at 0 replicas by this script).
$script:drillScaledDown = $false
$script:drillRestored = $true
if ($script:FailedStep -eq "") {
    Write-Host "[MONITOR] step 6/6 end-to-end alert drill (backend scaled to 0 -> firing -> silence -> restore -> resolved)..."
    $promBase = "http://localhost:" + $TempPorts.prometheus
    $amBase = "http://localhost:" + $TempPorts.alertmanager

    $out, $code = Invoke-Native "kubectl -n default scale deployment ctds-backend --replicas=0 2>&1"
    if ($code -ne 0) {
        $script:FailedStep = "drill-scale-down"
        $script:FailureExcerpt = ($out | Select-Object -Last 5) -join "`n"
    } else {
        $script:drillScaledDown = $true
        $script:drillRestored = $false
        Write-Host "  backend scaled to 0; waiting for CtdsBackendDown to fire (cap " + $DrillTimeoutSeconds + "s)..."
        $fired = $false
        $waited = 0
        while ($waited -lt $DrillTimeoutSeconds) {
            Start-Sleep -Seconds 15
            $waited += 15
            $out, $code = Invoke-Native ("curl.exe -s --max-time 10 """ + $promBase + "/api/v1/alerts"" 2>&1")
            $body = ($out | ForEach-Object { "$_" }) -join ""
            if ($body -match '"name":"CtdsBackendDown"' -and $body -match '"state":"firing"') { $fired = $true; break }
            Write-Host ("    ...waiting (" + $waited + "s)")
        }
        if (-not $fired) {
            $script:FailedStep = "drill-firing"
            $script:FailureExcerpt = "CtdsBackendDown did not reach firing within " + $DrillTimeoutSeconds + "s (check the absent/up rule and the scrape link)"
        } else {
            Write-Host "  alert FIRED"
            Add-Step "S6a drill: alert fired on service down" "PASS" ("CtdsBackendDown firing observed after backend scaled to 0")

            # Alertmanager received the routed alert (default route, grouped).
            Start-Forward "svc/ctds-alertmanager" ($TempPorts.alertmanager.ToString() + ":9093") $pf
            [System.IO.File]::WriteAllLines($pfPidFile, [string[]]($pf + $pfPermanent))
            Start-Sleep -Seconds 4
            $out, $code = Invoke-Native ("curl.exe -s --max-time 10 """ + $amBase + "/api/v2/alerts"" 2>&1")
            $amBody = ($out | ForEach-Object { "$_" }) -join ""
            $amOk = ($code -eq 0) -and ($amBody -match '"CtdsBackendDown"')
            if ($amOk) {
                Add-Step "S6b drill: alert routed to Alertmanager" "PASS" "alert visible via Alertmanager API (default route ctds-default)"
            } else {
                $script:FailedStep = "drill-routing"
                $script:FailureExcerpt = "CtdsBackendDown not found in Alertmanager API: " + $amBody.Substring(0, [Math]::Min(300, $amBody.Length))
            }
        }
    }

    # Silence roundtrip (create + delete) proves the routing/silencing API.
    if ($script:FailedStep -eq "") {
        $endsAt = (Get-Date).ToUniversalTime().AddMinutes(5).ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        $silenceFile = Join-Path $copyRoot "drill-silence.json"
        Write-TextFile $silenceFile @(
            '{"matchers":[{"name":"alertname","value":"CtdsBackendDown","isRegex":false}],'
            '"startsAt":"' + (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ") + '",'
            '"endsAt":"' + $endsAt + '",'
            '"createdBy":"monitoring.ps1 drill","comment":"smoke silence"}'
        )
        $out, $code = Invoke-Native ("curl.exe -s -X POST --data-binary @""" + $silenceFile + """ --max-time 10 """ + $amBase + "/api/v2/silences"" 2>&1")
        $silBody = ($out | ForEach-Object { "$_" }) -join ""
        if ($code -eq 0 -and $silBody -match '"silenceID":"([\w\-]+)"') {
            $sid = $Matches[1]
            $out, $code = Invoke-Native ("curl.exe -s -X DELETE --max-time 10 """ + $amBase + "/api/v2/silence/" + $sid + """ 2>&1")
            if ($code -eq 0) {
                Add-Step "S6c drill: silence roundtrip" "PASS" ("silence " + $sid + " created then deleted via Alertmanager API")
            } else {
                $script:FailedStep = "drill-silence"
                $script:FailureExcerpt = "silence delete failed (exit " + $code + ")"
            }
        } else {
            $script:FailedStep = "drill-silence"
            $script:FailureExcerpt = "silence create failed: " + $silBody.Substring(0, [Math]::Min(300, $silBody.Length))
        }
    }

    # Restore and wait until the alert resolves (no longer firing).
    if ($script:FailedStep -eq "") {
            $out, $code = Invoke-Native "kubectl -n default scale deployment ctds-backend --replicas=1 2>&1"
            if ($code -ne 0) {
                $script:FailedStep = "drill-restore"
                $script:FailureExcerpt = ($out | Select-Object -Last 5) -join "`n"
            } else {
                $script:drillRestored = $true
            $out, $code = Invoke-Native ("kubectl -n default rollout status deployment/ctds-backend --timeout=" + $RolloutTimeoutSeconds + "s 2>&1")
            if ($code -ne 0) {
                $script:FailedStep = "drill-restore"
                $script:FailureExcerpt = "backend rollout after restore failed"
            } else {
                Write-Host "  backend restored; waiting for CtdsBackendDown to resolve..."
                $resolved = $false
                $waited = 0
                while ($waited -lt $DrillTimeoutSeconds) {
                    Start-Sleep -Seconds 15
                    $waited += 15
                    $out, $code = Invoke-Native ("curl.exe -s --max-time 10 """ + $promBase + "/api/v1/alerts"" 2>&1")
                    $body = ($out | ForEach-Object { "$_" }) -join ""
                    if (-not ($body -match '"name":"CtdsBackendDown"' -and $body -match '"state":"firing"')) { $resolved = $true; break }
                    Write-Host ("    ...waiting (" + $waited + "s)")
                }
                if ($resolved) {
                    Add-Step "S6d drill: alert resolved after restore" "PASS" ("backend back to 1 replica; CtdsBackendDown no longer firing")
                } else {
                    $script:FailedStep = "drill-resolve"
                    $script:FailureExcerpt = "CtdsBackendDown still firing " + $DrillTimeoutSeconds + "s after restore"
                }
            }
        }
    }
}

# Recycle the temporary forwards (prometheus/alertmanager); the Grafana entry
# forward stays (business entry, listed in the report, stopped by -Teardown).
Stop-TempForwards
[System.IO.File]::WriteAllLines($pfPidFile, [string[]]$pfPermanent)

# Safety net: if a drill step after scale-down failed, restore the backend now
# (the failure is reported, but the app is never left at 0 replicas).
if ($script:drillScaledDown -and -not $script:drillRestored) {
    Write-Host "[MONITOR] drill failed mid-way - restoring backend to 1 replica (best effort)..."
    $null = Invoke-Native "kubectl -n default scale deployment ctds-backend --replicas=1 2>&1"
    $null = Invoke-Native ("kubectl -n default rollout status deployment/ctds-backend --timeout=" + $RolloutTimeoutSeconds + "s 2>&1")
}

# ---------- S7 report ----------
$reportPath = Join-Path $copyRoot "monitoring-report.md"
$verdict = "FAIL"
if ($script:FailedStep -eq "") { $verdict = "PASS" }

$rep = New-Object System.Collections.Generic.List[string]
$rep.Add("# C-TDS monitoring baseline report (WBS 2.5.3)")
$rep.Add("")
$rep.Add("- Time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
$rep.Add("- Working dir: " + $copyRoot)
$rep.Add("- Verdict: **" + $verdict + "**")
$rep.Add("")
$rep.Add("## Steps")
$rep.Add("")
$rep.Add("| Step | Verdict | Detail |")
$rep.Add("| --- | --- | --- |")
foreach ($r in $results) { $rep.Add("| " + $r.Step + " | " + $r.Verdict + " | " + ($r.Detail -replace "\|", "/") + " |") }
if ($script:FailedStep -ne "") {
    $rep.Add("| " + $script:FailedStep + " | FAIL | (see excerpt below) |")
}
$rep.Add("")
$rep.Add("## Access entries (dev/test)")
$rep.Add("")
$rep.Add("- Grafana baseline dashboard: http://localhost:30082 (anonymous Viewer; opens without login)")
$rep.Add("- Served by a detached kubectl port-forward (kind-mode clusters do not map NodePorts to localhost, measured): keeps running after this script exits.")
$rep.Add("- Ad-hoc troubleshooting UIs (not started by this script): kubectl -n ctds-monitoring port-forward svc/ctds-prometheus 9090:9090 / svc/ctds-alertmanager 9093:9093")
$rep.Add("- Teardown (removes the monitoring namespace AND stops the port-forwards): powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\monitoring.ps1 -Teardown")
if ($script:FailureExcerpt -ne "") {
    $rep.Add("")
    $rep.Add("## Failure excerpt (" + $script:FailedStep + ")")
    $rep.Add("")
    $rep.Add('```')
    foreach ($l in ($script:FailureExcerpt -split "`n")) { $rep.Add($l) }
    $rep.Add('```')
}
$rep.Add("")
$rep.Add("[MONITOR] " + $verdict)
[System.IO.File]::WriteAllLines($reportPath, [string[]]$rep)

Write-Host ("[MONITOR] report: " + $reportPath)
if ($verdict -eq "PASS") {
    Write-Host "[MONITOR] PASS - monitoring baseline deployed. Grafana: http://localhost:30082"
    exit 0
} else {
    Write-Host ("[MONITOR] FAIL at step '" + $script:FailedStep + "'. Report: " + $reportPath)
    exit 1
}
