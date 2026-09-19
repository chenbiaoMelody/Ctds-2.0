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
# How: every scenario builds a hermetic fixture repo under %TEMP% (git init + git add, cheap stages
# only), generates its config FROM THE REAL gates-config.json (so the real exclude list is what gets
# exercised), runs scripts/gates/run-gates.ps1 against it and asserts on the report file / stdout /
# exit code. Nothing outside %TEMP% is touched; the fixtures are deleted when all scenarios pass.
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
    foreach ($n in @("compile", "lint", "unitTest", "frontendLint", "frontendTest", "frontendE2E")) { Set-StageEnabled $cfgObj $n $false }
    if ($mavenStages) {
        foreach ($n in @("compile", "lint", "unitTest")) { Set-StageEnabled $cfgObj $n $true }
        $cfgObj.toolchain.javaHome = $javaHome
    }
    if ($extraExclude -and $extraExclude.Count -gt 0) { $cfgObj.secretsExcludePaths = @($cfgObj.secretsExcludePaths) + $extraExclude }
    $path = Join-Path $dir $fileName
    [System.IO.File]::WriteAllText($path, ($cfgObj | ConvertTo-Json -Depth 10), $utf8NoBom)
    return $path
}
function Invoke-Runner([string]$repo, [string]$cfgPath, [string]$reportPath, [string]$scenario) {
    $outFile = Join-Path $script:workDir ($scenario + ".out.txt")
    $runnerArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $runner, "-RepoRoot", $repo, "-ConfigPath", $cfgPath, "-Scenario", $scenario)
    if ($reportPath -ne "") { $runnerArgs += @("-ReportPath", $reportPath) }
    & powershell @runnerArgs *> $outFile
    $code = $LASTEXITCODE
    $text = ""
    if (Test-Path $outFile) { $text = Get-Content -Path $outFile -Raw }
    return [pscustomobject]@{ ExitCode = $code; Stdout = $text }
}

# ================= harness =================
if (-not (Test-Path $runner)) { Write-Output ("[HARNESS-ERROR] runner not found: " + $runner); exit 2 }
if (-not (Test-Path $realConfig)) { Write-Output ("[HARNESS-ERROR] config not found: " + $realConfig); exit 2 }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Write-Output "[HARNESS-ERROR] git not found on PATH (fixtures need a real index)"; exit 2 }
if (-not (Get-Command powershell -ErrorAction SilentlyContinue)) { Write-Output "[HARNESS-ERROR] powershell not found on PATH"; exit 2 }
$script:workDir = Join-Path $env:TEMP ("ctds-gates-selftest-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $script:workDir -Force | Out-Null
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
    Assert-Contains $repText "gateSelfTest | PENDING | PENDING-SELFTEST" "S1 'selftest not wired into CI' is visible in every report"
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
if ($KeepFixture -or $script:failures -gt 0) {
    Write-Output ("fixture workdir kept for inspection: " + $script:workDir)
} else {
    Remove-Item -Path $script:workDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Output "fixture workdir removed (all scenarios passed)"
}
if ($script:failures -gt 0) { exit 1 }
exit 0
