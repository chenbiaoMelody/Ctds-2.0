# C-TDS one-click deployment toolchain (WBS 2.5.2, spec: ADR-012/ADR-013).
# One command: self-check -> inject canonical image tags into a COPY of
# deploy/k8s (line-anchored, workspace untouched) -> NodePort patch (copy
# only) -> load images into the cluster node -> server-side schema
# validation -> apply -> rollout wait -> detached port-forward + smoke
# test -> business-readable report. -Teardown removes what was deployed
# and stops the port-forwards.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\deploy.ps1
#   powershell ... deploy.ps1 -Teardown
# Exit codes: 0 = all green, 1 = deploy/smoke failure, 2 = self-check failure.
param(
    [string]$RepoRoot = "",
    [int]$RolloutTimeoutSeconds = 180,
    [switch]$Teardown,
    [string]$CopyDir = ""
)
$ErrorActionPreference = "Stop"

if ($RepoRoot -eq "") {
    if ($PSScriptRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
    else { $RepoRoot = (Get-Location).Path }
}
if (-not (Test-Path (Join-Path $RepoRoot "pom.xml"))) {
    Write-Host "[DEPLOY][CONFIG-ERROR] RepoRoot not found or pom.xml missing: $RepoRoot"
    exit 2
}

# Module -> template file -> image repository mapping (hifi C2). Deployment
# object names are ctds-backend/ctds-frontend (label unification, ADR-013
# amendment 2); image names follow ADR-012 via image-tag.ps1.
$ModuleMap = @(
    @{ Module = "example-service"; File = "backend-deployment.yaml";  Image = "ctds/example-service" },
    @{ Module = "frontend";        File = "frontend-deployment.yaml"; Image = "ctds/frontend" }
)
$NodePorts = @{ backend = 30080; frontend = 30081 }

$results = New-Object System.Collections.Generic.List[object]
$script:selfOk = $true
$script:FailedStep = ""
$script:FailureExcerpt = ""

function Add-Step([string]$step, [string]$verdict, [string]$detail) {
    $script:results.Add([pscustomobject]@{ Step = $step; Verdict = $verdict; Detail = $detail })
}

# Native commands run through cmd /c with stderr merged inside cmd: under
# $ErrorActionPreference=Stop a raw `native 2>&1` turns the first stderr
# line into a terminating error (PS 5.1). Drain fully, then judge by the
# returned exit code - never by consuming a pipeline.
function Invoke-Native([string]$cmdLine) {
    $out = @()
    try { $out = @(& cmd /c $cmdLine) } catch { }
    $rc = -1
    if ($null -ne $LASTEXITCODE) { $rc = $LASTEXITCODE }
    return @($out), $rc
}

# ---------- A8 teardown (independent early path) ----------
# Returns an exit code; the caller exits (no exit inside functions, PS 5.1).
function Invoke-Teardown {
    $deployRoot = Join-Path $RepoRoot "build-output\deploy"
    if ($CopyDir -ne "") {
        $copy = $CopyDir
    } elseif (Test-Path $deployRoot) {
        $latest = Get-ChildItem $deployRoot -Directory | Sort-Object Name -Descending |
            Where-Object { Test-Path (Join-Path $_.FullName "k8s\kustomization.yaml") } |
            Select-Object -First 1
        if ($null -eq $latest) {
            Write-Host "[DEPLOY] no deployed copy found under build-output\deploy; nothing to clean"
            return 0
        }
        $copy = Join-Path $latest.FullName "k8s"
    } else {
        Write-Host "[DEPLOY] build-output\deploy does not exist; nothing to clean"
        return 0
    }
    if (-not (Test-Path (Join-Path $copy "kustomization.yaml"))) {
        Write-Host "[DEPLOY][CONFIG-ERROR] kustomization.yaml not found in copy dir: $copy"
        return 2
    }
    # Stop detached port-forwards from any previous deploy run first.
    $pfRoot = Join-Path $RepoRoot "build-output\deploy"
    if (Test-Path $pfRoot) {
        foreach ($f in (Get-ChildItem $pfRoot -Recurse -Filter "port-forward.pids" -ErrorAction SilentlyContinue)) {
            Stop-PortForwardPids (Get-Content $f.FullName -ErrorAction SilentlyContinue)
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host "[DEPLOY] tearing down resources from copy: $copy"
    $out, $code = Invoke-Native ("kubectl delete -k """ + $copy + """ --timeout=120s 2>&1")
    foreach ($l in $out) { Write-Host ("  " + $l) }
    if ($code -ne 0 -and ("$out" -notmatch "NotFound")) {
        Write-Host "[DEPLOY] FAIL: teardown failed (exit $code)"
        return 1
    }
    if ("$out" -match "NotFound") {
        Write-Host "[DEPLOY] nothing to clean (resources not found) - treated as success"
    }
    # Teardown leaves an archive trail too (review 4: A8 was the only
    # behaviour with no execution evidence).
    $teardownReport = Join-Path (Split-Path -Parent $copy) "teardown-report.md"
    $tr = New-Object System.Collections.Generic.List[string]
    $tr.Add("# C-TDS teardown report (WBS 2.5.2)")
    $tr.Add("")
    $tr.Add("- Time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
    $tr.Add("- Copy dir: " + $copy)
    $tr.Add("- kubectl delete exit code: " + $code)
    $tr.Add("- Result: **PASS**" + $(if ("$out" -match "NotFound") { " (nothing to clean: resources not found)" }))
    $tr.Add("")
    $tr.Add("[TEARDOWN] PASS")
    [System.IO.File]::WriteAllLines($teardownReport, [string[]]$tr)
    Write-Host "[DEPLOY] PASS (teardown)"
    return 0
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

if ($Teardown) { $rc = Invoke-Teardown; exit $rc }

# ---------- A1 environment self-check ----------
Write-Host "[DEPLOY] step 1/6 environment self-check..."
function Report-Check([bool]$ok, [string]$msg) {
    if (-not $ok) { $script:selfOk = $false; Write-Host ("[DEPLOY][CHECK-FAIL] " + $msg) }
    else { Write-Host ("[DEPLOY][CHECK-OK] " + $msg) }
}

$out, $code = Invoke-Native "docker version --format {{.Server.Version}} 2>&1"
Report-Check ($code -eq 0) ("docker engine reachable: " + "$($out[0])")

$out, $code = Invoke-Native "kubectl version --request-timeout=32s 2>&1"
$serverSeen = ($out | ForEach-Object { "$_" }) -match "Server Version"
Report-Check ($code -eq 0 -and [bool]$serverSeen) "kubectl available AND cluster reachable (if this fails: is the Docker Desktop built-in Kubernetes enabled? see deploy/runbook.md)"

# Canonical tags come from the 2.2.6 contract script, called in-process
# (dodges cmd /c quoting for the repo path containing spaces).
$injectTag = @{}
foreach ($m in $ModuleMap) {
    $tag = ""
    try {
        $tagOut = @(& (Join-Path $RepoRoot "scripts\pipeline\image-tag.ps1") -ModuleName $m.Module)
        if ($tagOut.Count -gt 0) { $tag = ("$($tagOut[0])").Trim() }
    } catch { }
    if ($tag -notmatch "^ctds/[\w\-]+:[\w\.\-]+$") {
        Report-Check $false ("image tag computation failed for module " + $m.Module)
        continue
    }
    $injectTag[$m.File] = $tag
    $out, $code = Invoke-Native ("docker image inspect """ + $tag + """ --format exists 2>&1")
    if ($code -ne 0) {
        # Minute-rollover fallback: image-tag.ps1 bakes the build minute into
        # the tag, so an image built a minute earlier has a different tag.
        # Adopt the newest local tag of the same repo ending in the same git
        # short hash - the image still corresponds to this exact code state.
        $core = $tag -replace "-dirty$", ""
        $gitHash = ($core -split "-")[-1]
        $out2, $c2 = Invoke-Native ("docker images """ + $m.Image + """ --format {{.Tag}} 2>&1")
        $cand = ($out2 | ForEach-Object { "$_" } |
            Where-Object { $_ -match ([regex]::Escape($gitHash) + "(-dirty)?$") -and $_ -match "^[\w\.\-]+$" } |
            Sort-Object -Descending | Select-Object -First 1)
        if ($c2 -eq 0 -and $cand) {
            $tag = $m.Image + ":" + $cand
            $injectTag[$m.File] = $tag
            Write-Host ("[DEPLOY][CHECK-FALLBACK] exact tag not found, adopting newest local tag with same git hash " + $gitHash + ": " + $tag)
            $code = 0
        }
    }
    Report-Check ($code -eq 0) ("local image exists: " + $tag + " (if missing: run nightly-build.ps1, then docker build per deploy/runbook.md)")
}

$k8sDir = Join-Path $RepoRoot "deploy\k8s"
foreach ($m in $ModuleMap) {
    $file = Join-Path $k8sDir $m.File
    $hits = 0
    if (Test-Path $file) {
        foreach ($l in (Get-Content $file)) {
            if ($l -match "^\s*image:\s*IMAGE_PLACEHOLDER\s*$") { $hits++ }
        }
    }
    Report-Check ($hits -eq 1) ("template placeholder check: " + $m.File + " has exactly 1 anchored image-placeholder line (found " + $hits + ")")
}

$out, $code = Invoke-Native "curl.exe --version 2>&1"
Report-Check ($code -eq 0) ("curl.exe available for smoke test: " + "$($out[0])")

if (-not $selfOk) {
    Write-Host "[DEPLOY] environment self-check failed - fix the items above and rerun (exit 2). Build guidance: run nightly-build.ps1 first, then docker build per deploy/runbook.md."
    exit 2
}
Add-Step "A1 environment self-check" "PASS" "docker / kubectl+cluster reachable / both local images present (exact tag or same-git-hash fallback) / placeholder exactly 1 per deployment template / curl.exe"

# ---------- A2+A3 copy, inject, NodePort patch ----------
Write-Host "[DEPLOY] step 2/6 copy templates, inject image tags, patch NodePort..."
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$copyRoot = Join-Path (Join-Path $RepoRoot "build-output\deploy") $stamp
$copyK8s = Join-Path $copyRoot "k8s"
New-Item -ItemType Directory -Path $copyK8s -Force | Out-Null
Copy-Item (Join-Path $k8sDir "*") $copyK8s -Force
Write-Host ("[DEPLOY] working copy: " + $copyK8s + " (workspace files untouched)")

# UTF-8 WITHOUT BOM (Set-Content UTF8 in PS 5.1 emits a BOM that kubectl
# rejects): write via .NET WriteAllLines.
function Write-YamlFile([string]$path, $lines) {
    [System.IO.File]::WriteAllLines($path, [string[]]($lines | ForEach-Object { "$_" }))
}

foreach ($m in $ModuleMap) {
    $file = Join-Path $copyK8s $m.File
    $lines = @(Get-Content $file)
    $tag = $injectTag[$m.File]
    $done = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        # Double anchor: the line must BE the image field AND its value must
        # be the placeholder - comments/other fields never match.
        if ($lines[$i] -match "^\s*image:\s*IMAGE_PLACEHOLDER\s*$") {
            $lines[$i] = $lines[$i] -replace "IMAGE_PLACEHOLDER", $tag
            $done++
        }
    }
    if ($done -ne 1) {
        $script:FailedStep = "inject"
        $script:FailureExcerpt = $m.File + ": expected exactly 1 anchored placeholder line, found " + $done + " (was the template edited?)"
        break
    }
    Write-YamlFile $file $lines
    Write-Host ("[DEPLOY][INJECT-OK] " + $m.File + " -> " + $tag)
}

if ($script:FailedStep -eq "") {
    foreach ($svc in @(@{ File = "backend-service.yaml";  Port = $NodePorts.backend },
                       @{ File = "frontend-service.yaml"; Port = $NodePorts.frontend })) {
        $file = Join-Path $copyK8s $svc.File
        $new = New-Object System.Collections.Generic.List[string]
        $typeHits = 0; $tpHits = 0
        foreach ($l in (Get-Content $file)) {
            if ($l -match "^\s+type:\s*ClusterIP\s*$") {
                $new.Add(($l -replace "ClusterIP", "NodePort")); $typeHits++
            } elseif ($l -match "^\s+targetPort:\s*8080\s*$") {
                $new.Add($l); $new.Add(("      nodePort: " + $svc.Port)); $tpHits++
            } else {
                $new.Add($l)
            }
        }
        if ($typeHits -ne 1 -or $tpHits -ne 1) {
            $script:FailedStep = "nodeport-patch"
            $script:FailureExcerpt = $svc.File + ": expected 1 ClusterIP line and 1 targetPort:8080 line, found " + $typeHits + "/" + $tpHits
            break
        }
        Write-YamlFile $file $new
        Write-Host ("[DEPLOY][PATCH-OK] " + $svc.File + " -> NodePort " + $svc.Port)
    }
    if ($script:FailedStep -eq "") {
        Add-Step "A2 inject (line-anchored, copy only)" "PASS" ("per-file exactly 1 replacement; tags: " + (($ModuleMap | ForEach-Object { $_.File + "=" + $injectTag[$_.File] }) -join ", "))
        Add-Step "A3 NodePort patch (copy only)" "PASS" ("backend=30080, frontend=30081; base templates remain ClusterIP")
    }
}

# ---------- A4-pre load images into the cluster node ----------
# The Docker Desktop built-in cluster (kind mode) has its own containerd and
# does NOT see the docker daemon's local images - without this step every pod
# lands in ImagePullBackOff (discovered in the 2.5.2 drill).
if ($script:FailedStep -eq "") {
    Write-Host "[DEPLOY] step 3/6 loading images into the cluster node..."
    $nodeOut, $nc = Invoke-Native "kubectl get nodes --no-headers -o custom-columns=:metadata.name 2>&1"
    $node = ""
    if ($nc -eq 0 -and $nodeOut.Count -gt 0) { $node = ("$($nodeOut[0])").Trim() }
    if ($node -eq "") {
        $script:FailedStep = "image-load"
        $script:FailureExcerpt = "could not resolve a cluster node name (kubectl get nodes)"
    } else {
        foreach ($m in $ModuleMap) {
            $tag = $injectTag[$m.File]
            $out, $code = Invoke-Native ("docker save """ + $tag + """ | docker exec -i " + $node + " ctr --namespace k8s.io images import - 2>&1")
            if ($code -ne 0) {
                $script:FailedStep = "image-load"
                $script:FailureExcerpt = "docker save/import failed for " + $tag + ":`n" + (($out | Select-Object -Last 5) -join "`n")
                break
            }
            $lsOut, $lc = Invoke-Native ("docker exec " + $node + " ctr --namespace k8s.io images ls 2>&1")
            $found = ($lsOut | ForEach-Object { "$_" }) -match [regex]::Escape($tag)
            if ($lc -ne 0 -or -not [bool]$found) {
                $script:FailedStep = "image-load"
                $script:FailureExcerpt = "image " + $tag + " not present in node containerd after import"
                break
            }
            Write-Host ("  loaded into node: " + $tag)
        }
        if ($script:FailedStep -eq "") {
            Add-Step "A4-pre image load (docker save -> node ctr import)" "PASS" ("node: " + $node + ", both module images loaded and verified")
        }
    }
}

# ---------- A4 server-side schema validation ----------
if ($script:FailedStep -eq "") {
    Write-Host "[DEPLOY] step 4/6 server-side schema validation (kubectl apply --dry-run=server)..."
    $out, $code = Invoke-Native ("kubectl apply --dry-run=server -k """ + $copyK8s + """ 2>&1")
    if ($code -ne 0) {
        $script:FailedStep = "schema-validate"
        $script:FailureExcerpt = ($out | Select-Object -Last 10) -join "`n"
    } else {
        foreach ($l in $out) { Write-Host ("  " + $l) }
        Add-Step "A4 server-side schema validation" "PASS" "OpenAPI-schema-level check against the live API (fulfils the 2.5.1 K5 deferred item)"
    }
}

# ---------- A5 apply + rollout ----------
if ($script:FailedStep -eq "") {
    Write-Host "[DEPLOY] step 5/6 applying to cluster and waiting for rollout..."
    $out, $code = Invoke-Native ("kubectl apply -k """ + $copyK8s + """ 2>&1")
    if ($code -ne 0) {
        $script:FailedStep = "apply"
        $script:FailureExcerpt = ($out | Select-Object -Last 10) -join "`n"
    } else {
        foreach ($l in $out) { Write-Host ("  " + $l) }
        Add-Step "A5a kubectl apply -k" "PASS" ("copy dir: " + $copyK8s)
    }
}
if ($script:FailedStep -eq "") {
    foreach ($dep in @("ctds-backend", "ctds-frontend")) {
        $out, $code = Invoke-Native ("kubectl rollout status deployment/" + $dep + " --timeout=" + $RolloutTimeoutSeconds + "s 2>&1")
        if ($code -ne 0) {
            $script:FailedStep = "rollout"
            $podOut, $null = Invoke-Native "kubectl get pods -o wide 2>&1"
            $script:FailureExcerpt = "rollout of " + $dep + " failed or timed out.`n" + (($podOut | Select-Object -Last 15) -join "`n")
            break
        }
        Write-Host ("  rollout ok: " + $dep)
    }
    if ($script:FailedStep -eq "") {
        Add-Step "A5b rollout status x2" "PASS" ("both Deployments ready within " + $RolloutTimeoutSeconds + "s")
    }
}

# ---------- A6 detached port-forward + smoke test ----------
$smokeResults = New-Object System.Collections.Generic.List[object]
$pfPidFile = Join-Path $copyK8s "port-forward.pids"
if ($script:FailedStep -eq "") {
    Write-Host "[DEPLOY] step 6/6 port-forward + smoke test..."
    # kind-mode clusters do NOT map NodePorts to localhost (measured in the
    # 2.5.2 drill): serve the dev/test entries with DETACHED kubectl
    # port-forward processes that survive this script (stopped by -Teardown).
    $kubectl = (Get-Command kubectl -ErrorAction SilentlyContinue).Source
    if ([string]::IsNullOrEmpty($kubectl)) {
        $script:FailedStep = "port-forward"
        $script:FailureExcerpt = "kubectl not found on PATH"
    } else {
        # Reap detached forwards from previous runs so reruns stay clean.
        $pfRoot = Join-Path $RepoRoot "build-output\deploy"
        if (Test-Path $pfRoot) {
            foreach ($f in (Get-ChildItem $pfRoot -Recurse -Filter "port-forward.pids" -ErrorAction SilentlyContinue)) {
                if ($f.FullName -ne $pfPidFile) {
                    Stop-PortForwardPids (Get-Content $f.FullName -ErrorAction SilentlyContinue)
                }
            }
        }
        $pf = New-Object System.Collections.Generic.List[string]
        foreach ($fw in @(@{ Svc = "svc/ctds-frontend"; Port = "30081:80" },
                           @{ Svc = "svc/ctds-backend";  Port = "30080:8080" })) {
            $proc = Start-Process -FilePath $kubectl -ArgumentList @("port-forward", $fw.Svc, $fw.Port) -WindowStyle Hidden -PassThru
            $pf.Add([string]$proc.Id)
            Write-Host ("  port-forward " + $fw.Port + " (pid " + $proc.Id + ")")
        }
        [System.IO.File]::WriteAllLines($pfPidFile, [string[]]$pf)
        Start-Sleep -Seconds 4

        $smokeDefs = @(
            @{ Name = "frontend page http://localhost:30081/";               Url = "http://localhost:30081/";      Expect = "200" },
            @{ Name = "frontend SPA deep link http://localhost:30081/login"; Url = "http://localhost:30081/login"; Expect = "200" },
            @{ Name = "backend responds http://localhost:30080/";            Url = "http://localhost:30080/";      Expect = "ANY" }
        )
        foreach ($s in $smokeDefs) {
            $out, $code = Invoke-Native ("curl.exe -s -o NUL -w %{http_code} --max-time 10 " + $s.Url)
            $httpCode = ""
            if ($out.Count -gt 0) { $httpCode = ("$($out[0])").Trim() }
            $ok = ($code -eq 0) -and ($httpCode -match "^\d{3}$") -and ($s.Expect -eq "ANY" -or $httpCode -eq $s.Expect)
            $smokeResults.Add([pscustomobject]@{ Check = $s.Name; Expected = $s.Expect; Got = $httpCode; Verdict = $(if ($ok) { "PASS" } else { "FAIL" }) })
            if (-not $ok) {
                $script:FailedStep = "smoke"
                $script:FailureExcerpt = $s.Name + ": expected " + $s.Expect + ", got HTTP " + $httpCode + " (curl exit " + $code + "). If unreachable, check whether localhost ports 30080/30081 are occupied by other software."
                break
            }
        }
        if ($script:FailedStep -eq "") {
            $summary = ($smokeResults | ForEach-Object { $_.Check + " => " + $_.Got }) -join "; "
            Add-Step "A6 smoke test (port-forward)" "PASS" ($summary + "; forwards detached (pids: " + ($pf -join ",") + "), survive this script, stopped by -Teardown")
        } else {
            foreach ($p in $pf) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
        }
    }
}

# ---------- A7 report ----------
$reportPath = Join-Path $copyRoot "deploy-report.md"
$verdict = "FAIL"
if ($script:FailedStep -eq "") { $verdict = "PASS" }

$rep = New-Object System.Collections.Generic.List[string]
$rep.Add("# C-TDS one-click deployment report (WBS 2.5.2)")
$rep.Add("")
$rep.Add("- Time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
$rep.Add("- Working copy: " + $copyK8s)
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
$rep.Add("## Smoke checks")
$rep.Add("")
if ($smokeResults.Count -gt 0) {
    $rep.Add("| Check | Expected | Got | Verdict |")
    $rep.Add("| --- | --- | --- | --- |")
    foreach ($s in $smokeResults) { $rep.Add("| " + $s.Check + " | " + $s.Expected + " | " + $s.Got + " | " + $s.Verdict + " |") }
} else {
    $rep.Add("(not reached)")
}
$rep.Add("")
$rep.Add("## Access entries (dev/test)")
$rep.Add("")
$rep.Add("- Frontend: http://localhost:30081 (SPA deep links such as /login stay on 200)")
$rep.Add("- Backend: http://localhost:30080 (404 on / is normal: the service is answering)")
$rep.Add("- Served by detached kubectl port-forward (kind-mode clusters do not map NodePorts to localhost, measured): these keep running after this script exits.")
$rep.Add("- Teardown (removes resources AND stops the port-forwards): powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\deploy.ps1 -Teardown")
if ($script:FailureExcerpt -ne "") {
    $rep.Add("")
    $rep.Add("## Failure excerpt (" + $script:FailedStep + ")")
    $rep.Add("")
    $rep.Add('```')
    foreach ($l in ($script:FailureExcerpt -split "`n")) { $rep.Add($l) }
    $rep.Add('```')
}
$rep.Add("")
$rep.Add("[DEPLOY] " + $verdict)
[System.IO.File]::WriteAllLines($reportPath, [string[]]$rep)

Write-Host ("[DEPLOY] report: " + $reportPath)
if ($verdict -eq "PASS") {
    Write-Host "[DEPLOY] PASS - deployed. Frontend: http://localhost:30081 Backend: http://localhost:30080"
    exit 0
} else {
    Write-Host ("[DEPLOY] FAIL at step '" + $script:FailedStep + "'. Report: " + $reportPath)
    exit 1
}
