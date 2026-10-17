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
# DB-43（2026-10-14，门禁变更流程留痕〔红线 3〕，gates-config.json 零改动 = ConfigSha256 不变自证）
# record: ① mvn 三段（compile/lint/unitTest）成功/失败/超时三态落盘
#   %TEMP%\ctds-gate-<stage>-out.log（摘要行 stage/command/exitCode/elapsedSec/time + 完整原始输出；
#   根治 WBS-3.4.7"0 字节日志"教训——成功态也有机器可核证据）；② npm 段与 coverage /
#   mutationTest / sast 段失败/超时落盘完整原始输出 + 报告失败/超时行尾附
#   " | full output: <path>"（DB-44 首跑 RED"错误正文未取得"损失场景的根治；
#   Invoke-MetricProcess 超时分支不再丢弃已读流）；③ 阶段超时后 Stop-ProcessTree
#   （taskkill /PID <pid> /T /F）清理整个进程树（跟踪-23 剩余子项兑现，Q1-A）。
#   判定阈值 / 阶段范围 / 扫描面 / 报告五态与退出码语义零改动；PASS 行格式零改动；
#   落盘失败不影响段判定（记录是加菜，不是判定）。
#   依据：任务卡 docs/tasks/DB-43-门禁可观测性增强-2026-10-13.md + 两级设计
#   docs/designs/DB-43-{lofi,hifi}.md（V1.0 已确认）+ ADR-018 修订记录 V1.3。
# DB-07（2026-10-16，CI 门禁自动化·编排师一次确认 Q5-A + 编码段红线 4 停报后裁定 C，config V1.8，
# ADR-018 V1.5）record: 新增 gateSelfTest 执行器段（见下方 Stage: gateSelfTest——位于全阶段之首；
# 退出码 0/1/2→PASS/FAIL/ERROR，超时=FAIL；selftest.ps1 同步适配：New-FixtureConfig 递归守卫 +
# S15 探针〔绿/空格路径/红/ERROR/缺文件/hang〕，断言只增不减）。Invoke-MetricProcess 由原"质量
# 度量段"位置纯上移至文件头部（函数体内容零改动，diff 呈现为迁移——执行器置首须可调用）。
# 判定阈值 / 阶段范围 / 扫描面 / 退出码语义 / 报告五态语义零改动；gates-config.json 改动面 =
# gateSelfTest.enabled 一位 + timeoutSeconds 新增键 + 移除 status + note + changeLog/version。
# DB-07 V5（2026-10-17，CI 覆盖率口径裁决——编排师授权"评估后按合理建议执行"；config V1.11 +
# ADR-018 V1.8 留痕）record: coverage 段环境自适应判定。背景：CI（GitHub 托管 Windows runner，
# 裁决 B 既定）无 Linux 容器 Docker，Testcontainers 集成测试按测试代码既有语义静默 skip（实证：
# 复跑 4〔run 39024057954〕coverage 45.61%〔4153/9106，core did 52.96%〕，16/16 模块 BUILD
# SUCCESS、contract 240 例 skip 127；本地有 Docker 真值 94.1%）——CI 物理上测不出章程口径覆盖率，
# 以低阈值冒充达标 = 绿灯失真（弃选项①原案）、永久 FAIL = 门禁长红失效（弃选项②）、换 runner =
# 推翻裁决 B 且丢失 Windows 特有验证面（弃选项③）。口径：Docker Linux 容器可用 → 阈值判定不变；
# CI 环境且不可用 → jacoco 照常执行（实测值入报告不丢），阈值判定降级 SKIP（detail 明示实测值 +
# 未达标项 + 降级原因 + 权威判定归属本地门禁）；本地无 Docker → 维持 FAIL（可修复环境不开口子）。
# 执行层失败（exit≠0 / 无报告 / 超时）不降级。机器护栏 = selftest S17 三探针；判定阈值 / 阶段范围 /
# 扫描面 / 退出码语义 / 五态语义零改动（降级复用既有 SKIP 态——"no FAIL; PENDING/SKIP allowed"）。
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
# DB-07 V1.2（2026-10-17，CI 取证修复）：Windows 8.3 短路径（CI 的 C:\Users\RUNNER~1\...）与
# Get-ChildItem 返回的长路径（C:\Users\runneradmin\...）混用，会让"按根长度截断"的相对化错位
# （实证：相对路径变成 "-s2/docs/..."——S2 锁定文件被判"未入库"降级 SKIP、S6 扫描命中路径对不上）。
# 入口统一把 RepoRoot 展开为长路径；相对化改为前缀剥离 + 兜底，绝不静默截断。
# DB-07 V4 复跑 3 取证再修正（2026-10-17）：cmd /c "cd /d <短路径> && cd" **不展开** 8.3 短名
# ——cmd 的 cd 无参回显的是"进入时给出的路径原样"（本地实测：cd /d C:\PROGRA~1 后 cd 仍回显
# C:\PROGRA~1），故修复批 864f9d2 的展开方法无效：CI 复跑 3 的 S2/S6 五断言仍失败（报告 Repo
# 行仍为短路径，SKIP/FAIL 行列出长路径全名 = 前缀剥离失配走全路径兜底；本地短路径 TEMP 复现同型）。
# 改用 Get-Item -LiteralPath 的 FullName：文件系统提供程序逐组件解析为长名（本地实测
# C:\PROGRA~1 -> C:\Program Files，嵌套 C:\PROGRA~1\COMMON~1 同样成立，PS 5.1 与 7 双解释器一致）。
function Resolve-LongDirPath([string]$path) {
    try {
        $item = Get-Item -LiteralPath $path
        if ($item -and $item.FullName) { return $item.FullName }
    } catch { }
    return $path
}
function Get-RelRepoPath([string]$fullPath) {
    $r = $RepoRoot.TrimEnd('\', '/')
    if ($fullPath.StartsWith($r + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($fullPath.Substring($r.Length + 1) -replace '\\', '/')
    }
    return ($fullPath -replace '\\', '/')
}
$RepoRoot = Resolve-LongDirPath $RepoRoot

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

# ---------- DB-43（2026-10-14，门禁变更流程留痕，config 零改动）：阶段原始输出落盘 ----------
# ① mvn 三段（compile/lint/unitTest）成功/失败/超时三态都落盘（WBS-3.4.7"0 字节日志"教训：
#    成功态也必须有机器可核证据）；③ npm / coverage / mutationTest / sast 段失败/超时落盘
#    （DB-44 首跑 RED"错误正文未取得"损失场景的根治）。命名沿用既有
#    %TEMP%\ctds-gate-<stage>-out.log（Q2-A：固定名 = 最近一次该阶段运行的覆盖式记录）。
# 落盘失败绝不影响段判定（调用处 try/catch 包裹——"记录是加菜，不是判定"）。
function Write-StageOutLog([string]$stage, [string]$command, [string]$body, [int]$exitCode, [bool]$timedOut, [int]$elapsedSec) {
    $path = Join-Path $env:TEMP ("ctds-gate-" + $stage + "-out.log")
    $exitSuffix = ""
    if ($timedOut) { $exitSuffix = " (TIMED OUT)" }
    # 摘要行（stage/command/exitCode/elapsedSec/time）——即使 body 为空，"0 字节日志"因摘要头
    # 在结构上不可能（WBS-3.4.7 教训的结构性根治）。数组 join 形态：行首 '+' 会被 PowerShell
    # 解析成一元加（字符串转 Int32 报错），故不用跨行 '+' 拼接。
    $headLines = @(
        "# ctds-gate stage output",
        ("stage: " + $stage),
        ("command: " + $command),
        ("exitCode: " + $exitCode + $exitSuffix),
        ("elapsedSec: " + $elapsedSec),
        ("time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")),
        (([string][char]0x2500) * 40)
    )
    [System.IO.File]::WriteAllText($path, (($headLines -join [Environment]::NewLine) + [Environment]::NewLine + $body))
    return $path
}

# ---------- DB-43（Q1-A / 跟踪-23 剩余）：阶段超时后清理整个进程树 ----------
# 背景：2026-10-09 实测"unitTest 超时被杀后残留 cmd 孤儿进程"污染 coverage/frontendTest。
# taskkill /T 按父子快照清理（Windows 定位脚本，系统内置零新依赖）；"已脱离父链的孙进程"
# 可能漏网（罕见，诚实边界见 README 变更条目）。进程已退出时不再补 Kill。
function Stop-ProcessTree([System.Diagnostics.Process]$proc) {
    try { $null = & taskkill /PID $proc.Id /T /F 2>&1 } catch { }
    try { if (-not $proc.HasExited) { $proc.Kill() } } catch { }
}

# ---------- 通用辅助：按超时运行进程（不经 cmd 拼接，参数直接传递，无 shell 注入面） ----------
# DB-06 质量度量段引入；DB-07（编排师裁定 C）gateSelfTest 执行器复用——执行器须位于全阶段之首
# （selftest 开场/收尾清理及其 fixture 运行均写 %TEMP% 固定名 ctds-gate-*-out.log，置首避免
# 删除/覆盖本运行先前阶段的失败取证），故本函数由原"质量度量段"位置纯上移至此：
# 函数体内容零改动，diff 呈现为迁移（留痕见 ADR-018 V1.5 与 hifi §3-bis）。
function Invoke-MetricProcess([string]$exe, [string]$argLine, [string]$workDir, [int]$timeoutSec, [string]$stage) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = $argLine
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $workDir
    # DB-19 同款口径：JVM 系工具输出按 UTF-8 解码（中文 Windows 默认 GBK 会乱码红灯明细）
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($timeoutSec * 1000)) {
        # DB-43：超时也保有已产出的原始输出（原实现丢弃已读流，超时零证据）；进程树清理（Q1-A）
        Stop-ProcessTree $proc
        $null = $outTask.Wait(5000)
        $null = $errTask.Wait(5000)
        $timedOutBody = ("<timed out after " + $timeoutSec + "s>")
        try { $timedOutBody = ("<timed out after " + $timeoutSec + "s>") + [Environment]::NewLine + $outTask.Result + $errTask.Result } catch { }
        return @{ ExitCode = -1; Output = $timedOutBody }
    }
    $null = $outTask.Wait(10000); $null = $errTask.Wait(10000)
    return @{ ExitCode = $proc.ExitCode; Output = ($outTask.Result + [Environment]::NewLine + $errTask.Result) }
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

