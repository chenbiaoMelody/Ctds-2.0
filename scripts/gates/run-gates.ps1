# C-TDS quality gate runner (WBS 2.2.2/2.2.4).
# Design: CI-platform-agnostic (D-3 "switch interface reserved") - any future CI
# system only needs to execute this script and check the exit code.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\run-gates.ps1
# Options: -RepoRoot <path>   (default: repo root inferred from script location)
#          -ReportPath <file> (default: scripts\gates\reports\gate-report-<timestamp>.md)
#          -ConfigPath <file> (default: scripts\gates\gates-config.json; ONLY for the runner selftest
#                              and change verification - a normal run must NOT point elsewhere. The
#                              report records the resolved path and its SHA256 so any run is auditable.)
#          -Scenario <label>  (default: "default"; the selftest labels its runs, shown in the report)
# Exit codes (WBS-2.2.7): 0 = GREEN (no FAIL; PENDING/SKIP stages allowed),
#                          1 = RED (any FAIL -> code not acceptable; RED takes priority over ERROR),
#                          2 = config/environment error (bad config, unwritable report, or the runner
#                              itself crashed - NOT a code verdict).
# WBS-2.2.7 round-1 (config V1.1) record: 单文件读取失败（被占用/权限）不再中断门禁；全阶段异常兜底
# 保证"报告必出"；脚本自身崩溃的退出码独立为 2，与"代码不合格的红灯=1"区分。
# WBS-2.2.7 round-2 (config V1.2, R1-R9) record:
#   R1 排除项根锚定（'logs' 不再误伤 docs/logs/）+ 机器护栏"排除项命中 git 追踪路径即 ERROR"
#   R2 RED 优先于 ERROR（真红灯绝不能被环境/配置错误掩盖）
#   R3 入库文件不可读记 ERROR；仅未入库的构建/运行产物可 SKIP
#   R4 报告目标（目录创建 + 可写探针）纳入保护区，结论纳入"报告写入失败"
#   R5 Maven 段配置违规整段跳过（不再"检出后仍执行命令"）
#   R6 报告头增加 RunLabel/Scenario/ExitCode/ConfigPath/ConfigSha256；mvn/npm 前置探测
param(
    [string]$RepoRoot = "",
    [string]$ReportPath = "",
    [string]$ConfigPath = "",
    [string]$Scenario = "default"
)
$ErrorActionPreference = "Stop"
$script:runnerCrashed = $false
$runLabel = (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + (Get-Random -Minimum 1000 -Maximum 9999)

if ($RepoRoot -eq "") {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
if (-not (Test-Path $RepoRoot)) {
    Write-Output "[CONFIG-ERROR] RepoRoot not found: $RepoRoot"
    exit 2
}
$cfgPath = $ConfigPath
if ($cfgPath -eq "") { $cfgPath = Join-Path $PSScriptRoot "gates-config.json" }
if (-not (Test-Path $cfgPath)) {
    Write-Output "[CONFIG-ERROR] gates-config.json not found at $cfgPath"
    exit 2
}
$configSha256 = ""
try {
    # 读一次字节：解析与哈希用同一份内容，报告中记录的 ConfigSha256 即被执行的配置本身
    $cfgBytes = [System.IO.File]::ReadAllBytes($cfgPath)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $configSha256 = ([System.BitConverter]::ToString($sha.ComputeHash($cfgBytes))).Replace("-", "").ToLowerInvariant()
    $sha.Dispose()
    $cfgText = [System.Text.Encoding]::UTF8.GetString($cfgBytes)
    if ($cfgText.Length -gt 0 -and $cfgText[0] -eq [char]0xFEFF) { $cfgText = $cfgText.Substring(1) }
    $cfg = ConvertFrom-Json -InputObject $cfgText
} catch {
    Write-Output ("[CONFIG-ERROR] gates-config.json parse failed: " + $_.Exception.Message)
    exit 2
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$stage, [string]$verdict, [string]$detail) {
    $script:results.Add([pscustomobject]@{ Stage = $stage; Verdict = $verdict; Detail = $detail })
}

# ---------- Git-tracked index (WBS-2.2.7 V1.2 / R1+R3) ----------
# 用途：① 排除项护栏——排除项绝不允许命中入库路径；② 不可读文件判定——入库文件不可读记 ERROR，
# 未入库的构建/运行产物才允许 SKIP。索引取不到属环境问题，护栏记 ERROR（不得静默放行）。
$tracked = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$script:gitIndexOk = $false
$script:gitIndexDetail = ""
function Initialize-GitIndex([string]$root) {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        $script:gitIndexDetail = "git executable not found on PATH"
        return
    }
    try {
        $lsOut = @(& git -C $root ls-files 2>$null)
        if ($LASTEXITCODE -ne 0) {
            $script:gitIndexDetail = "git ls-files exited with code " + $LASTEXITCODE
            return
        }
        foreach ($p in $lsOut) {
            $q = ([string]$p).Trim()
            if ($q -ne "") { $null = $tracked.Add($q) }
        }
        $script:gitIndexOk = $true
    } catch {
        $script:gitIndexDetail = $_.Exception.Message
    }
}

# 排除项匹配规则（配置 V1.2，详见 gates-config.json 的 secretsExcludePathsRule）：
#   - 不以 '*' 开头 = 仓库根锚定：只匹配"根下该路径本身及其子项"（'logs' 不再命中 docs/logs/）
#   - 以 '*' 开头 = 通配：对仓库相对路径做 -like（'*' 可跨 '/'）；以 '**/' 开头时再按去掉前缀的
#     形式匹配一次（PowerShell 的 '**/' 需要真有前缀，故根下同名目录需这次额外匹配）
function Test-Excluded([string]$rel, [string[]]$exclude) {
    foreach ($e in $exclude) {
        if ($e.StartsWith('*')) {
            if ($rel -like $e) { return $true }
            if ($e.StartsWith('**/') -and ($rel -like $e.Substring(3))) { return $true }
        } elseif ($rel -eq $e -or $rel.StartsWith($e + '/', [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

# ---------- WBS-2.2.7: 全阶段异常兜底 ----------
# 以下所有阶段包在一个 try 内：任何未预期异常都不允许让门禁"无报告退出"（原缺陷：脚本崩溃不留报告、
# 退出码与"代码不合格的红灯"同形）。为保持变更最小可读，块内不额外缩进，改由本注释与收尾 catch 标注边界。
try {

# ---------- Stage: secretsScan ----------
if ($cfg.stages.secretsScan.enabled) {
    $exclude = @($cfg.secretsExcludePaths)
    $patterns = @($cfg.secretsPatterns)
    Initialize-GitIndex $RepoRoot
    # R1 机器护栏：把"禁止借排除项规避源码/文档扫描"从注释变成强制——排除项命中入库路径即 ERROR。
    if (-not $script:gitIndexOk) {
        Add-Result "secretsScan.excludeGuard" "ERROR" ("exclude-path guard could NOT run: " + $script:gitIndexDetail + " (the git-tracked index is required to prove that no exclude entry hides committed files)")
    } else {
        $guardHits = @()
        foreach ($e in $exclude) {
            $m = @($tracked | Where-Object { Test-Excluded $_ $e } | Sort-Object)
            if ($m.Count -gt 0) {
                $sample = ($m | Select-Object -First 5) -join ", "
                if ($m.Count -gt 5) { $sample = $sample + " (+" + ($m.Count - 5) + " more)" }
                $guardHits += ("[" + $e + "] -> " + $sample)
            }
        }
        if ($guardHits.Count -gt 0) {
            Add-Result "secretsScan.excludeGuard" "ERROR" ("secretsExcludePaths hit git-TRACKED paths - an exclude entry must NEVER hide committed files (AGENTS section 8 item 3 - gate config changes go through the change process); make the entry root-anchored or a build-artifact glob: " + ($guardHits -join " ; "))
        } else {
            Add-Result "secretsScan.excludeGuard" "PASS" ($exclude.Count.ToString() + " exclude entries, 0 hits against " + $tracked.Count + " git-tracked path(s)")
        }
    }
    $hit = 0
    $unreadable = New-Object System.Collections.Generic.List[string]
    $all = @(Get-ChildItem -Path $RepoRoot -Recurse -File -ErrorAction SilentlyContinue)
    $files = @($all | Where-Object {
        $rel = $_.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
        (-not (Test-Excluded $rel $exclude)) -and $_.Length -lt 1MB
    })
    $skipped = $all.Count - $files.Count
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
        # WBS-2.2.7: 单文件不可读（被占用/权限）不得中断整个门禁；
        # R3: 但"不能扫"与"扫过且干净"必须区分——入库文件不可读 = 扫描缺口 -> ERROR（退出码 2）。
        try {
            $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
        } catch {
            if ($tracked.Contains($rel)) {
                Add-Result "secretsScan.unreadable" "ERROR" ("git-TRACKED file could NOT be scanned (locked/permission) - a committed file must never silently escape the scan: " + $rel + " (" + $_.Exception.Message + ")")
            } else {
                $unreadable.Add($rel)
            }
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
    if ($hit -eq 0) { Add-Result "secretsScan" "PASS" ("Scanned " + $files.Count + " files, 0 hits (not scanned: " + $skipped + " excluded-or-oversized)") }
    if ($unreadable.Count -gt 0) {
        # SKIP 不是放行：仅未入库的构建/运行产物可 SKIP，且逐条列出以便人工复核
        Add-Result "secretsScan.skipped" "SKIP" ($unreadable.Count.ToString() + " untracked file(s) unreadable (locked/permission), NOT scanned - listed for manual review: " + ($unreadable -join ", "))
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
    # R5: 违规必须跳过整个阶段（不得"检出后仍启动命令"，与前端段同构）
    $illegal = $false
    $illegalField = ""
    if ($toolMavenBin -and ($toolMavenBin -notmatch '^[A-Za-z0-9:._\-\s]+$')) { $illegal = $true; $illegalField = "toolchain.mavenBin" }
    if ($toolMavenArgs -and ($toolMavenArgs -notmatch '^[A-Za-z0-9:._\-\s]+$')) { $illegal = $true; $illegalField = "toolchain.mavenArgs" }
    if ($s.goals -and ($s.goals -notmatch '^[A-Za-z0-9:._\-\s]+$')) { $illegal = $true; $illegalField = "stages.$stageName.goals" }
    if ($illegal) {
        Add-Result $stageName "ERROR" ("Illegal characters in gates-config value (" + $illegalField + "; allowed: A-Za-z0-9 : . _ - space) - stage SKIPPED, command NOT executed")
        continue
    }
    # WBS-2.2.7: 配置/环境类问题记 ERROR（退出码 2），不得与"代码不合格的 FAIL（退出码 1）"混淆
    if (-not $toolJavaHome -or -not (Test-Path $toolJavaHome)) {
        Add-Result $stageName "ERROR" "JAVA_HOME not found (set env JAVA_HOME or gates-config toolchain.javaHome)"
        continue
    }
    # R6: 工具前置探测——缺工具是环境问题（ERROR/2），不是代码结论
    if (-not (Get-Command $toolMavenBin -ErrorAction SilentlyContinue)) {
        Add-Result $stageName "ERROR" ("Maven executable not found on PATH: " + $toolMavenBin + " (install Maven 3.9+ or set gates-config toolchain.mavenBin)")
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
        Add-Result $stageName "ERROR" "Illegal characters in gates-config stage value (allowed: A-Za-z0-9 : . _ - space) - stage SKIPPED, command NOT executed"
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
    # R6: 工具前置探测——缺 npm 是环境问题（ERROR/2），不是代码结论
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Add-Result $stageName "ERROR" "npm executable not found on PATH (Node.js/npm required for frontend stages)"
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

# ---------- Report target (R4: 目录创建与可写探针纳入保护区） ----------
# 目标不可写必须在"结论"里体现（ERROR 行），因此探针要早于结论计算；此处失败不得中断门禁。
if ($ReportPath -eq "") {
    $reportDir = Join-Path $PSScriptRoot "reports"
    try {
        if (-not (Test-Path $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
        $ReportPath = Join-Path $reportDir ("gate-report-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".md")
    } catch {
        Add-Result "runner.reportPath" "ERROR" ("cannot create reports directory (" + $reportDir + "): " + $_.Exception.Message)
    }
}
if ($ReportPath -ne "") {
    try {
        $probe = [System.IO.File]::Open($ReportPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $probe.Close()
    } catch {
        Add-Result "runner.reportPath" "ERROR" ("report path is NOT writable (" + $ReportPath + "): " + $_.Exception.Message + " - no report file can be produced")
    }
}

} catch {
    # WBS-2.2.7 / R4: 脚本自身异常 -> 记 ERROR 态（非"代码不合格"）+ runnerCrashed 标记 -> 退出码 2。
    # 必须记 ERROR 而非 FAIL：R2 让 RED（任一 FAIL）优先于 ERROR，若这里记 FAIL，脚本崩溃就会被
    # 误报成"代码不合格的红灯=1"，与"崩溃=2"的语义自相矛盾。
    $script:runnerCrashed = $true
    Add-Result "runner" "ERROR" ("runner aborted by unexpected error (exit code 2, NOT a code verdict): " + $_.Exception.Message + " | " + $_.ScriptStackTrace)
}

# ---------- Verdict (R2: RED 优先于 ERROR —— 真红灯绝不能被环境/配置错误掩盖) ----------
$green = @($results | Where-Object { $_.Verdict -eq "PASS" }).Count
$red = @($results | Where-Object { $_.Verdict -eq "FAIL" }).Count
$skip = @($results | Where-Object { $_.Verdict -eq "SKIP" }).Count
$envErr = @($results | Where-Object { $_.Verdict -eq "ERROR" }).Count
$pend = @($results | Where-Object { $_.Verdict -eq "PENDING" }).Count
$verdict = "GREEN"
$exitCode = 0
if ($red -gt 0) {
    $verdict = "RED"
    $exitCode = 1
    if ($envErr -gt 0) { $verdict = "RED (also " + $envErr + " ERROR row(s): environment/config problems must NOT mask this red light)" }
} elseif ($script:runnerCrashed) {
    $verdict = "ERROR (runner crashed - see runner row; exit code 2, NOT a code verdict)"
    $exitCode = 2
} elseif ($envErr -gt 0) {
    $verdict = "ERROR (environment/config - NOT a code verdict; exit code 2; fix and re-run)"
    $exitCode = 2
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# C-TDS Gate Report")
$lines.Add("")
$lines.Add("- RunLabel: " + $runLabel)
$lines.Add("- Scenario: " + $Scenario)
$lines.Add("- ExitCode: " + $exitCode + "  (must equal the process exit code: 0=GREEN, 1=RED, 2=config/env)")
$lines.Add("- ConfigPath: " + $cfgPath)
$lines.Add("- ConfigSha256: " + $configSha256)
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

# 兜底：若执行中途崩溃以致探针未跑，这里仍尽力写出报告（写不进去也算 ERROR 而非绿灯）
$reportTarget = $ReportPath
if ($reportTarget -eq "") {
    $reportTarget = Join-Path $PSScriptRoot ("reports\gate-report-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".md")
}
$reportWritten = $true
$writeErr = ""
try {
    [System.IO.File]::WriteAllText($reportTarget, $report, (New-Object System.Text.UTF8Encoding($true)))
} catch {
    $reportWritten = $false
    $writeErr = $_.Exception.Message
}
Write-Output $report
Write-Output ""
if ($reportWritten) {
    Write-Output "Report saved to: $reportTarget"
} else {
    # 无报告 = 无证据 -> 不得给绿灯（R4）；但真红灯仍保持退出码 1（R2）
    if ($exitCode -eq 0) { $exitCode = 2 }
    Write-Output ("[WARN] report could not be written to " + $reportTarget + ": " + $writeErr + " -> exit code " + $exitCode)
}
Write-Output ("RunLabel: " + $runLabel + " | Scenario: " + $Scenario + " | ExitCode: " + $exitCode)
exit $exitCode
