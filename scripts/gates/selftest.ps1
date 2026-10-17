# C-TDS gate runner selftest (WBS-2.2.7 deliverable #5, round-2 fix R7).
# Purpose: prove - reproducibly and in seconds, not by a one-off manual probe - that the runner
# really keeps its robustness promises:
#   S1 untracked locked file      -> SKIP row, later stages still run, report produced, exit 0
#   S2 git-TRACKED locked file    -> ERROR row, exit 2 (a committed file must never silently escape)
#   S3 invalid JAVA_HOME          -> ERROR rows + exit 2, plus a REVERSE PROBE (same fixture, toolchain
#                                    stages off -> exit 0) proving the ERROR comes from the injected fault
#   S4 unwritable report path     -> ERROR row + exit 2, and the report is still printed to stdout
#   S5 exclude entry matching a git-tracked path -> ERROR row + exit 2 (the R1 machine guard)
#   S6 fake secret inside docs/logs/*.md -> FAIL row naming that file (the DB-16 regression anchor:
#                                    committed dev logs MUST be scanned again)
#   S7 a stage throwing (unreadable ADR file) -> the outer fallback records a runner ERROR row, the
#                                    report is STILL produced and the exit code is 2 (never 1)
#   S8 devLogNaming FAIL + invalid JAVA_HOME ERRORs coexist -> exit 1, verdict "RED (also ...)"
#                                    (the R2 anchor, DB-17 N1: a real red light must never be masked
#                                    by environment/config errors - now asserted on every run)
#   S9 Maven goals containing an illegal character -> that stage reports ERROR "NOT executed"
#                                    (the R5 anchor, DB-17 N2: a command is never launched from an
#                                    illegal config; regression of the allowlist order fails here)
#   S10 frontendTypeCheck wiring (config V1.3, 清债卡4) -> stub typecheck exit 1 = FAIL/RED/exit 1,
#                                    exit 0 = PASS/GREEN/exit 0 (red/green double probe: the npm-loop
#                                    stage is genuinely executed and a type failure can never be green)
#   S11 metric stages fail-visible (config V1.4, 清债卡1/DB-06) -> coverage/mutationTest/sast in a
#                                    bare fixture: FAIL/ERROR rows + verdict RED, NEVER a silent PASS;
#                                    coverage threshold-polarity probe (10% < 70% must FAIL);
#                                    reverse probe (stages off) -> GREEN (fail-visible, not fail-silent)
#   S11b mvn nonzero-exit branch (清债卡1 复审尾巴) -> stub mvn exits 3 = coverage FAIL naming the
#                                    exit code (the threshold probe's exit-0 stub no longer covers
#                                    the "mvn exit != 0 -> FAIL" branch)
#   S11c sast engine unavailable (DB-07 V1.2 §3-ter) -> stub docker (writes stderr then exits 1):
#                                    sast ERROR row, report STILL written, exit 2, no runner-crash row
#                                    (regression anchor for the V4 first run where a bare
#                                    `docker image inspect 2>&1` aborted the whole gate under
#                                    $ErrorActionPreference=Stop and lost all evidence)
#   S12 mvn success-state log (DB-43 ①) -> stub mvn exit 0: compile PASS AND
#                                    %TEMP%\ctds-gate-compile-out.log EXISTS, carrying the
#                                    stage/command/exitCode summary + full original output
#                                    (structural fix of the "0-byte log" lesson: success is
#                                    evidenced too; the PASS report row keeps its original format)
#   S13 npm failure full-output log (DB-43 ③) -> stub npm exit 1: frontendTypeCheck FAIL, out.log
#                                    carries exitCode + stdout/stderr markers (the DB-44 "error
#                                    text unobtainable" loss scenario's positive anchor) and the
#                                    report FAIL row carries the full-output path
#   S14 timeout process-tree cleanup (DB-43 Q1-A / 跟踪-23) -> stub mvn starts a grandchild then
#                                    hangs with timeoutSeconds=2: FAIL "timed out", out.log
#                                    "(TIMED OUT)", grandchild PID GONE (taskkill /T; the old
#                                    Kill()-only behaviour leaves the grandchild alive = red)
#   S15 gateSelfTest executor wiring (DB-07 编排师裁定 C, config V1.8) -> fixture with
#                                    gateSelfTest.enabled=true + a stub selftest script: exit 0 =
#                                    gateSelfTest PASS row + exit 0 (PASS detail carries the parsed
#                                    "SELFTEST PASS - N assertion(s)" summary), a spaced-path stub
#                                    (tool under "scripts/gates dir/") anchors the -File quoting,
#                                    exit 1 = FAIL row + exit 1 (self-check FAILED + full-output
#                                    path), a hanging stub with timeoutSeconds=2 = FAIL "timed out
#                                    after 2s" (executor timeout branch), exit 2 = ERROR row + exit 2
#                                    (harness error, not a code verdict), missing tool = ERROR row +
#                                    exit 2; PASS probes also anchor the first-stage row order, and a
#                                    closing guard scan asserts every non-S15 fixture config keeps the
#                                    stage disabled (recursion guard as a machine check). The recursion
#                                    guard (fixtures force the stage off) is what makes a stub - not the
#                                    real selftest - the right probe here; the real chain is covered
#                                    by the standalone baseline run and the post-enable full gate (V3).
# How: every scenario builds a hermetic fixture repo under %TEMP% (git init + git add, cheap stages
# only), generates its config FROM THE REAL gates-config.json (so the real exclude list is what gets
# exercised), runs scripts/gates/run-gates.ps1 against it and asserts on the report file / stdout /
# exit code. Nothing outside %TEMP% is touched; the fixtures are deleted when all scenarios pass.
# DB-07 (config V1.8): every fixture FORCES gateSelfTest off (recursion guard - with the stage
# enabled in the real config, a fixture inheriting it would make the runner invoke selftest again,
# forever); S15 is the only scenario that turns it back on, with a stub in place of the real script.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\selftest.ps1 [-KeepFixture]
# Exit codes: 0 = all scenarios passed, 1 = assertion failure, 2 = harness error (fixture/env problem).
param(
    [switch]$KeepFixture
)
$ErrorActionPreference = "Continue"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$scriptDir = $PSScriptRoot
$runner = Join-Path $scriptDir "run-gates.ps1"
$realConfig = Join-Path $scriptDir "gates-config.json"
$script:assertions = 0
$script:failures = 0