# ---------- Stage: gateSelfTest (DB-07 CI 门禁自动化·编排师裁定 C, config V1.8, ADR-018 V1.5, hifi §3-bis) ----------
# 运行器自检执行器：enabled=true 时每轮门禁实跑 selftest.ps1（S1~S15，只写 %TEMP%），结论写为报告行。
# 位置 = 全阶段之首：selftest 开场/收尾清理及其 fixture 运行均写 %TEMP% 固定名 ctds-gate-*-out.log，
# 置首避免删除/覆盖本运行先前阶段的失败取证（DB-43 取证链）。
# 语义：0=PASS / 1=FAIL（断言失败=门禁自身不可信，红灯必修）/ 2=ERROR（harness/环境，非代码结论）；
# 超时=FAIL（与既有阶段一致）。enabled=false 时由下方 PENDING 循环出可见行（不留静默关闭）。
$gstStage = $cfg.stages.gateSelfTest
if ($gstStage -and $gstStage.enabled) {
    $gstTool = "scripts/gates/selftest.ps1"
    if ($gstStage.tool) { $gstTool = $gstStage.tool }
    $gstScript = Join-Path $RepoRoot $gstTool
    if (-not (Test-Path $gstScript)) {
        Add-Result "gateSelfTest" "ERROR" ("selftest script not found: " + $gstTool + " (resolved under RepoRoot) - fix stages.gateSelfTest.tool or restore the script")
    } elseif (-not (Get-Command powershell -ErrorAction SilentlyContinue)) {
        Add-Result "gateSelfTest" "ERROR" "powershell executable not found on PATH (required to run selftest.ps1)"
    } else {
        $gstTimeout = 300; if ($gstStage.timeoutSeconds) { $gstTimeout = [int]$gstStage.timeoutSeconds }
        # 引号包裹（同 mutationTest pitArgs 惯例）：仓库路径含空格（E:\AI Projects\...），未加引号的
        # -File 路径会被 powershell 命令行解析截断——S15 "spaces" 探针锚定该风险面。
        # 单变量构造（③ F-c2：$gstCmd 与 argLine 原为同串构建两次）
        $gstArgLine = "-NoProfile -ExecutionPolicy Bypass -File `"" + $gstScript + "`""
        $gstCmd = "powershell " + $gstArgLine
        $gstSw = [System.Diagnostics.Stopwatch]::StartNew()
        $gstRun = Invoke-MetricProcess "powershell" $gstArgLine $RepoRoot $gstTimeout "gateSelfTest"
        $gstElapsed = [int]$gstSw.Elapsed.TotalSeconds
        $gstTail = ($gstRun.Output -split "`r?`n" | Where-Object { $_.Trim() -ne "" } | Select-Object -Last 3) -join " | "
        if ($gstRun.ExitCode -eq -1) {
            $p = $null
            try { $p = Write-StageOutLog "gateSelfTest" $gstCmd $gstRun.Output -1 $true $gstElapsed } catch { }
            if ($p) { Add-Result "gateSelfTest" "FAIL" ("selftest timed out after " + $gstTimeout + "s | full output: " + $p) }
            else { Add-Result "gateSelfTest" "FAIL" ("selftest timed out after " + $gstTimeout + "s") }
        } elseif ($gstRun.ExitCode -eq 0) {
            # 成功态详情摘录 selftest 末行摘要（"SELFTEST PASS - N assertion(s), 0 failure(s)"）——机器可核
            $gstSummary = "exit 0"
            if ($gstRun.Output -match "SELFTEST PASS - (\d+) assertion\(s\), (\d+) failure\(s\)") {
                $gstSummary = ("exit 0, " + $Matches[1] + " assertions, " + $Matches[2] + " failures")
            }
            Add-Result "gateSelfTest" "PASS" ("selftest.ps1 " + $gstSummary + " in " + $gstElapsed + "s")
        } elseif ($gstRun.ExitCode -eq 2) {
            $p = $null
            try { $p = Write-StageOutLog "gateSelfTest" $gstCmd $gstRun.Output 2 $false $gstElapsed } catch { }
            if ($p) { Add-Result "gateSelfTest" "ERROR" ("selftest harness error (exit 2 = fixture/env problem, NOT a code verdict): " + $gstTail + " | full output: " + $p) }
            else { Add-Result "gateSelfTest" "ERROR" ("selftest harness error (exit 2 = fixture/env problem, NOT a code verdict): " + $gstTail) }
        } else {
            $p = $null
            try { $p = Write-StageOutLog "gateSelfTest" $gstCmd $gstRun.Output $gstRun.ExitCode $false $gstElapsed } catch { }
            if ($p) { Add-Result "gateSelfTest" "FAIL" ("selftest exit " + $gstRun.ExitCode + " (runner self-check FAILED - the gate cannot vouch for this run): " + $gstTail + " | full output: " + $p) }
            else { Add-Result "gateSelfTest" "FAIL" ("selftest exit " + $gstRun.ExitCode + " (runner self-check FAILED): " + $gstTail) }
        }
    }
}

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
    $binarySkipped = New-Object System.Collections.Generic.List[string]
    $binaryUntracked = 0
    # DB-18 ①：目录枚举失败（无权限/被占用）不得静默——整个子树没进扫描名单必须记 ERROR（退出码 2）
    $scanEnumErrors = @()
    $all = @(Get-ChildItem -Path $RepoRoot -Recurse -File -ErrorAction SilentlyContinue -ErrorVariable scanEnumErrors)
    # DB-18 ②：>=1MB 文件按"入库/未入库"区分——入库的逐条列名（SKIP 行供人工复核），不再只进总数
    $excludedCount = 0
    $oversized = New-Object System.Collections.Generic.List[string]
    $oversizedUntracked = 0
    $files = New-Object System.Collections.Generic.List[object]
    foreach ($f in $all) {
        $rel = Get-RelRepoPath $f.FullName
        if (Test-Excluded $rel $exclude) { $excludedCount++; continue }
        if ($f.Length -ge 1MB) {
            if ($tracked.Contains($rel)) { $oversized.Add($rel) } else { $oversizedUntracked++ }
            continue
        }
        $files.Add($f)
    }
    foreach ($f in $files) {
        $rel = Get-RelRepoPath $f.FullName
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
        # binary skip: NUL byte in first 1024; DB-18 ③：入库二进制逐条列名，不得静默逃逸
        $head = $bytes[0..([Math]::Min(1023, $bytes.Length - 1))]
        if ($head -contains 0) {
            if ($tracked.Contains($rel)) { $binarySkipped.Add($rel) } else { $binaryUntracked++ }
            continue
        }
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        foreach ($p in $patterns) {
            if ([System.Text.RegularExpressions.Regex]::IsMatch($text, $p, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
                $hit++
                Add-Result "secretsScan" "FAIL" ("Pattern hit in " + $rel + " (content withheld)")
                break
            }
        }
    }
    if ($hit -eq 0) { Add-Result "secretsScan" "PASS" ("Scanned " + $files.Count + " files, 0 hits (not scanned: " + $excludedCount + " excluded, " + ($oversized.Count + $oversizedUntracked) + " oversized, " + ($binarySkipped.Count + $binaryUntracked) + " binary, " + $unreadable.Count + " unreadable)") }
    # DB-18：三类"没扫到"都必须在报告里可见——枚举失败=ERROR（退出码 2）；超大/二进制=SKIP 且入库文件逐条列名
    if ($scanEnumErrors.Count -gt 0) {
        $enumPaths = @($scanEnumErrors | ForEach-Object { [string]$_.TargetObject } | Where-Object { $_ -ne "" } | Sort-Object -Unique)
        Add-Result "secretsScan.enumGap" "ERROR" ("Enumerating repo files FAILED for " + $enumPaths.Count + " path(s) (permission/locked) - anything inside them silently escaped the scan; list: " + ($enumPaths -join ", "))
    }
    if ($oversized.Count -gt 0 -or $oversizedUntracked -gt 0) {
        $d = "not regex-scanned (>= 1MB)"
        if ($oversized.Count -gt 0) { $d += " - tracked, listed for manual review: " + ($oversized -join ", ") }
        if ($oversizedUntracked -gt 0) { $d += " - untracked: " + $oversizedUntracked }
        Add-Result "secretsScan.oversized" "SKIP" (($oversized.Count + $oversizedUntracked).ToString() + " file(s) " + $d)
    }
    if ($binarySkipped.Count -gt 0 -or $binaryUntracked -gt 0) {
        $d = "skipped by the NUL-byte heuristic"
        if ($binarySkipped.Count -gt 0) { $d += " - tracked, listed for manual review: " + ($binarySkipped -join ", ") }
        if ($binaryUntracked -gt 0) { $d += " - untracked: " + $binaryUntracked }
        Add-Result "secretsScan.binary" "SKIP" (($binarySkipped.Count + $binaryUntracked).ToString() + " binary file(s) " + $d)
    }
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
    $oldJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $toolJavaHome
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c " + $toolMavenBin + " " + $toolMavenArgs + " " + $s.goals
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $RepoRoot
    # V1.5 (2026-10-09, WBS-3.4.6 跟踪-23 处置, 编排师裁决 A): mvn 阶段超时可经 stages.<name>.timeoutSeconds
    # 覆盖（与 coverage/mutationTest/sast 同一配置键），未配置时默认 600s 不变（compile/lint 未设值 = 行为零变化）。
    # 留痕: gates-config.json changeLog V1.5 + docs/tasks/WBS-3.4.6-策略模拟器与测试台-2026-10-08.md §五 跟踪-23。
    $stageTimeoutSec = 600
    if ($s.PSObject.Properties['timeoutSeconds'] -and $s.timeoutSeconds) {
        $stageTimeoutSec = [int]$s.timeoutSeconds
    }
    # DB-43 ①：三态（成功/失败/超时）都落盘 %TEMP%\ctds-gate-<stage>-out.log（摘要行含
    # stage/command/exitCode/elapsedSec/time + 完整原始输出）；报告行仅失败/超时追加路径。
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $cmdLine = "mvn " + $toolMavenArgs + " " + $s.goals
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($stageTimeoutSec * 1000)) {
        Stop-ProcessTree $proc
        $null = $outTask.Wait(5000)
        $null = $errTask.Wait(5000)
        $p = $null
        try { $p = Write-StageOutLog $stageName $cmdLine ($outTask.Result + $errTask.Result) -1 $true ([int]$sw.Elapsed.TotalSeconds) } catch { }
        if ($p) { Add-Result $stageName "FAIL" ("mvn " + $s.goals + " timed out after " + $stageTimeoutSec + "s | full output: " + $p) }
        else { Add-Result $stageName "FAIL" ("mvn " + $s.goals + " timed out after " + $stageTimeoutSec + "s") }
    } elseif ($proc.ExitCode -eq 0) {
        $null = $outTask.Wait(10000)
        $null = $errTask.Wait(10000)
        # ① 的核心：成功态也落盘（"0 字节日志"教训的结构性根治）；PASS 报告行保持原格式（不加路径）
        try { $null = Write-StageOutLog $stageName $cmdLine ($outTask.Result + $errTask.Result) 0 $false ([int]$sw.Elapsed.TotalSeconds) } catch { }
        Add-Result $stageName "PASS" ("mvn " + $s.goals + " exit 0")
    } else {
        $null = $outTask.Wait(10000)
        $null = $errTask.Wait(10000)
        $allOut = ($outTask.Result + [Environment]::NewLine + $errTask.Result) -split "`r?`n" | Where-Object { $_.Trim() -ne "" }
        $tail = ($allOut | Select-Object -Last 6) -join " | "
        $p = $null
        try { $p = Write-StageOutLog $stageName $cmdLine ($outTask.Result + $errTask.Result) $proc.ExitCode $false ([int]$sw.Elapsed.TotalSeconds) } catch { }
        if ($p) { Add-Result $stageName "FAIL" ("mvn " + $s.goals + " exit " + $proc.ExitCode + ": " + $tail + " | full output: " + $p) }
        else { Add-Result $stageName "FAIL" ("mvn " + $s.goals + " exit " + $proc.ExitCode + ": " + $tail) }
    }
    if ($oldJavaHome) { $env:JAVA_HOME = $oldJavaHome } else { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
}

# ---------- Frontend stages (npm; WBS 2.4.12 G1-G3, change-trace: docs/designs/WBS-2.4.12-{lofi,hifi}.md + ADR-011;
#   frontendTypeCheck added 2026-09-26 by 清债卡4 (config V1.3), change-trace: docs/tasks/DB-06-23-24-前端类型-清债小卡-2026-09-25.md 卡4) ----------
foreach ($stageName in @("frontendTypeCheck", "frontendLint", "frontendTest", "frontendE2E")) {
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
    # DB-19：Node.js/npm 的输出恒为 UTF-8，不显式指定时 .NET 按系统默认（中文 Windows=GBK）解码，
    # 报告 FAIL 明细的中文变乱码（红灯不丢，但人工复核"为什么红"失效）。Maven 段不改——JDK 17 于
    # 中文 Windows 的输出为平台编码（GBK），与默认解码一致，强行 UTF-8 反而引入乱码。
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $psi.WorkingDirectory = $workdir
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    # DB-43 ③：npm 段失败/超时落盘完整原始输出（③ 的处置要求原文 = "失败时落盘"；成功态不落）
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    if (-not $proc.WaitForExit(300000)) {
        Stop-ProcessTree $proc
        $null = $outTask.Wait(5000)
        $null = $errTask.Wait(5000)
        $p = $null
        try { $p = Write-StageOutLog $stageName ("npm " + $s.goals) ($outTask.Result + $errTask.Result) -1 $true ([int]$sw.Elapsed.TotalSeconds) } catch { }
        if ($p) { Add-Result $stageName "FAIL" ("npm " + $s.goals + " timed out after 300s | full output: " + $p) }
        else { Add-Result $stageName "FAIL" ("npm " + $s.goals + " timed out after 300s") }
    } elseif ($proc.ExitCode -eq 0) {
        Add-Result $stageName "PASS" ("npm " + $s.goals + " exit 0")
    } else {
        $null = $outTask.Wait(10000)
        $null = $errTask.Wait(10000)
        $allOut = ($outTask.Result + [Environment]::NewLine + $errTask.Result) -split "`r?`n" | Where-Object { $_.Trim() -ne "" }
        $tail = ($allOut | Select-Object -Last 6) -join " | "
        $p = $null
        try { $p = Write-StageOutLog $stageName ("npm " + $s.goals) ($outTask.Result + $errTask.Result) $proc.ExitCode $false ([int]$sw.Elapsed.TotalSeconds) } catch { }
        if ($p) { Add-Result $stageName "FAIL" ("npm " + $s.goals + " exit " + $proc.ExitCode + ": " + $tail + " | full output: " + $p) }
        else { Add-Result $stageName "FAIL" ("npm " + $s.goals + " exit " + $proc.ExitCode + ": " + $tail) }
    }
}

# ---------- Quality metrics: coverage / mutationTest / sast (DB-06/清债卡1, config V1.4, ADR-018) ----------
# （Invoke-MetricProcess 通用辅助已由 DB-07〔编排师裁定 C〕纯上移至文件头部——gateSelfTest 执行器
#   位于全阶段之首须可调用；本处不再重复定义。）

$javaExe = "java"
if ($toolJavaHome) { $javaExe = Join-Path $toolJavaHome "bin\java.exe" }

# ----- coverage 环境自适应判定辅助（DB-07 V5，见文件头 record） -----
function Test-CiEnvironment {
    # GitHub Actions 恒注入 GITHUB_ACTIONS=true；CI=true 为通用惯例（GitHub 同设）。两者其一即 CI。
    return (($env:GITHUB_ACTIONS -eq "true") -or ($env:CI -eq "true"))
}
function Test-LinuxDockerDaemon {
    # 仅 Linux 容器 daemon 可供 Testcontainers 跑真值（GitHub 托管 Windows runner 的 docker 仅
    # Windows 容器，OSType=windows 同样判不可用）；无 CLI / daemon 不可达 / 探测超时均判 false。
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $false }
    $probe = Invoke-MetricProcess "cmd.exe" "/c docker info --format {{.OSType}}" $RepoRoot 60 "coverage-docker-probe"
    if ($probe.ExitCode -ne 0) { return $false }
    return (($probe.Output.Trim()) -ieq "linux")
}

# ----- coverage (jacoco 命令行形态：prepare-agent test report 一次完成，零 pom 变更) -----
$covStage = $cfg.stages.coverage
if ($covStage -and $covStage.enabled) {
    if (-not $toolJavaHome -or -not (Test-Path $toolJavaHome)) {
        Add-Result "coverage" "ERROR" "JAVA_HOME not found (set env JAVA_HOME or gates-config toolchain.javaHome)"
    } elseif (-not (Get-Command $toolMavenBin -ErrorAction SilentlyContinue)) {
        Add-Result "coverage" "ERROR" ("Maven executable not found on PATH: " + $toolMavenBin)
    } elseif ($covStage.goals -and ($covStage.goals -notmatch '^[A-Za-z0-9:._\-\s]+$')) {
        Add-Result "coverage" "ERROR" "Illegal characters in gates-config stages.coverage.goals - stage SKIPPED, command NOT executed"
    } else {
        $covTimeout = 900; if ($covStage.timeoutSeconds) { $covTimeout = [int]$covStage.timeoutSeconds }
        # DB-07 V5：降级判定在段首一次性探测（CI 环境 且 无 Linux Docker）；非 CI 短路不探测
        # （本地判定语义零变化）；仅作用于下方阈值判定环节，执行层失败不降级。
        $covDefer = (Test-CiEnvironment) -and (-not (Test-LinuxDockerDaemon))
        # cmd /c 包一层（同 maven 段形态）：Process.Start 不解析 PATH，mvn 须经 cmd 定位
        $argLine = "/c " + $toolMavenBin + " " + $toolMavenArgs + " " + $covStage.goals
        $swCov = [System.Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-MetricProcess "cmd.exe" $argLine $RepoRoot $covTimeout "coverage"
        # DB-43 ③：凡该段判定 FAIL（超时 / exit≠0 / 无报告 / 阈值不达标）→ 落盘完整原始输出，
        # 报告行尾附路径（PASS 不落——③ 的原文口径 = "失败时落盘"）
        $covFail = $false
        $covDetail = ""
        if ($r.ExitCode -eq -1) {
            $covFail = $true
            $covDetail = $r.Output
        } elseif ($r.ExitCode -ne 0) {
            $tail = (($r.Output -split "`r?`n") | Where-Object { $_.Trim() -ne "" } | Select-Object -Last 5) -join " | "
            $covFail = $true
            $covDetail = ("mvn coverage exit " + $r.ExitCode + ": " + $tail)
        } else {
            $reportGlobs = @("common\*\target\site\jacoco\jacoco.xml", "services\*\target\site\jacoco\jacoco.xml", "std-adapter\target\site\jacoco\jacoco.xml")
            $reports = @(); foreach ($g in $reportGlobs) { $reports += @(Get-ChildItem (Join-Path $RepoRoot $g) -ErrorAction SilentlyContinue) }
            if ($reports.Count -eq 0) {
                $covFail = $true
                $covDetail = "mvn coverage exit 0 but no jacoco.xml reports found (report generation silently skipped?)"
            } else {
                $totCovered = [long]0; $totMissed = [long]0; $coreRows = @()
                $coreNames = @(); if ($cfg.coreModules) { $coreNames = @($cfg.coreModules) }
                foreach ($rep in $reports) {
                    [xml]$doc = [System.IO.File]::ReadAllText($rep.FullName, [System.Text.Encoding]::UTF8)
                    $cnt = $doc.report.counter | Where-Object { $_.type -eq "LINE" } | Select-Object -First 1
                    if (-not $cnt) { continue }
                    $c = [long]$cnt.covered; $m = [long]$cnt.missed
                    $totCovered += $c; $totMissed += $m
                    $moduleName = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $rep.FullName)))
                    $moduleName = Split-Path -Leaf $moduleName
                    if ($coreNames -contains $moduleName) {
                        $pct = if (($c + $m) -gt 0) { [math]::Round(100.0 * $c / ($c + $m), 2) } else { 0 }
                        $coreRows += ($moduleName + " " + $pct + "%")
                    }
                }
                $overallPct = if (($totCovered + $totMissed) -gt 0) { [math]::Round(100.0 * $totCovered / ($totCovered + $totMissed), 2) } else { 0 }
                $coreLine = 80.0; $overallLine = 70.0
                if ($cfg.thresholds -and $cfg.thresholds.coreLineCoverage) { $coreLine = [double]$cfg.thresholds.coreLineCoverage }
                if ($cfg.thresholds -and $cfg.thresholds.overallLineCoverage) { $overallLine = [double]$cfg.thresholds.overallLineCoverage }
                $bad = @()
                if ($overallPct -lt $overallLine) { $bad += ("overall " + $overallPct + "% < " + $overallLine + "%") }
                foreach ($coreRep in $reports) {
                    [xml]$doc2 = [System.IO.File]::ReadAllText($coreRep.FullName, [System.Text.Encoding]::UTF8)
                    $cnt2 = $doc2.report.counter | Where-Object { $_.type -eq "LINE" } | Select-Object -First 1
                    if (-not $cnt2) { $bad += ("core:" + (Split-Path -Leaf (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $coreRep.FullName))))) + " report has no LINE counter (silent skip is not allowed)"); continue }
                    $c2 = [long]$cnt2.covered; $m2 = [long]$cnt2.missed
                    $mName = Split-Path -Leaf (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $coreRep.FullName))))
                    if (($coreNames -contains $mName) -and (($c2 + $m2) -gt 0)) {
                        $p2 = 100.0 * $c2 / ($c2 + $m2)
                        if ($p2 -lt $coreLine) { $bad += ("core:" + $mName + " " + [math]::Round($p2, 2) + "% < " + $coreLine + "%") }
                    }
                }
                $detail = ("overall line " + $overallPct + "% (" + $totCovered + "/" + ($totCovered + $totMissed) + "), core [" + ($coreRows -join ", ") + "], thresholds core>=" + $coreLine + "% overall>=" + $overallLine + "%")
                if ($bad.Count -gt 0) {
                    if ($covDefer) {
                        # DB-07 V5：CI 无 Linux Docker —— 阈值判定降级 SKIP；实测值与未达标项如实
                        # 入报告（测量数据不丢），权威覆盖率判定归属有 Docker 的本地门禁
                        Add-Result "coverage" "SKIP" ($detail + " - threshold verdict DEFERRED [" + ($bad -join "; ") + "]: no Linux Docker daemon in this CI environment (Testcontainers integration tests skip per test-code semantics; measured value not comparable to thresholds) - authoritative coverage verdict = local gate with Docker")
                    } else {
                        $covFail = $true
                        $covDetail = ($detail + " - " + ($bad -join "; "))
                    }
                }
                else { Add-Result "coverage" "PASS" $detail }
            }
        }
        if ($covFail) {
            $p = $null
            try { $p = Write-StageOutLog "coverage" $argLine $r.Output $r.ExitCode ($r.ExitCode -eq -1) ([int]$swCov.Elapsed.TotalSeconds) } catch { }
            if ($p) { Add-Result "coverage" "FAIL" ($covDetail + " | full output: " + $p) }
            else { Add-Result "coverage" "FAIL" $covDetail }
        }
    }
}

# ----- mutationTest (PIT CLI: bundle 聚合 -> classpath 生成 -> 模块根运行 -> CSV 解析) -----
$mutStage = $cfg.stages.mutationTest
if ($mutStage -and $mutStage.enabled) {
    if (-not (Test-Path $javaExe)) {
        Add-Result "mutationTest" "ERROR" ("java executable not found: " + $javaExe + " (check JAVA_HOME / toolchain.javaHome)")
    } else {
        $bundleDir = Join-Path $RepoRoot "target\precheck\pit-bundle"
        $marker = Join-Path $bundleDir "bundle-ready.flag"
        $bundleOk = (Test-Path $marker) -and (@(Get-ChildItem (Join-Path $bundleDir "*.jar") -ErrorAction SilentlyContinue).Count -gt 0)
        # DB-43 ③：mutationTest 各失败点的原始输出收集器（段末汇总落盘，分隔符区分子运行）
        $mutLogs = @()
        $swMut = [System.Diagnostics.Stopwatch]::StartNew()
        if (-not $bundleOk) {
            $deps = @(); if ($mutStage.bundle -and $mutStage.bundle.dependencies) { $deps = @($mutStage.bundle.dependencies) }
            $pomLines = @('<?xml version="1.0" encoding="UTF-8"?>', '<project xmlns="http://maven.apache.org/POM/4.0.0">', '  <modelVersion>4.0.0</modelVersion>', '  <groupId>ctds.gate</groupId><artifactId>pit-bundle-assembly</artifactId><version>1.0.0</version><packaging>pom</packaging>', '  <dependencies>')
            foreach ($d in $deps) { $pomLines += ('    <dependency><groupId>' + $d.groupId + '</groupId><artifactId>' + $d.artifactId + '</artifactId><version>' + $d.version + '</version></dependency>') }
            $pomLines += @('  </dependencies>', '</project>')
            New-Item -ItemType Directory -Path $bundleDir -Force | Out-Null
            [System.IO.File]::WriteAllLines((Join-Path $bundleDir "pom.xml"), $pomLines)
            $r = Invoke-MetricProcess "cmd.exe" ("/c " + $toolMavenBin + " -f pom.xml -B -ntp -q dependency:copy-dependencies -DoutputDirectory=.") $bundleDir 300 "mutationTest.bundle"
            $keyJars = @(); foreach ($d in $deps) { $keyJars += ($d.artifactId + "-" + $d.version + ".jar") }
            $missing = @($keyJars | Where-Object { -not (Test-Path (Join-Path $bundleDir $_)) })
            if ($r.ExitCode -ne 0 -or $missing.Count -gt 0) {
                # bundle 失败后无段末汇总机会（$bundleOk=false 直接跳过模块循环），就地落盘
                $p = $null
                try { $p = Write-StageOutLog "mutationTest" ("mvn -f pit-bundle/pom.xml dependency:copy-dependencies (bundle assembly)") $r.Output $r.ExitCode $false ([int]$swMut.Elapsed.TotalSeconds) } catch { }
                $errDetail = ("PIT bundle assembly failed (mvn exit " + $r.ExitCode + ", missing: " + ($missing -join ", ") + ") - run once with network/mirror access; see ADR-018")
                if ($p) { Add-Result "mutationTest" "ERROR" ($errDetail + " | full output: " + $p) }
                else { Add-Result "mutationTest" "ERROR" $errDetail }
            } else {
                [System.IO.File]::WriteAllText($marker, "ok")
                $bundleOk = $true
            }
        }
        if ($bundleOk) {
            $mutTimeout = 1800; if ($mutStage.timeoutSeconds) { $mutTimeout = [int]$mutStage.timeoutSeconds }
            $mutExc = "*IntegrationTest"; if ($mutStage.excludedTestClasses) { $mutExc = $mutStage.excludedTestClasses }
            $killTh = 60.0; if ($cfg.thresholds -and $cfg.thresholds.mutationKillRate) { $killTh = [double]$cfg.thresholds.mutationKillRate }
            if (-not (Test-Path $javaExe)) {
                Add-Result "mutationTest" "ERROR" ("java executable not found: " + $javaExe + " (check JAVA_HOME / toolchain.javaHome)")
            } else {
            $modRows = @(); $badMods = @()
            foreach ($m in @($mutStage.modules)) {
                $modDir = Join-Path $RepoRoot $m.moduleDir
                if (-not (Test-Path (Join-Path $modDir "target\classes"))) {
                    $badMods += ($m.name + ": classes not found (compile stage must run first)"); continue
                }
                $cpCmd = "/c " + $toolMavenBin + " dependency:build-classpath -Dmdep.outputFile=target/pit-cp.txt -B -ntp -q"
                $rCp = Invoke-MetricProcess "cmd.exe" $cpCmd $modDir 300 "mutationTest.classpath"
                if ($rCp.ExitCode -ne 0) {
                    # CI 适配（DB-07 V1.2）：CI 工作流只跑 compile/test，不会把兄弟模块 SNAPSHOT 装进
                    # 本地仓库，dependency:build-classpath 因此解析失败（V4 第二轮实证：did 找不到
                    # common-*/std-adapter 的 2.0.0-SNAPSHOT）。先以 reactor 方式安装该模块及其依赖
                    # （-am，跳过测试）再重试一次；判定语义不变——仍以最终 classpath 与 PIT 结论为准，
                    # 重试后仍失败照记 classpath failed。
                    $mutLogs += ("==== " + $m.name + " classpath (first attempt) ====" + [Environment]::NewLine + $rCp.Output)
                    $prepArgs = "/c " + $toolMavenBin + " -B -ntp -q -DskipTests -pl " + $m.moduleDir + " -am install"
                    $rPrep = Invoke-MetricProcess "cmd.exe" $prepArgs $RepoRoot 1800 ("mutationTest." + $m.name + ".classpath-prep")
                    $mutLogs += ("==== " + $m.name + " classpath prep (install -am, exit " + $rPrep.ExitCode + ") ====" + [Environment]::NewLine + $rPrep.Output)
                    if ($rPrep.ExitCode -ne 0) { $badMods += ($m.name + ": classpath generation failed (module install -am exit " + $rPrep.ExitCode + ")"); continue }
                    $rCp = Invoke-MetricProcess "cmd.exe" $cpCmd $modDir 300 "mutationTest.classpath"
                }
                if ($rCp.ExitCode -ne 0) { $badMods += ($m.name + ": classpath generation failed"); $mutLogs += ("==== " + $m.name + " classpath ====" + [Environment]::NewLine + $rCp.Output); continue }
                $cpText = ([System.IO.File]::ReadAllText((Join-Path $modDir "target\pit-cp.txt"), [System.Text.Encoding]::UTF8)).Trim()
                $fullCp = "target/classes;target/test-classes;" + $cpText + ";" + ((Join-Path $RepoRoot "target\precheck\pit-bundle") + "\*")
                $mutTargetTests = "com.ctds.*"; if ($mutStage.targetTests) { $mutTargetTests = $mutStage.targetTests }
                $pitArgs = "-cp `"" + $fullCp + "`" org.pitest.mutationtest.commandline.MutationCoverageReport --reportDir=target/pit-reports --outputFormats=XML,CSV --targetClasses=`"" + $m.targetPackages + "`" --targetTests=`"" + $mutTargetTests + "`" --excludedTestClasses=`"" + $mutExc + "`" --sourceDirs=`"" + $m.sourceDir + "`""
                $r = Invoke-MetricProcess $javaExe $pitArgs $modDir $mutTimeout ("mutationTest." + $m.name)
                $xmlPath = Join-Path $modDir "target\pit-reports\mutations.xml"
                if ($r.ExitCode -eq -1) {
                    $badMods += ($m.name + ": timed out after " + $mutTimeout + "s"); $mutLogs += ("==== " + $m.name + " (timed out) ====" + [Environment]::NewLine + $r.Output); continue
                }
                if (-not (Test-Path $xmlPath)) {
                    $why = (($r.Output -split "`r?`n") | Where-Object { $_ -match "PitHelpError|SEVERE" } | Select-Object -First 1)
                    $badMods += ($m.name + ": no mutation report (exit " + $r.ExitCode + ") " + $why); $mutLogs += ("==== " + $m.name + " (no report) ====" + [Environment]::NewLine + $r.Output); continue
                }
                # PIT 的 mutations.csv 无表头行，改解析 mutations.xml（mutation 元素 detected/status 属性；
                # detected=true 含 KILLED 与 TIMED_OUT，SURVIVED/NO_COVERAGE 计入分母 = 保守口径）
                [xml]$mx = [System.IO.File]::ReadAllText($xmlPath, [System.Text.Encoding]::UTF8)
                $all = @($mx.mutations.mutation)
                $total = $all.Count
                $detected = @($all | Where-Object { $_.detected -eq "true" }).Count
                if ($total -eq 0) { $badMods += ($m.name + ": empty mutation report"); continue }
                $score = [math]::Round(100.0 * $detected / $total, 2)
                $modRows += ($m.name + " " + $score + "% (" + $detected + "/" + $total + ")")
                if ($score -lt $killTh) { $badMods += ($m.name + " " + $score + "% < " + $killTh + "%") }
            }
            $detail = ("kill rate thresholds>=" + $killTh + "% [" + ($modRows -join ", ") + "]")
            $mutFailDetail = $null
            if ($badMods.Count -gt 0) { $mutFailDetail = ($detail + " - " + ($badMods -join "; ")) }
            elseif ($modRows.Count -eq 0) { $mutFailDetail = "no modules executed (configuration error)" }
            else { Add-Result "mutationTest" "PASS" $detail }
            if ($mutFailDetail) {
                # DB-43 ③：有收集到的原始输出 → 汇总落盘（分隔符区分子运行）；无（如阈值不达标
                # 分支——PIT 报告 JSON/XML 本身即产物）→ 诚实附产物位置，不假装有进程输出可落
                $p = $null
                if ($mutLogs.Count -gt 0) {
                    $sep = [Environment]::NewLine + "----------------------------------------" + [Environment]::NewLine
                    try { $p = Write-StageOutLog "mutationTest" "pitest (bundle / classpath / module sub-runs; exitCode -1 = aggregated multi-source)" ("# aggregated raw outputs below (separated), each prefixed with its sub-run name" + [Environment]::NewLine + ($mutLogs -join $sep)) -1 $false ([int]$swMut.Elapsed.TotalSeconds) } catch { }
                }
                if ($p) { Add-Result "mutationTest" "FAIL" ($mutFailDetail + " | full output: " + $p) }
                else { Add-Result "mutationTest" "FAIL" ($mutFailDetail + " | no raw process output to archive (PIT reports themselves are the artifacts: see each module target/pit-reports/)") }
            }
            }
        }
    }
}

