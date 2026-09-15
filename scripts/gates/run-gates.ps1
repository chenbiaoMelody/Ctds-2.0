# C-TDS quality gate runner (WBS 2.2.2/2.2.4).
# Design: CI-platform-agnostic (D-3 "switch interface reserved") - any future CI
# system only needs to execute this script and check the exit code.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\run-gates.ps1
# Options: -RepoRoot <path> (default: repo root inferred from script location)
#          -ReportPath <file> (default: scripts\gates\reports\gate-report-<timestamp>.md)
# Exit codes (WBS-2.2.7): 0 = GREEN (no FAIL; PENDING/SKIP stages allowed),
#                          1 = RED (any FAIL -> code not acceptable),
#                          2 = config/environment error (bad config, or the runner itself crashed - NOT a code verdict).
# WBS-2.2.7 change record: 单文件读取失败（被占用/权限）不再中断门禁，改记 SKIP 并在报告中逐条列出；
# 全阶段异常兜底保证"报告必出"；脚本自身崩溃退出码独立为 2，与"代码不合格的红灯=1"区分。
param(
    [string]$RepoRoot = "",
    [string]$ReportPath = ""
)
$ErrorActionPreference = "Stop"
$script:runnerCrashed = $false

if ($RepoRoot -eq "") {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
if (-not (Test-Path $RepoRoot)) {
    Write-Output "[CONFIG-ERROR] RepoRoot not found: $RepoRoot"
    exit 2
}
$cfgPath = Join-Path $PSScriptRoot "gates-config.json"
if (-not (Test-Path $cfgPath)) {
    Write-Output "[CONFIG-ERROR] gates-config.json not found at $cfgPath"
    exit 2
}
try {
    $cfg = Get-Content -Path $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Write-Output ("[CONFIG-ERROR] gates-config.json parse failed: " + $_.Exception.Message)
    exit 2
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$stage, [string]$verdict, [string]$detail) {
    $script:results.Add([pscustomobject]@{ Stage = $stage; Verdict = $verdict; Detail = $detail })
}

# ---------- WBS-2.2.7: 全阶段异常兜底 ----------
# 以下所有阶段包在一个 try 内：任何未预期异常都不允许让门禁"无报告退出"（原缺陷：脚本崩溃不留报告、
# 退出码与"代码不合格的红灯"同形）。为保持变更最小可读，块内不额外缩进，改由本注释与收尾 catch 标注边界。
try {

# ---------- Stage: secretsScan ----------
if ($cfg.stages.secretsScan.enabled) {
    $exclude = @($cfg.secretsExcludePaths)
    $patterns = @($cfg.secretsPatterns)
    $hit = 0
    $unreadable = New-Object System.Collections.Generic.List[string]
    $all = @(Get-ChildItem -Path $RepoRoot -Recurse -File -ErrorAction SilentlyContinue)
    $files = @($all | Where-Object {
        $rel = $_.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
        $bad = $false
        foreach ($e in $exclude) {
            if ($e.StartsWith('*')) { if ($rel -like $e) { $bad = $true; break } }
            elseif ($rel -eq $e -or $rel -like ($e + '/*') -or $rel -like ('*/' + $e) -or $rel -like ('*/' + $e + '/*')) { $bad = $true; break }
        }
        (-not $bad) -and $_.Length -lt 1MB
    })
    $skipped = $all.Count - $files.Count
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
        # WBS-2.2.7: 单文件不可读（被占用/权限）不得中断整个门禁 —— 记 SKIP 明细并继续扫描其余文件
        try {
            $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
        } catch {
            $unreadable.Add($rel)
            continue
        }
        if ($bytes.Length -eq 0) { continue }
        # crude binary skip: NUL byte in first 1024
        $head = $bytes[0..([Math]::Min(1023, $bytes.Length - 1))]
        if ($head -contains 0) { continue }
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        foreach ($p in $patterns) {
            if ([System.Text.RegularExpressions.Regex]::IsMatch($text, $p, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
                $hit++
                Add-Result "secretsScan" "FAIL" ("Pattern hit in " + $rel + " (content withheld)")
                break
            }
        }
    }
    if ($hit -eq 0) { Add-Result "secretsScan" "PASS" ("Scanned " + $files.Count + " files, 0 hits (skipped oversized/binary/excluded: " + $skipped + ")") }
    if ($unreadable.Count -gt 0) {
        # SKIP 不是放行：逐条列出被跳过的文件，便于人工复核（若为源码/配置/文档文件应视为异常并排查占用来源）
        Add-Result "secretsScan.skipped" "SKIP" ($unreadable.Count.ToString() + " file(s) unreadable (locked/permission), NOT scanned: " + ($unreadable -join ", "))
    }
}

# ---------- Stage: structureCheck ----------
if ($cfg.stages.structureCheck.enabled) {
    $required = @("AGENTS.md", "README.md", ".gitignore")
    $missing = @()
    foreach ($r in $required) { if (-not (Test-Path (Join-Path $RepoRoot $r))) { $missing += $r } }
    if (-not (Get-ChildItem (Join-Path $RepoRoot "docs") -Filter "03-*.md" -ErrorAction SilentlyContinue)) { $missing += "docs/03-* (charter)" }
    if (-not (Get-ChildItem (Join-Path $RepoRoot "docs\adr") -Filter "ADR-*.md" -ErrorAction SilentlyContinue)) { $missing += "docs/adr/ADR-*" }
    if (-not (Get-ChildItem (Join-Path $RepoRoot "docs\logs") -Filter "*.md" -ErrorAction SilentlyContinue)) { $missing += "docs/logs/*.md" }
    if ($missing.Count -eq 0) { Add-Result "structureCheck" "PASS" "All required repo entries present" }
    else { Add-Result "structureCheck" "FAIL" ("Missing: " + ($missing -join ", ")) }
}

# ---------- Stage: devLogNamingCheck ----------
if ($cfg.stages.devLogNamingCheck.enabled) {
    $logs = Get-ChildItem (Join-Path $RepoRoot "docs\logs") -Filter "*.md" -ErrorAction SilentlyContinue
    $badNames = @()
    foreach ($l in $logs) {
        if ($l.Name -notmatch '^Ctds-.+-\d{2}-\d{2}-\d{2}-\d{4}\.md$') { $badNames += $l.Name }
    }
    if ($badNames.Count -eq 0) { Add-Result "devLogNamingCheck" "PASS" ($logs.Count.ToString() + " log files, all match naming rule") }
    else { Add-Result "devLogNamingCheck" "FAIL" ("Non-conforming names: " + ($badNames -join ", ")) }
}

# ---------- Stage: adrFieldsCheck ----------
if ($cfg.stages.adrFieldsCheck.enabled) {
    $reqFields = @($cfg.stages.adrFieldsCheck.requiredFields)
    $adrs = Get-ChildItem (Join-Path $RepoRoot "docs\adr") -Filter "ADR-*.md" -ErrorAction SilentlyContinue
    $bad = @()
    foreach ($a in $adrs) {
        $content = Get-Content -Path $a.FullName -Raw -Encoding UTF8
        foreach ($fld in $reqFields) {
            if (-not $content.Contains($fld)) { $bad += ($a.Name + " missing field: " + $fld) }
        }
    }
    if ($bad.Count -eq 0) { Add-Result "adrFieldsCheck" "PASS" ($adrs.Count.ToString() + " ADR files, all required fields present") }
    else { Add-Result "adrFieldsCheck" "FAIL" ($bad -join "; ") }
}

# ---------- Toolchain stages (compile / lint / unitTest via Maven) ----------
$toolJavaHome = $env:JAVA_HOME
if (-not $toolJavaHome -and $cfg.toolchain -and $cfg.toolchain.javaHome) { $toolJavaHome = $cfg.toolchain.javaHome }
$toolMavenBin = "mvn"
if ($cfg.toolchain -and $cfg.toolchain.mavenBin) { $toolMavenBin = $cfg.toolchain.mavenBin }
$toolMavenArgs = ""
if ($cfg.toolchain -and $cfg.toolchain.mavenArgs) { $toolMavenArgs = $cfg.toolchain.mavenArgs }

foreach ($stageName in @("compile", "lint", "unitTest")) {
    $s = $cfg.stages.$stageName
    if (-not $s -or -not $s.enabled) { continue }
    # defensive allowlist: config values are joined into a cmd.exe command line
    # WBS-2.2.7: 配置/环境类问题记 ERROR（退出码 2），不得与"代码不合格的 FAIL（退出码 1）"混淆
    foreach ($v in @($toolMavenBin, $toolMavenArgs, $s.goals)) {
        if ($v -and ($v -notmatch '^[A-Za-z0-9:._\-\s]+$')) {
            Add-Result $stageName "ERROR" "Illegal characters in gates-config toolchain/stage value (allowed: A-Za-z0-9 : . _ - space)"
            continue
        }
    }
    if (-not $toolJavaHome -or -not (Test-Path $toolJavaHome)) {
        Add-Result $stageName "ERROR" "JAVA_HOME not found (set env JAVA_HOME or gates-config toolchain.javaHome)"
        continue
    }
    $outLog = Join-Path $env:TEMP ("ctds-gate-" + $stageName + "-out.log")
    $oldJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $toolJavaHome
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c " + $toolMavenBin + " " + $toolMavenArgs + " " + $s.goals
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $RepoRoot
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit(600000)) {
        try { $proc.Kill() } catch { }
        Add-Result $stageName "FAIL" ("mvn " + $s.goals + " timed out after 600s")
    } elseif ($proc.ExitCode -eq 0) {
        Add-Result $stageName "PASS" ("mvn " + $s.goals + " exit 0")
    } else {
        $null = $outTask.Wait(10000)
        $null = $errTask.Wait(10000)
        $allOut = ($outTask.Result + [Environment]::NewLine + $errTask.Result) -split "`r?`n" | Where-Object { $_.Trim() -ne "" }
        $tail = ($allOut | Select-Object -Last 6) -join " | "
        [System.IO.File]::WriteAllText($outLog, ($outTask.Result + $errTask.Result))
        Add-Result $stageName "FAIL" ("mvn " + $s.goals + " exit " + $proc.ExitCode + ": " + $tail)
    }
    if ($oldJavaHome) { $env:JAVA_HOME = $oldJavaHome } else { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
}

# ---------- Frontend stages (npm; WBS 2.4.12 G1-G3, change-trace: docs/designs/WBS-2.4.12-{lofi,hifi}.md + ADR-011) ----------
foreach ($stageName in @("frontendLint", "frontendTest", "frontendE2E")) {
    $s = $cfg.stages.$stageName
    if (-not $s -or -not $s.enabled) { continue }
    # defensive allowlist: config values are joined into a cmd.exe command line (same rule as Maven stages);
    # 评审②P2-1：违规必须跳过整个阶段的执行（不得检出后仍启动命令）
    $illegal = $false
    foreach ($v in @($s.goals)) {
        if ($v -and ($v -notmatch '^[A-Za-z0-9:._\-\s]+$')) { $illegal = $true }
    }
    if ($illegal) {
        Add-Result $stageName "ERROR" "Illegal characters in gates-config stage value (allowed: A-Za-z0-9 : . _ - space)"
        continue
    }
    # workdir 读取配置值（缺省回退 frontend；评审①P3-3/③P3-2：字段不得为死配置）
    $stageWorkdir = "frontend"
    if ($s.workdir) { $stageWorkdir = $s.workdir }
    $workdir = Join-Path $RepoRoot $stageWorkdir
    if (-not (Test-Path (Join-Path $workdir "package.json"))) {
        Add-Result $stageName "ERROR" ("package.json not found under workdir: " + $stageWorkdir)
        continue
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c npm " + $s.goals
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $workdir
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit(300000)) {
        try { $proc.Kill() } catch { }
        Add-Result $stageName "FAIL" ("npm " + $s.goals + " timed out after 300s")
    } elseif ($proc.ExitCode -eq 0) {
        Add-Result $stageName "PASS" ("npm " + $s.goals + " exit 0")
    } else {
        $null = $outTask.Wait(10000)
        $null = $errTask.Wait(10000)
        $allOut = ($outTask.Result + [Environment]::NewLine + $errTask.Result) -split "`r?`n" | Where-Object { $_.Trim() -ne "" }
        $tail = ($allOut | Select-Object -Last 6) -join " | "
        Add-Result $stageName "FAIL" ("npm " + $s.goals + " exit " + $proc.ExitCode + ": " + $tail)
    }
}

# ---------- Pending stages (toolchain blocked by ADR-001 approval) ----------
foreach ($prop in $cfg.stages.PSObject.Properties) {
    $s = $prop.Value
    if ($s.enabled -eq $false -and $s.status -like "PENDING*") {
        Add-Result $prop.Name "PENDING" ($s.status.ToString() + " tool=" + $s.tool)
    }
}

} catch {
    # WBS-2.2.7: 脚本自身异常 -> 记 FAIL 明细 + runnerCrashed 标记（退出码 2，与"代码不合格=1"区分）
    $script:runnerCrashed = $true
    Add-Result "runner" "FAIL" ("runner aborted by unexpected error (exit code will be 2, NOT a code verdict): " + $_.Exception.Message + " | " + $_.ScriptStackTrace)
}

# ---------- Report ----------
$green = @($results | Where-Object { $_.Verdict -eq "PASS" }).Count
$red = @($results | Where-Object { $_.Verdict -eq "FAIL" }).Count
$skip = @($results | Where-Object { $_.Verdict -eq "SKIP" }).Count
$envErr = @($results | Where-Object { $_.Verdict -eq "ERROR" }).Count
$pend = @($results | Where-Object { $_.Verdict -eq "PENDING" }).Count
$verdict = "GREEN"
if ($script:runnerCrashed) { $verdict = "ERROR (runner crashed - see runner row; exit code 2)" }
elseif ($envErr -gt 0) { $verdict = "ERROR (environment/config - NOT a code verdict; exit code 2; fix and re-run)" }
elseif ($red -gt 0) { $verdict = "RED" }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# C-TDS Gate Report")
$lines.Add("")
$lines.Add("- Time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
$lines.Add("- Repo: $RepoRoot")
$lines.Add("- Result: PASS=$green FAIL=$red SKIP=$skip ERROR=$envErr PENDING=$pend -> $verdict")
$lines.Add("")
$lines.Add("| Stage | Verdict | Detail |")
$lines.Add("| --- | --- | --- |")
foreach ($r in $results) {
    $d = $r.Detail -replace '\|', '/'
    $lines.Add("| " + $r.Stage + " | " + $r.Verdict + " | " + $d + " |")
}
$report = $lines -join [Environment]::NewLine

if ($ReportPath -eq "") {
    $dir = Join-Path $PSScriptRoot "reports"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $ReportPath = Join-Path $dir ("gate-report-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".md")
}
$reportWritten = $true
try {
    [System.IO.File]::WriteAllText($ReportPath, $report, (New-Object System.Text.UTF8Encoding($true)))
} catch {
    $reportWritten = $false
    Write-Output ("[WARN] report could not be written to " + $ReportPath + ": " + $_.Exception.Message)
}
Write-Output $report
Write-Output ""
if ($reportWritten) { Write-Output "Report saved to: $ReportPath" }
# WBS-2.2.7 exit code semantics: 2 = config/environment/runner error (NOT a code verdict), 1 = FAIL, 0 = GREEN
if ($script:runnerCrashed -or $envErr -gt 0 -or (-not $reportWritten)) { exit 2 }
elseif ($red -gt 0) { exit 1 } else { exit 0 }