function Assert-True([bool]$condition, [string]$label) {
    $script:assertions++
    if ($condition) { Write-Output ("    [ok]   " + $label) }
    else { $script:failures++; Write-Output ("    [FAIL] " + $label) }
}
function Assert-Contains([string]$text, [string]$needle, [string]$label) {
    if ($text -eq $null) { $text = "" }
    Assert-True ($text.Contains($needle)) ($label + "  <expected: " + $needle + ">")
}
function Assert-NotContains([string]$text, [string]$needle, [string]$label) {
    if ($text -eq $null) { $text = "" }
    Assert-True (-not $text.Contains($needle)) ($label + "  <must not contain: " + $needle + ">")
}
function Read-Report([string]$path) {
    if (-not (Test-Path $path)) { return "" }
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

# ---------- fixture helpers ----------
function New-Fixture([string]$name) {
    $dir = Join-Path $script:workDir ("fixture-" + $name)
    foreach ($sub in @("", "docs", "docs\adr", "docs\logs", "src")) {
        $p = $dir
        if ($sub -ne "") { $p = Join-Path $dir $sub }
        if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
    }
    [System.IO.File]::WriteAllText((Join-Path $dir "AGENTS.md"), "# fixture AGENTS (selftest)" + [Environment]::NewLine, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $dir "README.md"), "# fixture README (selftest)" + [Environment]::NewLine, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $dir ".gitignore"), "node_modules/" + [Environment]::NewLine, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $dir "docs\03-charter.md"), "# fixture charter" + [Environment]::NewLine, $utf8NoBom)
    $adrFields = "背景 决策 理由 备选 业务影响说明 影响范围 可替换性"
    [System.IO.File]::WriteAllText((Join-Path $dir "docs\adr\ADR-001-fixture.md"), ("# ADR-001 fixture - " + $adrFields) + [Environment]::NewLine, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $dir "docs\logs\Ctds-fixture-26-01-01-0000.md"), "# fixture dev log" + [Environment]::NewLine, $utf8NoBom)
    return $dir
}
function Initialize-FixtureGit([string]$dir) {
    & git -C $dir init --quiet 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw ("fixture git init failed in " + $dir) }
    & git -C $dir add -A 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw ("fixture git add failed in " + $dir) }
}
function Set-StageEnabled($cfgObj, [string]$stageName, [bool]$enabled) {
    $prop = $cfgObj.stages.PSObject.Properties[$stageName]
    if (-not $prop) { throw ("stage not found in gates-config.json: " + $stageName) }
    $prop.Value.enabled = $enabled
}
# Cheap config = the REAL config with only the toolchain/frontend stages switched off (they need a real
# project and minutes). Optionally force the three Maven stages on (S3) and/or inject extra excludes (S5).
function New-FixtureConfig([string]$dir, [string]$fileName, [bool]$mavenStages, [string]$javaHome, [string[]]$extraExclude) {
    $cfgText = [System.IO.File]::ReadAllText($realConfig, [System.Text.Encoding]::UTF8)
    $cfgObj = ConvertFrom-Json -InputObject $cfgText
    foreach ($n in @("compile", "lint", "unitTest", "frontendTypeCheck", "frontendLint", "frontendTest", "frontendE2E", "coverage", "mutationTest", "sast")) { Set-StageEnabled $cfgObj $n $false }
    # DB-07（编排师裁定 C，config V1.8）：fixture 一律强制 gateSelfTest 关闭——真实 config 翻位
    # （enabled=true）后 fixture 不再继承 true，杜绝"门禁→selftest→fixture 门禁→selftest"无限递归；
    # S15 在自己的 fixture 里显式改回 true 并配 stub selftest，验证执行器接线（递归守卫正面锚）。
    # 同步补回 status:PENDING-SELFTEST：真实 config 启用时已按 ADR-018 V1.4 先例移除该字段
    # （coverage/mutationTest/sast 等同径），而 PENDING 循环要求 enabled=false 且 status 形如
    # PENDING* 才出可见行——fixture 须显式重建"关闭态"才能演练 S1 的"关闭态可见性"锚。
    Set-StageEnabled $cfgObj "gateSelfTest" $false
    # 补属性再设值（同 S14 timeoutSeconds 模式）：ConvertFrom-Json 对象直接赋不存在的属性在 PS 5.1 抛错
    Add-Member -InputObject $cfgObj.stages.gateSelfTest -MemberType NoteProperty -Name status -Value "PENDING-SELFTEST" -Force
    if ($mavenStages) {
        foreach ($n in @("compile", "lint", "unitTest")) { Set-StageEnabled $cfgObj $n $true }
        $cfgObj.toolchain.javaHome = $javaHome
    }
    if ($extraExclude -and $extraExclude.Count -gt 0) { $cfgObj.secretsExcludePaths = @($cfgObj.secretsExcludePaths) + $extraExclude }
    $path = Join-Path $dir $fileName
    [System.IO.File]::WriteAllText($path, ($cfgObj | ConvertTo-Json -Depth 10), $utf8NoBom)
    return $path
}
function Invoke-Runner([string]$repo, [string]$cfgPath, [string]$reportPath, [string]$scenario, [switch]$detached) {
    $outFile = Join-Path $script:workDir ($scenario + ".out.txt")
    $runnerArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $runner, "-RepoRoot", $repo, "-ConfigPath", $cfgPath, "-Scenario", $scenario)
    if ($reportPath -ne "") { $runnerArgs += @("-ReportPath", $reportPath) }
    if ($detached) {
        # S14 专用（DB-43）：超时注入的孙进程在旧实现下会幸存并继承本重定向句柄，
        # `& powershell *> $outFile` 将永远等不到 EOF（红相期挂起）。Start-Process +
        # WaitForExit 只等待直接子进程退出；不重定向 std（S14 断言全在报告文件上，
        # 运行器正常路径对 stdout 零输出）——带 -RedirectStandardOutput 时
        # Start-Process -PassThru 的 ExitCode 不刷新（已实测复证），故此处不重定向。
        # Start-Process 不会自动给含空格的参数加引号，这里逐元素补齐。
        $quoted = @()
        foreach ($a in $runnerArgs) { if ($a -match '\s') { $quoted += ('"' + $a + '"') } else { $quoted += $a } }
        $sp = Start-Process -FilePath "powershell" -ArgumentList $quoted -WindowStyle Hidden -PassThru
        $sp.WaitForExit()
        $code = $sp.ExitCode
    } else {
        & powershell @runnerArgs *> $outFile
        $code = $LASTEXITCODE
    }
    $text = ""
    if (Test-Path $outFile) { try { $text = Get-Content -Path $outFile -Raw } catch { $text = "" } }
    return [pscustomobject]@{ ExitCode = $code; Stdout = $text }
}