# ----- sast（DB-07 V1.2 §3-ter：免 Docker 形态——semgrep 原生 CLI 优先〔CI 主路径〕，docker 为本地回退） -----
# 判定与解析只有一份（下方统一 JSON 段）；两种载体仅"如何启动 semgrep"不同，扫描面与五态语义零改动。
$sastStage = $cfg.stages.sast
if ($sastStage -and $sastStage.enabled) {
    $semgrepCmd = Get-Command semgrep -ErrorAction SilentlyContinue
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    $sastEngine = ""
    $sastEngineErr = ""
    if ($semgrepCmd) {
        $sastEngine = "native"
        $semgrepExe = $semgrepCmd.Source
    } elseif ($dockerCmd) {
        $sastEngine = "docker"
        $image = $sastStage.image
        # 探测不得在 $ErrorActionPreference=Stop 下中止整轮：PS 5.1 中 native stderr 经 2>&1 会变成
        # ErrorRecord 被抛出（V4 首跑 line 730 整轮中止的根因）。经 cmd.exe 内部重定向吞掉 stderr，
        # 只按 $LASTEXITCODE 判定；文案同时点明 daemon 不可达 / 镜像缺失两种可能，不猜测具体哪一种。
        $probeCode = 1
        try {
            cmd.exe /c ("docker image inspect " + $image + " >nul 2>&1")
            $probeCode = $LASTEXITCODE
        } catch { $probeCode = 1 }
        if ($probeCode -ne 0) {
            $sastEngine = ""
            $sastEngineErr = ("docker daemon unreachable or image not present locally: " + $image + " - start Docker / pull the image manually first (supply-chain leave-trace, no auto-pull; see ADR-018)")
        }
    } else {
        $sastEngineErr = "neither semgrep CLI nor docker found on PATH (required for semgrep stage)"
    }
    if ($sastEngine -eq "") {
        Add-Result "sast" "ERROR" $sastEngineErr
    } else {
        $ruleset = $sastStage.ruleset
            $scanTemp = Join-Path $env:TEMP ("ctds-sast-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
            New-Item -ItemType Directory -Path $scanTemp -Force | Out-Null
            $copied = @()
            foreach ($root in @($sastStage.scanRoots)) {
                # 组根（services/common）下枚举模块级源集 + 组根直属源集（std-adapter）两种形态都支持
                $srcDirs = @()
                $direct = Join-Path $RepoRoot ($root + "\src\main")
                if (Test-Path $direct) { $srcDirs += $direct }
                $mods = Get-ChildItem (Join-Path $RepoRoot $root) -Directory -ErrorAction SilentlyContinue
                foreach ($mod in $mods) {
                    $mpath = Join-Path $mod.FullName "src\main"
                    if (Test-Path $mpath) { $srcDirs += $mpath }
                }
                foreach ($src in $srcDirs) {
                    $rel = Get-RelRepoPath $src
                    $dst = Join-Path $scanTemp $rel
                    New-Item -ItemType Directory -Path (Split-Path -Parent $dst) -Force | Out-Null
                    Copy-Item $src $dst -Recurse -Force
                    $copied += $rel
                }
            }
            if ($copied.Count -eq 0) {
                Add-Result "sast" "ERROR" "no Java main source sets found to scan (scanRoots misconfigured?)"
            } else {
                $sastTimeout = 900; if ($sastStage.timeoutSeconds) { $sastTimeout = [int]$sastStage.timeoutSeconds }
                $swSast = [System.Diagnostics.Stopwatch]::StartNew()
                if ($sastEngine -eq "native") {
                    # 与 docker 形态同一参数集（ruleset/json/quiet 不变）；扫描副本路径可能含空格，必须加引号
                    # （同类坑见 S15 空格探针：未加引号会被命令行解析截断）
                    $nativeArgs = "--config " + $ruleset + " --json --quiet `"" + $scanTemp + "`""
                    $r = Invoke-MetricProcess $semgrepExe $nativeArgs $RepoRoot $sastTimeout "sast"
                } else {
                    $dockerArgs = "/c docker run --rm -v `"" + $scanTemp + ":/src`" " + $image + " semgrep --config " + $ruleset + " --json --quiet /src"
                    $r = Invoke-MetricProcess "cmd.exe" $dockerArgs $RepoRoot $sastTimeout "sast"
                }
                # DB-43 ③：凡该段判定 FAIL/ERROR（超时 / exit≠0 / JSON 不可解析 / scan errors /
                # findings>0）→ 落盘完整原始输出，报告行尾附路径（PASS 不落）
                $sastFail = $false
                $sastVerdict = "FAIL"
                $sastDetail = ""
                if ($r.ExitCode -eq -1) {
                    $sastFail = $true
                    $sastDetail = $r.Output
                } elseif ($r.ExitCode -ne 0) {
                    $tail = (($r.Output -split "`r?`n") | Where-Object { $_.Trim() -ne "" } | Select-Object -Last 4) -join " | "
                    $sastFail = $true
                    $sastVerdict = "ERROR"
                    $sastDetail = ("semgrep run failed (exit " + $r.ExitCode + "): " + $tail)
                } else {
                    try { $sj = ConvertFrom-Json ($r.Output -join " ") } catch { $sj = $null }
                    if (-not $sj) {
                        $sastFail = $true
                        $sastVerdict = "ERROR"
                        $sastDetail = "semgrep returned unparseable output (no JSON)"
                    } else {
                        $hits = @($sj.results)
                        $scanErrs = @($sj.errors)
                        if ($scanErrs.Count -gt 0) {
                            # 部分文件扫描失败时 results 可能为空——errors 非空即 ERROR，防"扫不动=0 热点"假绿
                            $e0 = ($scanErrs | Select-Object -First 1).message
                            if ($e0) { $e0 = $e0.ToString() }
                            if ($e0 -and $e0.Length -gt 120) { $e0 = $e0.Substring(0, 120) }
                            $sastFail = $true
                            $sastVerdict = "ERROR"
                            $sastDetail = ("semgrep reported " + $scanErrs.Count + " scan error(s) [" + $e0 + "] - results incomplete, not a clean 0-finding pass")
                        } elseif ($hits.Count -eq 0) {
                            Add-Result "sast" "PASS" ("semgrep " + $ruleset + ": 0 findings across " + ($copied -join ", ") + " main source sets")
                        } else {
                            $first = ($hits | Select-Object -First 3 | ForEach-Object { $_.check_id + "@" + $_.path }) -join "; "
                            $sastFail = $true
                            $sastDetail = ($hits.Count + " finding(s) [" + $first + "] - review before merge")
                        }
                    }
                }
                if ($sastFail) {
                    $p = $null
                    $sastCmdLabel = "semgrep --config " + $ruleset + " --json --quiet <scanTemp>"
                    if ($sastEngine -eq "docker") { $sastCmdLabel = "docker run --rm -v <scanTemp>:/src " + $image + " semgrep --config " + $ruleset + " /src" }
                    try { $p = Write-StageOutLog "sast" $sastCmdLabel $r.Output $r.ExitCode ($r.ExitCode -eq -1) ([int]$swSast.Elapsed.TotalSeconds) } catch { }
                    if ($p) { Add-Result "sast" $sastVerdict ($sastDetail + " | full output: " + $p) }
                    else { Add-Result "sast" $sastVerdict $sastDetail }
                }
            }
            Remove-Item -Path $scanTemp -Recurse -Force -ErrorAction SilentlyContinue
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
    # DB-07 V1.2：兜底路径的目录可能不存在（CI 新鲜克隆 + 早期阶段中止致报告探针未跑）——
    # 先建目录再写，避免"报告写不出"把整轮可核证据丢掉。仅兜底分支建目录；显式 -ReportPath
    # 不建（S4 不可写路径探针的 ERROR/exit 2 语义保持不变）。
    $fallbackDir = Join-Path $PSScriptRoot "reports"
    if (-not (Test-Path $fallbackDir)) { New-Item -ItemType Directory -Path $fallbackDir -Force | Out-Null }
    $reportTarget = Join-Path $fallbackDir ("gate-report-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".md")
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