# ================= harness =================
if (-not (Test-Path $runner)) { Write-Output ("[HARNESS-ERROR] runner not found: " + $runner); exit 2 }
if (-not (Test-Path $realConfig)) { Write-Output ("[HARNESS-ERROR] config not found: " + $realConfig); exit 2 }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Write-Output "[HARNESS-ERROR] git not found on PATH (fixtures need a real index)"; exit 2 }
if (-not (Get-Command powershell -ErrorAction SilentlyContinue)) { Write-Output "[HARNESS-ERROR] powershell not found on PATH"; exit 2 }
$script:workDir = Join-Path $env:TEMP ("ctds-gates-selftest-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $script:workDir -Force | Out-Null
# DB-43：%TEMP%\ctds-gate-<stage>-out.log 是固定名（= 最近一次该阶段运行，含 selftest fixture
# 运行——已知特性，README 变更条目有述）。开场统一清理：上一次运行（或上一次 selftest）的
# 残留会让本次"out.log 存在性"类断言假绿，故每次开场先清到白纸状态。
foreach ($stale in (Get-ChildItem $env:TEMP -Filter "ctds-gate-*-out.log" -ErrorAction SilentlyContinue)) {
    Remove-Item $stale.FullName -Force -ErrorAction SilentlyContinue
}
Write-Output ("C-TDS gate runner selftest - scenario workdir: " + $script:workDir)
Write-Output ("runner: " + $runner)

try {

# ---------- S1: untracked locked file -> SKIP, gate not aborted ----------
Write-Output ""
Write-Output "S1 [untracked locked file] expect: SKIP row, later stages run, report produced, exit 0"
$fix = New-Fixture "s1"
Initialize-FixtureGit $fix
$untrackedLocked = Join-Path $fix "docs\zz-gate-lock-untracked.txt"
[System.IO.File]::WriteAllText($untrackedLocked, "selftest fixture: untracked and locked" + [Environment]::NewLine, $utf8NoBom)
$cfg = New-FixtureConfig $fix "cfg-s1.json" $false "" $null
$rep = Join-Path $script:workDir "s1-report.md"
$lockStream = [System.IO.File]::Open($untrackedLocked, 'Open', 'Read', 'None')
try {
    $r = Invoke-Runner $fix $cfg $rep "selftest-s1-untracked-locked"
    Assert-True ($r.ExitCode -eq 0) ("S1 exit code 0 (got " + $r.ExitCode + ")")
    $repText = Read-Report $rep
    Assert-Contains $repText "secretsScan.skipped" "S1 report carries the SKIP row"
    Assert-Contains $repText "zz-gate-lock-untracked.txt" "S1 the skipped file is named in the report"
    Assert-Contains $repText "| structureCheck | PASS |" "S1 later stages still ran (gate was NOT aborted)"
    Assert-Contains $repText "secretsScan.excludeGuard | PASS |" "S1 exclude guard ran and passed"
    Assert-Contains $repText "gateSelfTest | PENDING | PENDING-SELFTEST" "S1 fixture configs (gateSelfTest forced off by the recursion guard) still show the PENDING row"
    Assert-Contains $repText "-> GREEN" "S1 verdict GREEN"
    Assert-Contains $repText "- ExitCode: 0" "S1 report header declares exit code 0 (consistent with the process exit code)"
    Assert-Contains $repText "- Scenario: selftest-s1-untracked-locked" "S1 report records the Scenario label"
    Assert-Contains $repText "- ConfigSha256: " "S1 report records the config hash (third-party can re-verify)"
} finally { $lockStream.Close() }

# ---------- S2: git-tracked locked file -> ERROR (R3) ----------
Write-Output ""
Write-Output "S2 [git-tracked locked file] expect: ERROR row, exit 2 (committed files may never be skipped)"
$fix = New-Fixture "s2"
$trackedLocked = Join-Path $fix "docs\zz-gate-lock-tracked.txt"
[System.IO.File]::WriteAllText($trackedLocked, "selftest fixture: tracked and then locked" + [Environment]::NewLine, $utf8NoBom)
Initialize-FixtureGit $fix
$cfg = New-FixtureConfig $fix "cfg-s2.json" $false "" $null
$rep = Join-Path $script:workDir "s2-report.md"
$lockStream = [System.IO.File]::Open($trackedLocked, 'Open', 'Read', 'None')
try {
    $r = Invoke-Runner $fix $cfg $rep "selftest-s2-tracked-locked"
    Assert-True ($r.ExitCode -eq 2) ("S2 exit code 2 (got " + $r.ExitCode + ")")
    $repText = Read-Report $rep
    Assert-Contains $repText "secretsScan.unreadable | ERROR |" "S2 report carries an ERROR row for the unreadable committed file"
    Assert-Contains $repText "zz-gate-lock-tracked.txt" "S2 the blocked file is named in the report"
    Assert-NotContains $repText "secretsScan.skipped" "S2 a committed file must NOT be downgraded to SKIP"
    Assert-Contains $repText "-> ERROR" "S2 verdict ERROR"
} finally { $lockStream.Close() }

# ---------- S3: invalid JAVA_HOME -> ERROR/2, with reverse probe ----------
Write-Output ""
Write-Output "S3 [invalid JAVA_HOME] expect: ERROR rows, exit 2 + reverse probe (same fixture, stages off -> exit 0)"
$fix = New-Fixture "s3"
Initialize-FixtureGit $fix
$savedJavaHome = $env:JAVA_HOME
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
try {
    $cfg = New-FixtureConfig $fix "cfg-s3-bad.json" $true (Join-Path $fix "no-such-jdk") $null
    $rep = Join-Path $script:workDir "s3-report.md"
    $r = Invoke-Runner $fix $cfg $rep "selftest-s3-invalid-java-home"
    Assert-True ($r.ExitCode -eq 2) ("S3 exit code 2 (got " + $r.ExitCode + ")")
    $repText = Read-Report $rep
    Assert-Contains $repText "| compile | ERROR |" "S3 compile stage reports ERROR"
    Assert-Contains $repText "JAVA_HOME not found" "S3 the ERROR names the actual cause"
    Assert-Contains $repText "-> ERROR (environment/config" "S3 verdict is an environment ERROR, not a code verdict"
    # 反向探针：同一 fixture、同一 harness，只把工具链阶段关掉 -> 必须 GREEN/0。
    # 若这里也失败，说明失败来自 harness/环境本身，而不是被注入的故障。
    $cfgControl = New-FixtureConfig $fix "cfg-s3-control.json" $false "" $null
    $repControl = Join-Path $script:workDir "s3-control-report.md"
    $rControl = Invoke-Runner $fix $cfgControl $repControl "selftest-s3-control-probe"
    Assert-True ($rControl.ExitCode -eq 0) ("S3 reverse probe exit code 0 (got " + $rControl.ExitCode + ")")
    Assert-Contains (Read-Report $repControl) "-> GREEN" "S3 reverse probe verdict GREEN (so the S3 ERROR is attributable to the injected fault)"
} finally {
    if ($savedJavaHome) { $env:JAVA_HOME = $savedJavaHome }
}

# ---------- S4: unwritable report path -> ERROR/2, report still printed ----------
Write-Output ""
Write-Output "S4 [unwritable report path] expect: ERROR row, exit 2, report still printed to stdout"
$fix = New-Fixture "s4"
Initialize-FixtureGit $fix
$cfg = New-FixtureConfig $fix "cfg-s4.json" $false "" $null
$badReport = Join-Path $fix "no-such-dir\gate-report.md"
$r = Invoke-Runner $fix $cfg $badReport "selftest-s4-unwritable-report"
Assert-True ($r.ExitCode -eq 2) ("S4 exit code 2 (got " + $r.ExitCode + ")")
Assert-Contains $r.Stdout "runner.reportPath | ERROR |" "S4 stdout report carries the ERROR row"
Assert-Contains $r.Stdout "- ExitCode: 2" "S4 the printed report declares the real exit code"
Assert-Contains $r.Stdout "[WARN] report could not be written" "S4 stdout warns that no report file exists"
Assert-True (-not (Test-Path $badReport)) "S4 no report file was produced (as expected)"

# ---------- S5: exclude entry hiding a git-tracked path -> ERROR (R1 guard) ----------
Write-Output ""
Write-Output "S5 [exclude entry hits a tracked path] expect: excludeGuard ERROR row, exit 2"
$fix = New-Fixture "s5"
Initialize-FixtureGit $fix
$cfg = New-FixtureConfig $fix "cfg-s5.json" $false "" @("docs/logs")
$rep = Join-Path $script:workDir "s5-report.md"
$r = Invoke-Runner $fix $cfg $rep "selftest-s5-exclude-hides-tracked"
Assert-True ($r.ExitCode -eq 2) ("S5 exit code 2 (got " + $r.ExitCode + ")")
$repText = Read-Report $rep
Assert-Contains $repText "secretsScan.excludeGuard | ERROR |" "S5 the guard refuses the exclude entry"
Assert-Contains $repText "hit git-TRACKED paths" "S5 the guard explains why"
Assert-Contains $repText "docs/logs/Ctds-fixture-26-01-01-0000.md" "S5 the guard names the file that would have escaped"
Assert-Contains $repText "-> ERROR" "S5 verdict ERROR"

# ---------- S6: a fake secret inside a committed dev log IS scanned (DB-16 regression anchor) ----------
Write-Output ""
Write-Output "S6 [fake secret in docs/logs] expect: FAIL row naming that file, exit 1 (committed dev logs are scanned)"
$fix = New-Fixture "s6"
$logFile = Join-Path $fix "docs\logs\Ctds-fixture-26-01-01-0000.md"
# 这份"假凭据"必须在运行时命中密钥规则，因此按片段拼装而不是整串写进源码——否则门禁会（正确地）
# 把 selftest.ps1 自己判为命中（2026-09-15 第 2 轮自查中确实发生过，即报告 gate-report-20260915-160629.md）。
$fakeCredentialLine = ('pass' + 'word = "Fixture' + 'NotAReal' + 'Secret01"')
[System.IO.File]::WriteAllText($logFile, ("# fixture dev log" + [Environment]::NewLine + $fakeCredentialLine + [Environment]::NewLine), $utf8NoBom)
Initialize-FixtureGit $fix
$cfg = New-FixtureConfig $fix "cfg-s6.json" $false "" $null
$rep = Join-Path $script:workDir "s6-report.md"
$r = Invoke-Runner $fix $cfg $rep "selftest-s6-devlog-scanned"
Assert-True ($r.ExitCode -eq 1) ("S6 exit code 1 (got " + $r.ExitCode + ")")
$repText = Read-Report $rep
Assert-Contains $repText "secretsScan | FAIL | Pattern hit in docs/logs/Ctds-fixture-26-01-01-0000.md" "S6 the committed dev log was actually scanned"
Assert-Contains $repText "-> RED" "S6 verdict RED"

# ---------- S7: a stage throwing -> outer fallback: runner ERROR, report still produced, exit 2 ----------
Write-Output ""
Write-Output "S7 [stage throws - outer fallback] expect: runner ERROR row, exit 2, report STILL produced"
$fix = New-Fixture "s7"
Initialize-FixtureGit $fix
# adrFieldsCheck 读 ADR 内容时没有 try 保护 -> 锁定该文件即可稳定触发脚本级兜底路径
$lockedAdr = Join-Path $fix "docs\adr\ADR-001-fixture.md"
$cfg = New-FixtureConfig $fix "cfg-s7.json" $false "" $null
$rep = Join-Path $script:workDir "s7-report.md"
$lockStream = [System.IO.File]::Open($lockedAdr, 'Open', 'Read', 'None')
try {
    $r = Invoke-Runner $fix $cfg $rep "selftest-s7-runner-crash"
    Assert-True ($r.ExitCode -eq 2) ("S7 exit code 2 (got " + $r.ExitCode + ")")
    Assert-True (Test-Path $rep) "S7 the report file was STILL produced (evidence is never lost on a crash)"
    $repText = Read-Report $rep
    Assert-Contains $repText "runner | ERROR |" "S7 the fallback records an ERROR row (never a code FAIL)"
    Assert-Contains $repText "runner aborted by unexpected error" "S7 the crash reason is visible in the report"
    Assert-Contains $repText "-> ERROR (runner crashed" "S7 verdict says the runner crashed - not that the code is bad"
    Assert-Contains $repText "- ExitCode: 2" "S7 the report declares exit code 2 (consistent with the process)"
} finally { $lockStream.Close() }

# ---------- S8: FAIL + ERROR coexist -> RED/1 (R2 anchor, DB-17 N1) ----------
Write-Output ""
Write-Output "S8 [naming FAIL + bad JAVA_HOME ERRORs] expect: exit 1, verdict 'RED (also ...)' - env errors must NOT mask the red light"
$fix = New-Fixture "s8"
Initialize-FixtureGit $fix
# 真红灯：一个不符合命名规则的入库开发日志（devLogNamingCheck FAIL）
[System.IO.File]::WriteAllText((Join-Path $fix "docs\logs\zz-bad-log-name.md"), "# fixture bad log name" + [Environment]::NewLine, $utf8NoBom)
# 环境错误：Maven 阶段启用但 JAVA_HOME 指向不存在路径（compile/lint/unitTest 各记一条 ERROR）。
# 与 S3 同构：必须先接管 $env:JAVA_HOME，否则本机真实 JAVA_HOME 会压过配置里的假路径（真跑 mvn）
$savedJavaHome = $env:JAVA_HOME
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
try {
$cfg = New-FixtureConfig $fix "cfg-s8.json" $true (Join-Path $fix "no-such-jdk") $null
$rep = Join-Path $script:workDir "s8-report.md"
$r = Invoke-Runner $fix $cfg $rep "selftest-s8-fail-plus-error"
Assert-True ($r.ExitCode -eq 1) ("S8 exit code 1 (got " + $r.ExitCode + ")")
$repText = Read-Report $rep
Assert-Contains $repText "| devLogNamingCheck | FAIL |" "S8 the real red light (bad log name) is present"
Assert-Contains $repText "zz-bad-log-name" "S8 the FAIL is attributable to the injected file (not another FAIL source)"
Assert-Contains $repText "| compile | ERROR |" "S8 the environment ERROR (bad JAVA_HOME) is present"
Assert-Contains $repText "JAVA_HOME not found" "S8 the ERROR names its cause"
Assert-Contains $repText "-> RED (also" "S8 verdict is RED with the ERROR count visible (not a masked GREEN/ERROR-only run)"
} finally {
    if ($savedJavaHome) { $env:JAVA_HOME = $savedJavaHome }
}

# ---------- S9: illegal chars in Maven goals -> stage SKIPPED, command NOT executed (R5 anchor, DB-17 N2) ----------
Write-Output ""
Write-Output "S9 [Maven goals with illegal char] expect: compile ERROR 'NOT executed' (a command is never launched from an illegal config)"
$fix = New-Fixture "s9"
Initialize-FixtureGit $fix
$savedJavaHome = $env:JAVA_HOME
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
try {
$cfg = New-FixtureConfig $fix "cfg-s9.json" $true (Join-Path $fix "no-such-jdk") $null
# 在派生配置上注入 cmd.exe 元字符（'&' 不在 allowlist 内）；假 JAVA_HOME 使该断言同时对
# "allowlist 检查被移除/被挪到 JAVA_HOME 检查之后"两类回归敏感（任一回归都会失去 NOT executed 行文）
$cfgObj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg, [System.Text.Encoding]::UTF8))
$cfgObj.stages.compile.goals = "clean package & whoami"
[System.IO.File]::WriteAllText($cfg, ($cfgObj | ConvertTo-Json -Depth 10), $utf8NoBom)
$rep = Join-Path $script:workDir "s9-report.md"
$r = Invoke-Runner $fix $cfg $rep "selftest-s9-illegal-goals"
Assert-True ($r.ExitCode -eq 2) ("S9 exit code 2 (got " + $r.ExitCode + ")")
$repText = Read-Report $rep
Assert-Contains $repText "| compile | ERROR |" "S9 compile reports ERROR (stage refused)"
Assert-Contains $repText "stage SKIPPED, command NOT executed" "S9 the R5 promise: no command is launched from an illegal config"
Assert-Contains $repText "stages.compile.goals" "S9 the ERROR names the offending config field"
} finally {
    if ($savedJavaHome) { $env:JAVA_HOME = $savedJavaHome }
}

# ---------- S10: frontendTypeCheck wiring (清债卡4, config V1.3) - red/green double probe ----------
# The stage must be genuinely executed via the npm loop (config goals "run typecheck" -> fixture
# package.json script). vue-tsc itself is not installed in fixtures, so the probe substitutes a
# stub script whose exit code stands in for the type-check verdict:
#   red probe   (exit 1) -> frontendTypeCheck FAIL, verdict RED, exit 1  (a type failure can NEVER be green)
#   green probe (exit 0) -> frontendTypeCheck PASS, verdict GREEN, exit 0 (wiring works end to end)
Write-Output ""
Write-Output "S10 [frontendTypeCheck wiring] expect: stub typecheck exit 1 -> FAIL/RED/1; exit 0 -> PASS/GREEN/0"
$fix = New-Fixture "s10"
Initialize-FixtureGit $fix
$fixFrontend = Join-Path $fix "frontend"
New-Item -ItemType Directory -Path $fixFrontend -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $fixFrontend "exit1.js"), "process.exit(1)" + [Environment]::NewLine, $utf8NoBom)
[System.IO.File]::WriteAllText((Join-Path $fixFrontend "exit0.js"), "process.exit(0)" + [Environment]::NewLine, $utf8NoBom)
$pkgJson = Join-Path $fixFrontend "package.json"
$cfg = New-FixtureConfig $fix "cfg-s10.json" $false "" $null
# New-FixtureConfig turns every frontend stage off (S1-S9 fixtures have no frontend/package.json);
# S10 is the one scenario that exercises this stage, so re-enable it on the derived config (S9 pattern).
$cfgObj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg, [System.Text.Encoding]::UTF8))
$cfgObj.stages.frontendTypeCheck.enabled = $true
[System.IO.File]::WriteAllText($cfg, ($cfgObj | ConvertTo-Json -Depth 10), $utf8NoBom)
$rep = Join-Path $script:workDir "s10-report.md"
# red probe: the stub typecheck exits 1 -> the gate must turn RED (never silently green)
[System.IO.File]::WriteAllText($pkgJson, ('{"name":"fixture-frontend","version":"0.0.0","private":true,"scripts":{"typecheck":"node exit1.js"}}' + [Environment]::NewLine), $utf8NoBom)
$r = Invoke-Runner $fix $cfg $rep "selftest-s10-frontend-typecheck-red"
Assert-True ($r.ExitCode -eq 1) ("S10 red probe exit code 1 (got " + $r.ExitCode + ")")
$repText = Read-Report $rep
Assert-Contains $repText "| frontendTypeCheck | FAIL |" "S10 red probe: the failing typecheck turns the stage FAIL"
Assert-Contains $repText "npm run typecheck exit 1" "S10 red probe: the FAIL names the actual command and exit code"
Assert-Contains $repText "-> RED" "S10 red probe verdict RED"
# green probe: same fixture, stub exits 0 -> PASS and GREEN (so the red above is attributable to the stub)
[System.IO.File]::WriteAllText($pkgJson, ('{"name":"fixture-frontend","version":"0.0.0","private":true,"scripts":{"typecheck":"node exit0.js"}}' + [Environment]::NewLine), $utf8NoBom)
$repGreen = Join-Path $script:workDir "s10-green-report.md"
$rGreen = Invoke-Runner $fix $cfg $repGreen "selftest-s10-frontend-typecheck-green"
Assert-True ($rGreen.ExitCode -eq 0) ("S10 green probe exit code 0 (got " + $rGreen.ExitCode + ")")
$repGreenText = Read-Report $repGreen
Assert-Contains $repGreenText "| frontendTypeCheck | PASS |" "S10 green probe: the passing typecheck turns the stage PASS"
Assert-Contains $repGreenText "-> GREEN" "S10 green probe verdict GREEN (so the S10 red is attributable to the injected type failure)"

# ---------- S11: quality metric stages wiring (DB-06/清债卡1, config V1.4) - fail-visible + reverse probe ----------
# The three metric stages (coverage / mutationTest / sast) must never pass silently in a fixture that
# cannot support them: a stub mvn.cmd on PATH (fixture dir prepended to PATH, restored in finally)
# exits 0, so coverage reaches its parse/threshold branch and must FAIL on the hand-crafted
# LOW-coverage jacoco.xml (100/1000 = 10% < 70%); mutationTest cannot find module classes (FAIL via
# badMods); sast finds no source sets (ERROR). The KEY assertion is that no stage reports PASS (a
# short-circuited "always PASS" implementation would turn this scenario green and fail S11).
# Threshold-polarity probe (评审④裁决采纳，任务卡验收口径"阈值未达标必红"): without this probe the
# "-lt threshold" polarity could invert with every table still green. mutationTest/sast threshold
# branches are exempt (registered in ADR-018 影响范围行): a real PIT/semgrep fixture is not feasible
# and their numeric paths are anchored by the real-repo reports.
# S11b (复审尾巴修复): the exit-0 stub means S11's coverage FAIL comes from the threshold branch, so
# the "mvn exit != 0 -> FAIL" branch lost its fixture coverage when the stub replaced the real Maven
# failure (复审 P3-3). S11b re-anchors it with a second stub that exits 3.
# Reverse probe: all three disabled -> GREEN/0 (so any red above is attributable to the stages).
Write-Output ""
Write-Output "S11 [metric stages fail-visible] expect: coverage FAIL + mutationTest FAIL/ERROR + sast ERROR, verdict RED, exit 1"
$fix = New-Fixture "s11"
Initialize-FixtureGit $fix
# low-coverage report for the threshold-polarity probe: 100 covered / 900 missed = 10% < 70%
$fakeJacocoDir = Join-Path $fix "common\fakecore\target\site\jacoco"
New-Item -ItemType Directory -Path $fakeJacocoDir -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $fakeJacocoDir "jacoco.xml"),
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><report name="fakecore"><counter type="LINE" missed="900" covered="100"/></report>',
    $utf8NoBom)
# stub mvn.cmd: always exit 0 (lets the coverage stage reach its parse/threshold branch)
$stubDir = Join-Path $fix "stub-bin"
New-Item -ItemType Directory -Path $stubDir -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $stubDir "mvn.cmd"), "@echo off" + [Environment]::NewLine + "exit /b 0" + [Environment]::NewLine, $utf8NoBom)
$cfg = New-FixtureConfig $fix "cfg-s11.json" $false "" $null
$cfgObj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg, [System.Text.Encoding]::UTF8))
$cfgObj.stages.coverage.enabled = $true
$cfgObj.stages.mutationTest.enabled = $true
$cfgObj.stages.sast.enabled = $true
[System.IO.File]::WriteAllText($cfg, ($cfgObj | ConvertTo-Json -Depth 10), $utf8NoBom)
$rep = Join-Path $script:workDir "s11-report.md"
$savedPath = $env:PATH
try {
    $env:PATH = $stubDir + ";" + $env:PATH
    $r = Invoke-Runner $fix $cfg $rep "selftest-s11-metric-stages"
} finally { $env:PATH = $savedPath }
Assert-True ($r.ExitCode -eq 1) ("S11 exit code 1 (got " + $r.ExitCode + ")")
$repText = Read-Report $rep
Assert-Contains $repText "| coverage | FAIL |" "S11 coverage FAIL row present (exit-0 stub lets the threshold branch drive it)"
Assert-Contains $repText "overall line 10%" "S11 threshold probe: the low-coverage report reaches the numeric branch (10% parsed)"
Assert-Contains $repText "< 70%" "S11 threshold probe: below-threshold coverage turns FAIL (polarity anchored)"
Assert-True (-not ($repText -match "\| coverage \| PASS \|")) "S11 coverage must NEVER report PASS in a fixture without reports"
Assert-True (-not ($repText -match "\| mutationTest \| PASS \|")) "S11 mutationTest must NEVER report PASS in a fixture"
Assert-True (-not ($repText -match "\| sast \| PASS \|")) "S11 sast must NEVER report PASS in a fixture"
Assert-Contains $repText "-> RED" "S11 verdict RED (coverage FAIL drives it)"
# reverse probe: same fixture, all three stages off -> GREEN/0
$cfgControl = New-FixtureConfig $fix "cfg-s11-control.json" $false "" $null
$repControl = Join-Path $script:workDir "s11-control-report.md"
$rControl = Invoke-Runner $fix $cfgControl $repControl "selftest-s11-control-probe"
Assert-True ($rControl.ExitCode -eq 0) ("S11 reverse probe exit code 0 (got " + $rControl.ExitCode + ")")
Assert-Contains (Read-Report $repControl) "-> GREEN" "S11 reverse probe verdict GREEN (so the S11 red is attributable to the metric stages)"
# ---------- S11b: mvn nonzero-exit branch probe (清债卡1 复审尾巴修复) ----------
# The S11 stub exits 0, so its coverage FAIL comes from the threshold branch; the "mvn exit != 0 ->
# FAIL" branch in the coverage stage lost its fixture coverage when the stub replaced the real Maven
# failure (复审 P3-3). S11b re-anchors it: a second stub exits 3 -> coverage FAIL must name the exit.
Write-Output ""
Write-Output "S11b [mvn nonzero-exit branch] expect: coverage FAIL naming 'mvn coverage exit 3', verdict RED, exit 1"
$stubFailDir = Join-Path $fix "stub-bin-fail"
New-Item -ItemType Directory -Path $stubFailDir -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $stubFailDir "mvn.cmd"), "@echo off" + [Environment]::NewLine + "exit /b 3" + [Environment]::NewLine, $utf8NoBom)
$cfgExit = New-FixtureConfig $fix "cfg-s11b.json" $false "" $null
$cfgExitObj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfgExit, [System.Text.Encoding]::UTF8))
$cfgExitObj.stages.coverage.enabled = $true
[System.IO.File]::WriteAllText($cfgExit, ($cfgExitObj | ConvertTo-Json -Depth 10), $utf8NoBom)
$repExit = Join-Path $script:workDir "s11b-report.md"
$savedPath2 = $env:PATH
try {
    $env:PATH = $stubFailDir + ";" + $env:PATH
    $rExit = Invoke-Runner $fix $cfgExit $repExit "selftest-s11b-mvn-exit"
} finally { $env:PATH = $savedPath2 }
Assert-True ($rExit.ExitCode -eq 1) ("S11b exit code 1 (got " + $rExit.ExitCode + ")")
$repExitText = Read-Report $repExit
Assert-Contains $repExitText "| coverage | FAIL |" "S11b nonzero mvn exit turns coverage FAIL"
Assert-Contains $repExitText "mvn coverage exit 3" "S11b the FAIL names the real Maven exit code"

# ---------- S11c: sast engine unavailable must NOT abort the run (DB-07 V1.2 §3-ter) ----------
# V4 首跑实证：裸 `docker image inspect <image> 2>&1` 在 PS 5.1 + $ErrorActionPreference=Stop 下
# 被 native stderr 抛出，整轮中止（runner 行 ERROR + 报告未写出）。本锚用 stub docker（写 stderr
# 后 exit 1）锚定"引擎不可用 = sast ERROR 行 + 报告照常写出 + exit 2"，不再以整轮崩溃形态出现。
# 载体自适应：本机无 semgrep 时走 docker 分支（直接锚定修复点）；若机器装有 semgrep 则走原生分支
# 并在 fixture 无源集处记 ERROR——两条路径都必须满足同一组断言（不中止、报告可核、fail-closed）。
Write-Output ""
Write-Output "S11c [sast engine unavailable] expect: sast ERROR row + report written + exit 2, no runner crash"
$fix = New-Fixture "s11c"
Initialize-FixtureGit $fix
$stubDockerDir = Join-Path $fix "stub-docker"
New-Item -ItemType Directory -Path $stubDockerDir -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $stubDockerDir "docker.cmd"),
    ("@echo off" + [Environment]::NewLine + "echo failed to connect to the docker API at npipe:////./pipe/docker_engine 1>&2" + [Environment]::NewLine + "exit /b 1" + [Environment]::NewLine),
    $utf8NoBom)
$cfg11c = New-FixtureConfig $fix "cfg-s11c.json" $false "" $null
$cfg11cObj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg11c, [System.Text.Encoding]::UTF8))
$cfg11cObj.stages.sast.enabled = $true
[System.IO.File]::WriteAllText($cfg11c, ($cfg11cObj | ConvertTo-Json -Depth 10), $utf8NoBom)
$rep11c = Join-Path $script:workDir "s11c-report.md"
$savedPath11c = $env:PATH
try {
    $env:PATH = $stubDockerDir + ";" + $env:PATH
    $r11c = Invoke-Runner $fix $cfg11c $rep11c "selftest-s11c-sast-engine-unavailable"
} finally { $env:PATH = $savedPath11c }
Assert-True ($r11c.ExitCode -eq 2) ("S11c exit code 2 (got " + $r11c.ExitCode + ")")
Assert-True (Test-Path $rep11c) "S11c the report is still written (the old bug lost all evidence)"
$rep11cText = Read-Report $rep11c
Assert-Contains $rep11cText "- ExitCode: 2" "S11c printed report declares the real exit code"
Assert-Contains $rep11cText "| sast | ERROR |" "S11c sast reports ERROR when the engine is unavailable"
Assert-True (-not ($rep11cText -match "\| runner \|")) "S11c no runner-crash row (the run must not abort mid-way)"

# ---------- S12: mvn success-state log (DB-43 ① - success must be evidenced too) ----------
# stub mvn exit 0 + marker output -> compile PASS and %TEMP%\ctds-gate-compile-out.log must exist
# with the machine-checkable summary (stage/command/exitCode) and the full original output.
# The PASS report row keeps its ORIGINAL format (no path appended - 最小改动, hifi §2.2-3).
Write-Output ""
Write-Output "S12 [mvn success-state log] expect: compile PASS + out.log with 'exitCode: 0' + stub marker"
$fix = New-Fixture "s12"
Initialize-FixtureGit $fix
$stubDir12 = Join-Path $fix "stub-bin"
New-Item -ItemType Directory -Path $stubDir12 -Force | Out-Null
$stubMarker12 = "DB43-S12-STUB-MARKER"
[System.IO.File]::WriteAllText((Join-Path $stubDir12 "mvn.cmd"), "@echo off" + [Environment]::NewLine + "echo " + $stubMarker12 + [Environment]::NewLine + "exit /b 0" + [Environment]::NewLine, $utf8NoBom)
# 真配置里的 javaHome 原样传入（不写死机器路径；env JAVA_HOME 在时由 runner 优先使用）
$realJavaHome = (ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($realConfig, [System.Text.Encoding]::UTF8))).toolchain.javaHome
$cfg12 = New-FixtureConfig $fix "cfg-s12.json" $true $realJavaHome $null
$rep12 = Join-Path $script:workDir "s12-report.md"
$outLog12 = Join-Path $env:TEMP "ctds-gate-compile-out.log"
$savedPath12 = $env:PATH
try {
    $env:PATH = $stubDir12 + ";" + $env:PATH
    $r12 = Invoke-Runner $fix $cfg12 $rep12 "selftest-s12-mvn-success-log"
} finally { $env:PATH = $savedPath12 }
Assert-True ($r12.ExitCode -eq 0) ("S12 exit code 0 (got " + $r12.ExitCode + ")")
$rep12Text = Read-Report $rep12
Assert-Contains $rep12Text "| compile | PASS |" "S12 compile PASS (stub exit 0)"
Assert-Contains $rep12Text "- ExitCode: 0" "S12 report header declares exit code 0"
Assert-NotContains $rep12Text "full output:" "S12 the PASS row keeps its original format (no path appended)"
Assert-True (Test-Path $outLog12) "S12 out.log EXISTS for the success state (the 0-byte-log lesson fixed)"
$out12 = ""
if (Test-Path $outLog12) { $out12 = [System.IO.File]::ReadAllText($outLog12, [System.Text.Encoding]::UTF8) }
Assert-Contains $out12 "stage: compile" "S12 out.log carries the stage summary line"
Assert-Contains $out12 "exitCode: 0" "S12 out.log carries exitCode: 0 (machine-checkable proof)"
Assert-Contains $out12 $stubMarker12 "S12 out.log carries the full original output (stub marker)"
foreach ($n in @("compile", "lint", "unitTest")) {
    $p = Join-Path $env:TEMP ("ctds-gate-" + $n + "-out.log")
    if (Test-Path $p) { Remove-Item $p -Force -ErrorAction SilentlyContinue }
}

# ---------- S13: npm failure full-output log (DB-43 ③ - failure evidence is never lost) ----------
# stub npm exits 1 with markers on stdout/stderr -> frontendTypeCheck FAIL, out.log carries the
# exitCode and both markers (DB-44 "error text unobtainable" positive anchor), and the report
# FAIL row carries the full-output path.
Write-Output ""
Write-Output "S13 [npm failure full-output log] expect: FAIL + out.log 'exitCode: 1' + markers + report 'full output:'"
$fix = New-Fixture "s13"
Initialize-FixtureGit $fix
$fixFrontend13 = Join-Path $fix "frontend"
New-Item -ItemType Directory -Path $fixFrontend13 -Force | Out-Null
$stubMarker13 = "DB43-S13-STUB-MARKER"
[System.IO.File]::WriteAllText((Join-Path $fixFrontend13 "fail1.js"),
    ("process.stdout.write('" + $stubMarker13 + "-out\n'); process.stderr.write('" + $stubMarker13 + "-err\n'); process.exit(1)" + [Environment]::NewLine),
    $utf8NoBom)
$pkgJson13 = Join-Path $fixFrontend13 "package.json"
[System.IO.File]::WriteAllText($pkgJson13, ('{"name":"fixture-frontend","version":"0.0.0","private":true,"scripts":{"typecheck":"node fail1.js"}}' + [Environment]::NewLine), $utf8NoBom)
$cfg13 = New-FixtureConfig $fix "cfg-s13.json" $false "" $null
$cfgObj13 = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg13, [System.Text.Encoding]::UTF8))
$cfgObj13.stages.frontendTypeCheck.enabled = $true
[System.IO.File]::WriteAllText($cfg13, ($cfgObj13 | ConvertTo-Json -Depth 10), $utf8NoBom)
$rep13 = Join-Path $script:workDir "s13-report.md"
$outLog13 = Join-Path $env:TEMP "ctds-gate-frontendTypeCheck-out.log"
$r13 = Invoke-Runner $fix $cfg13 $rep13 "selftest-s13-npm-failure-log"
Assert-True ($r13.ExitCode -eq 1) ("S13 exit code 1 (got " + $r13.ExitCode + ")")
$rep13Text = Read-Report $rep13
Assert-Contains $rep13Text "| frontendTypeCheck | FAIL |" "S13 the failing typecheck turns the stage FAIL"
Assert-Contains $rep13Text "full output:" "S13 the FAIL row carries the full-output path marker"
Assert-True (Test-Path $outLog13) "S13 out.log EXISTS (failure evidence is no longer lost)"
$out13 = ""
if (Test-Path $outLog13) { $out13 = [System.IO.File]::ReadAllText($outLog13, [System.Text.Encoding]::UTF8) }
Assert-Contains $out13 "exitCode: 1" "S13 out.log carries exitCode: 1"
Assert-Contains $out13 ($stubMarker13 + "-out") "S13 out.log carries the stdout marker (error text obtainable)"
Assert-Contains $out13 ($stubMarker13 + "-err") "S13 out.log carries the stderr marker"
if (Test-Path $outLog13) { Remove-Item $outLog13 -Force -ErrorAction SilentlyContinue }

# ---------- S14: timeout process-tree cleanup (DB-43 Q1-A / 跟踪-23 剩余) ----------
# stub mvn behaves per stage: for the compile goal it starts a grandchild (node, writes its PID
# to a file) then hangs; for the other goals it exits 0 immediately (so only ONE grandchild is
# ever spawned). compile timeoutSeconds=2 -> the runner times out and must clean the WHOLE tree.
# The old Kill()-only behaviour leaves the grandchild alive (red); taskkill /T cleans it (green).
# Both the grandchild and the hang-loop redirect to nul so they never hold the runner's stdout
# pipe (an inherited pipe handle would block the harness redirection - a red-phase-only hang).
# The selftest kills any surviving grandchild itself so a red run never leaks an orphan process.
Write-Output ""
Write-Output "S14 [timeout process-tree cleanup] expect: FAIL 'timed out' + out.log '(TIMED OUT)' + grandchild PID gone"
$fix = New-Fixture "s14"
Initialize-FixtureGit $fix
$stubDir14 = Join-Path $fix "stub-bin"
New-Item -ItemType Directory -Path $stubDir14 -Force | Out-Null
$childPidFile14 = Join-Path $fix "child.pid"
$childJs14 = Join-Path $fix "child.js"
$stubMarker14 = "DB43-S14-STUB-MARKER"
[System.IO.File]::WriteAllText($childJs14, "require('fs').writeFileSync(process.argv[2], String(process.pid)); try { require('fs').closeSync(1); } catch (e) {} try { require('fs').closeSync(2); } catch (e) {} setInterval(function () {}, 1000);" + [Environment]::NewLine, $utf8NoBom)
[System.IO.File]::WriteAllText((Join-Path $stubDir14 "mvn.cmd"),
    ("@echo off" + [Environment]::NewLine +
     "echo " + $stubMarker14 + [Environment]::NewLine +
     "echo %* | findstr /c:`"compile`" >nul" + [Environment]::NewLine +
     "if errorlevel 1 exit /b 0" + [Environment]::NewLine +
     "start /b node `"" + $childJs14 + "`" `"" + $childPidFile14 + "`" >nul 2>&1" + [Environment]::NewLine +
     "ping -n 61 127.0.0.1 >nul 2>&1" + [Environment]::NewLine +
     "exit /b 0" + [Environment]::NewLine),
    $utf8NoBom)
$cfg14 = New-FixtureConfig $fix "cfg-s14.json" $true $realJavaHome $null
$cfgObj14 = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg14, [System.Text.Encoding]::UTF8))
# compile 段在真配置中无 timeoutSeconds 属性（仅 unitTest 有）——先补属性再设值（V1.5 起的可配置面）
Add-Member -InputObject $cfgObj14.stages.compile -MemberType NoteProperty -Name timeoutSeconds -Value 2 -Force
[System.IO.File]::WriteAllText($cfg14, ($cfgObj14 | ConvertTo-Json -Depth 10), $utf8NoBom)
$rep14 = Join-Path $script:workDir "s14-report.md"
$outLog14 = Join-Path $env:TEMP "ctds-gate-compile-out.log"
$savedPath14 = $env:PATH
try {
    $env:PATH = $stubDir14 + ";" + $env:PATH
    $r14 = Invoke-Runner $fix $cfg14 $rep14 "selftest-s14-timeout-tree" -detached
} finally { $env:PATH = $savedPath14 }
Assert-True ($r14.ExitCode -eq 1) ("S14 exit code 1 (got " + $r14.ExitCode + ")")
$rep14Text = Read-Report $rep14
Assert-Contains $rep14Text "| compile | FAIL |" "S14 compile FAIL on timeout"
Assert-Contains $rep14Text "timed out after 2s" "S14 the FAIL names the timeout budget"
Assert-Contains $rep14Text "full output:" "S14 the timeout FAIL row carries the full-output path"
Assert-Contains $rep14Text "| lint | PASS |" "S14 the other Maven stages still ran (gate was NOT aborted)"
Assert-True (Test-Path $outLog14) "S14 out.log EXISTS for the timeout state"
$out14 = ""
if (Test-Path $outLog14) { $out14 = [System.IO.File]::ReadAllText($outLog14, [System.Text.Encoding]::UTF8) }
Assert-Contains $out14 "(TIMED OUT)" "S14 out.log marks the TIMED OUT state"
Assert-Contains $out14 $stubMarker14 "S14 out.log carries the partial output captured before the timeout"
$childPid14 = 0
if (Test-Path $childPidFile14) {
    $childPidText14 = ([System.IO.File]::ReadAllText($childPidFile14, [System.Text.Encoding]::UTF8)).Trim()
    $null = [int]::TryParse($childPidText14, [ref]$childPid14)
}
$childAlive14 = $false
if ($childPid14 -gt 0) { $childAlive14 = $null -ne (Get-Process -Id $childPid14 -ErrorAction SilentlyContinue) }
Assert-True (-not $childAlive14) ("S14 the grandchild was cleaned up with the tree (pid " + $childPid14 + " alive=" + $childAlive14 + ")")
# 兜底：红相期旧实现仅杀 cmd.exe，node 幸存——由 selftest 收杀掉，绝不留孤儿进程
if ($childAlive14) { try { Stop-Process -Id $childPid14 -Force -ErrorAction SilentlyContinue } catch { } }
foreach ($n in @("compile", "lint", "unitTest")) {
    $p = Join-Path $env:TEMP ("ctds-gate-" + $n + "-out.log")
    if (Test-Path $p) { Remove-Item $p -Force -ErrorAction SilentlyContinue }
}

# ---------- S15: gateSelfTest executor wiring (DB-07 编排师裁定 C, config V1.8, ADR-018 V1.5) ----------
# enabled=true 时执行器必须实跑 <tool> 并把退出码映射为报告行：0=PASS / 1=FAIL / 2=ERROR；
# tool 指向不存在文件 = ERROR。绿/红/ERROR/缺文件四探针。stub 只写标记输出——fixture 内跑真实
# selftest 会递归（门禁→selftest→fixture 门禁→selftest），故用 stub 验证接线与映射；真实
# selftest 的回归由 V2 基线与启用后 V3 全量门禁（真实链）覆盖。
Write-Output ""
Write-Output "S15 [gateSelfTest executor] expect: exit 0 -> PASS row/exit 0; exit 1 -> FAIL row/exit 1; exit 2 -> ERROR row/exit 2; tool missing -> ERROR row/exit 2"
$s15Probes = @(
    @{ Name = "green";   Code = 0;    Verdict = "PASS" },
    @{ Name = "spaces";  Code = 0;    Verdict = "PASS" },
    @{ Name = "red";     Code = 1;    Verdict = "FAIL" },
    @{ Name = "error";   Code = 2;    Verdict = "ERROR" },
    @{ Name = "hang";    Code = 1;    Verdict = "FAIL" },
    @{ Name = "missing"; Code = $null; Verdict = "ERROR" }
)
foreach ($probe in $s15Probes) {
    $fix15 = New-Fixture ("s15-" + $probe.Name)
    Initialize-FixtureGit $fix15
    $gstDir15 = Join-Path $fix15 "scripts\gates"
    New-Item -ItemType Directory -Path $gstDir15 -Force | Out-Null
    $cfg15 = New-FixtureConfig $fix15 ("cfg-s15-" + $probe.Name + ".json") $false "" $null
    $cfg15Obj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfg15, [System.Text.Encoding]::UTF8))
    $cfg15Obj.stages.gateSelfTest.enabled = $true
    $stubMarker15 = ""
    if ($probe.Name -eq "hang") {
        # hang 探针（④ F-2）：stub 挂起 60s，fixture config 把该段 timeoutSeconds 设为 2——锚定
        # 执行器超时分支（FAIL "timed out after 2s" + full output 取证）；S14 同款 detached 调用
        # （超时注入场景不重定向 std，断言全在报告文件上）
        $stubMarker15 = "DB07-S15-STUB-HANG"
        # [Console]::Out + Flush：PS 5.1 重定向 stdout 时输出经 PowerShell 缓冲，挂起中被 kill 会丢失
        # ——直写进程 stdout 并 flush，保证"stub 确已启动且其输出已进入取证"可被断言
        [System.IO.File]::WriteAllText((Join-Path $gstDir15 "selftest.ps1"),
            ("[Console]::Out.WriteLine('" + $stubMarker15 + "'); [Console]::Out.Flush()" + [Environment]::NewLine + "Start-Sleep -Seconds 60" + [Environment]::NewLine + "exit 0" + [Environment]::NewLine),
            $utf8NoBom)
        Add-Member -InputObject $cfg15Obj.stages.gateSelfTest -MemberType NoteProperty -Name timeoutSeconds -Value 2 -Force
    } elseif ($probe.Name -eq "missing") {
        # 缺文件探针：tool 指向不存在的脚本（fixture 内连 stub 都不放）
        $cfg15Obj.stages.gateSelfTest.tool = "scripts/gates/zz-no-such-selftest.ps1"
    } else {
        $stubMarker15 = ("DB07-S15-STUB-" + $probe.Name.ToUpper())
        $stubBody15 = "Write-Output '" + $stubMarker15 + "'" + [Environment]::NewLine
        if ($probe.Code -eq 0) {
            # 退出 0 的探针 stub 还打印 selftest 末行摘要——验证执行器 PASS 详情的摘要解析分支
            $stubBody15 += "Write-Output 'SELFTEST PASS - 3 assertion(s), 0 failure(s)'" + [Environment]::NewLine
        }
        $stubBody15 += "exit " + $probe.Code + [Environment]::NewLine
        if ($probe.Name -eq "spaces") {
            # 空格路径探针：stub 放"scripts\gates dir"（带空格目录）、tool 指向它——锚定执行器
            # argLine 必须给 -File 路径加引号（仓库根路径含空格：E:\AI Projects\...；未加引号会被
            # powershell 命令行解析截断成" cannot find path"类失败）
            $spacedDir15 = Join-Path $fix15 "scripts\gates dir"
            New-Item -ItemType Directory -Path $spacedDir15 -Force | Out-Null
            [System.IO.File]::WriteAllText((Join-Path $spacedDir15 "selftest.ps1"), $stubBody15, $utf8NoBom)
            $cfg15Obj.stages.gateSelfTest.tool = "scripts/gates dir/selftest.ps1"
        } else {
            [System.IO.File]::WriteAllText((Join-Path $gstDir15 "selftest.ps1"), $stubBody15, $utf8NoBom)
        }
    }
    [System.IO.File]::WriteAllText($cfg15, ($cfg15Obj | ConvertTo-Json -Depth 10), $utf8NoBom)
    $rep15 = Join-Path $script:workDir ("s15-" + $probe.Name + "-report.md")
    if ($probe.Name -eq "hang") {
        # hang 探针走 detached（S14 同款：超时注入不重定向 std，断言全在报告文件上）
        $r15 = Invoke-Runner $fix15 $cfg15 $rep15 ("selftest-s15-gateselftest-" + $probe.Name) -detached
    } else {
        $r15 = Invoke-Runner $fix15 $cfg15 $rep15 ("selftest-s15-gateselftest-" + $probe.Name)
    }
    $rep15Text = Read-Report $rep15
    if ($probe.Name -eq "missing") {
        Assert-True ($r15.ExitCode -eq 2) ("S15 missing probe exit code 2 (got " + $r15.ExitCode + ")")
        Assert-Contains $rep15Text "| gateSelfTest | ERROR |" "S15 missing probe: gateSelfTest reports ERROR"
        Assert-Contains $rep15Text "selftest script not found" "S15 missing probe: the ERROR names the missing script"
    } else {
        Assert-True ($r15.ExitCode -eq $probe.Code) ("S15 " + $probe.Name + " probe exit code " + $probe.Code + " (got " + $r15.ExitCode + ")")
        Assert-Contains $rep15Text ("| gateSelfTest | " + $probe.Verdict + " |") ("S15 " + $probe.Name + " probe: gateSelfTest reports " + $probe.Verdict)
        if ($probe.Code -eq 0) {
            Assert-Contains $rep15Text "3 assertions, 0 failures" ("S15 " + $probe.Name + " probe: PASS detail carries the parsed selftest summary")
            Assert-NotContains $rep15Text "gateSelfTest | PENDING" ("S15 " + $probe.Name + " probe: no PENDING row when the executor ran (no silent double-bookkeeping)")
            # ④ F-1：行序锚——执行器必须位于全阶段之首（selftest 开场/收尾清理及其 fixture 均写
            # %TEMP% 固定名 ctds-gate-*-out.log；置首保护本运行先前阶段的失败取证，下移即破坏）
            Assert-True ($rep15Text.IndexOf("| gateSelfTest |") -lt $rep15Text.IndexOf("| secretsScan |")) ("S15 " + $probe.Name + " probe: the executor row precedes secretsScan (first-stage invariant)")
        } else {
            if ($probe.Name -eq "hang") {
                # 超时行按既有口径（同 mvn/npm 段）不带输出尾段——行文锚 + full output 取证深锚
                Assert-Contains $rep15Text "timed out after 2s" "S15 hang probe: the FAIL names the timeout budget (executor timeout branch anchored)"
                Assert-Contains $rep15Text "full output:" "S15 hang probe: the FAIL row carries the full-output path"
                $mHang = [regex]::Match($rep15Text, "full output: (.+ctds-gate-gateSelfTest-out\.log)")
                if ($mHang.Success -and (Test-Path $mHang.Groups[1].Value.Trim())) {
                    $hangOut = [System.IO.File]::ReadAllText($mHang.Groups[1].Value.Trim(), [System.Text.Encoding]::UTF8)
                    Assert-Contains $hangOut "stage: gateSelfTest" "S15 hang probe: out.log carries the stage summary line (DB-43 format)"
                    Assert-Contains $hangOut "exitCode: -1 (TIMED OUT)" "S15 hang probe: out.log marks the TIMED OUT state"
                    Assert-Contains $hangOut $stubMarker15 "S15 hang probe: out.log carries the stub output captured before the kill"
                } else {
                    Assert-True $false "S15 hang probe: the reported out.log path is present and readable"
                }
            } else {
                # marker 仅对真实生成 stub 的探针断言（missing 探针无 stub，marker 为空——避免恒真断言）
                if ($stubMarker15 -ne "") {
                    Assert-Contains $rep15Text $stubMarker15 ("S15 " + $probe.Name + " probe: the stub marker reached the report evidence")
                }
                Assert-Contains $rep15Text "full output:" ("S15 " + $probe.Name + " probe: the " + $probe.Verdict + " row carries the full-output path")
                if ($probe.Name -eq "red") {
                    Assert-Contains $rep15Text "runner self-check FAILED" "S15 red probe: the FAIL says the gate cannot vouch for this run"
                }
                if ($probe.Name -eq "error") {
                    Assert-Contains $rep15Text "harness error" "S15 error probe: the ERROR says exit 2 is not a code verdict"
                }
            }
        }
    }
}

# ---------- S15 收尾护栏（④ F-8）：把递归守卫从"约定"升级为"机器强制" ----------
# New-FixtureConfig 集中强制关闭 + 补回 status；若未来新场景绕过该 helper 直接复制真实 config
# （enabled=true）当 fixture 配置，将触发"门禁→selftest→fixture 门禁→selftest"无限递归。
# 收尾前统一扫描本运行产生的全部 fixture 配置（S15 显式开启的 cfg-s15-*.json 按名豁免）。
Write-Output ""
Write-Output "S15 guard scan: every non-S15 fixture config must keep gateSelfTest in the disabled state"
$s15GuardBad = @()
foreach ($cfgGuardFile in (Get-ChildItem $script:workDir -Recurse -Filter "cfg*.json" -ErrorAction SilentlyContinue)) {
    if ($cfgGuardFile.Name -like "cfg-s15-*.json") { continue }
    $cfgGuardObj = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($cfgGuardFile.FullName, [System.Text.Encoding]::UTF8))
    $gstGuard = $cfgGuardObj.stages.gateSelfTest
    if ($null -eq $gstGuard -or $gstGuard.enabled -ne $false -or $gstGuard.status -notlike "PENDING*") {
        $s15GuardBad += ($cfgGuardFile.Name + " (enabled=" + $(if ($null -eq $gstGuard) { "<absent>" } else { $gstGuard.enabled }) + ", status=" + $(if ($null -eq $gstGuard) { "<absent>" } else { $gstGuard.status }) + ")")
    }
}
Assert-True ($s15GuardBad.Count -eq 0) ("S15 guard scan: all non-S15 fixture configs keep gateSelfTest disabled + PENDING status" + $(if ($s15GuardBad.Count -gt 0) { " <violations: " + ($s15GuardBad -join " ; ") + ">" } else { "" }))

} catch {
    Write-Output ""
    Write-Output ("[HARNESS-ERROR] " + $_.Exception.Message + " | " + $_.ScriptStackTrace)
    Write-Output ("fixture workdir kept for inspection: " + $script:workDir)
    exit 2
}

Write-Output ""
if ($script:failures -eq 0) {
    Write-Output ("SELFTEST PASS - " + $script:assertions + " assertion(s), 0 failure(s)")
} else {
    Write-Output ("SELFTEST FAIL - " + $script:assertions + " assertion(s), " + $script:failures + " failure(s)")
}
# DB-43：收尾统一清理本次运行产生的全部 out.log（S11/S11b 的 coverage/mutationTest FAIL 也会
# 落盘——fixture 运行写的是同一固定名，绝不把 fixture 产物留在真实门禁的记录位上）
foreach ($produced in (Get-ChildItem $env:TEMP -Filter "ctds-gate-*-out.log" -ErrorAction SilentlyContinue)) {
    Remove-Item $produced.FullName -Force -ErrorAction SilentlyContinue
}
if ($KeepFixture -or $script:failures -gt 0) {
    Write-Output ("fixture workdir kept for inspection: " + $script:workDir)
} else {
    Remove-Item -Path $script:workDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Output "fixture workdir removed (all scenarios passed)"
}
if ($script:failures -gt 0) { exit 1 }
exit 0
