<#
.SYNOPSIS
  e2e-run.ps1 — behavioural regression harness for Refio's coding agents (docs/0061), Windows parity
  for e2e-run.sh. Runs each scenario through the existing headless CLI into a throwaway --project and
  asserts on the produced run.json.

.DESCRIPTION
  Assertion tiers (docs/0061 review note — keep hard signals hard, soft signals soft):
    HARD (fail):  needle(s) present · build_cmd exit==0 (when present) · no_context_overflow
    SOFT (warn):  tool_order (subsequence) · judge

  Scenarios are JSON (parsed by native ConvertFrom-Json — no YAML module needed).

  Needles: {path, regex?|text?}; ALL of needle_in_file + needles_in_file[] must match. `regex` is
  authored as grep ERE (POSIX classes such as [[:space:]]); this script translates [[:space:]] -> \s
  for .NET regex and matches case-sensitively (-cmatch) to mirror grep -E.

  Selection: -List shows ids; -All runs everything; a positional arg resolves as a file path, else
  test_data\e2e\<arg>.json, else the scenario whose .id equals it.

  ENCODING: this file MUST stay UTF-8 *with BOM*. It contains non-ASCII (em dashes); Windows PowerShell
  5.1 reads a no-BOM file as ANSI, which corrupts those bytes and breaks the parse before any code runs.
  The BOM makes both Windows PowerShell 5.1 and PowerShell 7 read it as UTF-8. Do not strip the BOM.

  PARITY: the regex/array-needle and selection logic mirror e2e-run.sh; `-SelfTest` passes under Windows
  PowerShell 5.1. e2e-run.sh remains the validated reference for live (LLM) runs.

  CONSENT: a real run spends tokens / local GPU time and WRITES into a temp --project. Per CLAUDE.md's
  headless rule, a human approves the concrete command before it runs. -SelfTest exercises only the
  assertion logic against bundled sample run.json files and makes no LLM call.

  LLM judge (SOFT tier, mirrors e2e-run.sh): set JUDGE_MODEL=<provider/model> to run an external judge
  after each scenario. The judge is the headless CLI itself in CHAT mode; it gets the task text, the
  diff of the fixture project after the run, the build/test output, and the scenario's optional
  `judge_criteria` (string or array; falls back to legacy `judge.criteria`). It must answer with JSON
  {verdict: PASS|FAIL, confidence: 0-1, reasons: [...]}; an unparseable answer becomes verdict FAIL
  with reason "judge output unparseable". The verdict is appended to the run's results.jsonl record
  (E2E_OUT_DIR mechanism) as `judge:{...}` and printed as a WARN/NOTE - it never changes the run's
  HARD verdict. Sanity: JUDGE_MODEL must differ from the tested -Model, otherwise judging is skipped
  with a warning; multi-agent scenarios (no prompt file) are skipped with a note.

.EXAMPLE
  ./e2e-run.ps1 -SelfTest
.EXAMPLE
  ./e2e-run.ps1 -List
.EXAMPLE
  ./e2e-run.ps1 -Model "ollama/qwen3.5:9b" increase-retry-count
.EXAMPLE
  ./e2e-run.ps1 -Model "ollama/qwen3.5:9b" -All
#>
# PositionalBinding=$false: scenario ids are passed as bare args (e.g. `-Model X increase-retry-count`)
# and must land in $Scenarios via ValueFromRemainingArguments. Without this, a bare id binds
# positionally to $Cli/$MaxCost instead (the second bare id then fails [double] conversion on MaxCost).
[CmdletBinding(PositionalBinding=$false)]
param(
    [string]$Cli,
    [double]$MaxCost = 0.50,
    [string]$Model,
    [string]$OllamaHost,
    [int]$OllamaCtx = 0,
    [switch]$Keep,
    [switch]$SelfTest,
    [switch]$List,
    [switch]$All,
    [string[]]$Config,
    # Headless auto-approval for verification commands. Without it, run_terminal_command (a
    # PermissionLevel.ASK tool) is rejected in headless (no human, no approver) -> the turn ends
    # FAILED even though the edit already landed - a spurious failure for any model that tries to
    # compile/test its own work. Mirrors e2e-run.sh's AUTO_APPROVE default exactly (same tool-name
    # regex, cross-platform: gradlew/python/node etc. are invoked by bare name on Windows too via
    # PATHEXT). Override with -AutoApprove <regex> or disable with -NoAutoApprove.
    [string]$AutoApprove = '\b(kotlinc|gradlew|gradle|javac|java|python3?|pip3?|node|npm|npx|pnpm|yarn|pytest|mvn|cargo|go|make|cmake|ls|cat|pwd|echo|head|tail|sed|awk|grep|rg|find|wc|diff|test|true|cd|sh|bash|env|export)\b',
    [switch]$NoAutoApprove,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Scenarios
)
if ($NoAutoApprove) { $AutoApprove = '' }

$ErrorActionPreference = 'Stop'
# The CLI and build_cmd are native commands that signal failure via exit code, which this script
# inspects explicitly ($LASTEXITCODE). A non-zero native exit must FAIL only its own scenario - like
# e2e-run.sh - never abort the whole run. Without this, PowerShell 7.4+ escalates a non-zero native
# exit to a terminating error under -EAP Stop, so a missing build tool (e.g. kotlinc) would kill the
# entire -All run instead of failing just that scenario. The variable is absent on Windows PowerShell
# 5.1, where assigning it is a harmless no-op.
$PSNativeCommandUseErrorActionPreference = $false
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
# Top-level *.json here are scenarios; examples\ (samples) is a subdir and is excluded from discovery.
$E2EDir    = Join-Path $RepoRoot 'test_data\e2e'
if (-not $Cli) { $Cli = Join-Path $RepoRoot 'cli\build\install\cli\bin\cli.bat' }

# --- scenario discovery & selection -----------------------------------------
function Get-Scenarios {
    Get-ChildItem -Path $E2EDir -Filter *.json -File | ForEach-Object { $_.FullName }
}

function Show-Scenarios {
    Write-Host "Selectable e2e scenarios in test_data\e2e:"
    foreach ($f in Get-Scenarios) {
        $sc = Get-Content -Raw $f | ConvertFrom-Json
        $mode = if ($sc.mode) { $sc.mode } else { 'AGENT' }
        Write-Host ("  {0,-24} mode={1,-6} {2}" -f $sc.id, $mode, (Split-Path -Leaf $f))
    }
}

# Resolve a positional arg to a scenario path: existing file → that file; else <E2EDir>\<arg>.json;
# else the scenario whose `.id` equals <arg>. Throws if nothing matches.
function Resolve-Scenario {
    param([string]$Arg)
    if (Test-Path $Arg) { return (Resolve-Path $Arg).Path }
    $byName = Join-Path $E2EDir "$Arg.json"
    if (Test-Path $byName) { return $byName }
    foreach ($f in Get-Scenarios) {
        $sc = Get-Content -Raw $f | ConvertFrom-Json
        if ($sc.id -eq $Arg) { return $f }
    }
    throw "scenario not found by path or id: '$Arg' (try -List)"
}

# Returns "PASS" or "FAIL (reasons)". Writes SOFT warnings to the host (stderr-like) only.
function Assert-Run {
    param($Scenario, $RunJsonPath, $ProjectDir, [int]$BuildExit, [int]$SmokeExit = 0)
    $s   = Get-Content -Raw $Scenario | ConvertFrom-Json
    $run = Get-Content -Raw $RunJsonPath | ConvertFrom-Json
    $hardFail = $false; $reasons = @()

    # HARD 0 — the agent run itself must have SUCCEEDED. run.json carries the final TaskStatus name
    # (SessionDebugExporter): SUCCESS = delivered; FAILED/INCOMPLETE/CANCELED = the agent errored, gave
    # up, or was aborted. Without this a failed run that still wrote a run.json passes whenever the
    # FIXTURE already satisfies the deterministic checks below (e.g. a no-change scenario) — hiding an
    # agent failure as PASS (docs/0061). A non-SUCCESS status hard-fails. Mirrors e2e-run.sh HARD 0.
    $runStatus = if ($run.session -and $run.session.status) { [string]$run.session.status } else { 'UNKNOWN' }
    if ($runStatus -ne 'SUCCESS') { $hardFail = $true; $reasons += "session.status=$runStatus (want SUCCESS)" }

    # HARD 1 — needle(s) in the edited file(s); ALL must match.
    # {path, regex?|text?, absent?, min_count?, max_count?}. grep ERE POSIX classes have no .NET token,
    # so [[:space:]] -> \s; match case-sensitively (-cmatch) to mirror grep -E. Counts matching LINES.
    $needles = @()
    if ($s.assert.needle_in_file)  { $needles += $s.assert.needle_in_file }
    if ($s.assert.needles_in_file) { $needles += @($s.assert.needles_in_file) }
    foreach ($nd in $needles) {
        if (-not $nd.path) { continue }
        $file = Join-Path $ProjectDir $nd.path
        if (-not (Test-Path $file)) { $hardFail = $true; $reasons += "file $($nd.path) not found"; continue }
        $lines = @(Get-Content $file)
        if ($nd.regex) {
            $rx = ($nd.regex -replace '\[\[:space:\]\]', '\s')
            $c = @($lines | Where-Object { $_ -cmatch $rx }).Count
            $patt = "/$($nd.regex)/"
        } elseif ($nd.text) {
            $c = @($lines | Where-Object { $_.Contains($nd.text) }).Count
            $patt = "'$($nd.text)'"
        } else { continue }
        if ($nd.absent -eq $true) { $lo = 0; $hi = 0 }
        else {
            $lo = if ($null -ne $nd.min_count) { [int]$nd.min_count } else { 1 }
            $hi = if ($null -ne $nd.max_count) { [int]$nd.max_count } else { 1000000 }
        }
        if ($c -lt $lo -or $c -gt $hi) {
            $hardFail = $true; $reasons += "$patt matched $c line(s) in $($nd.path), want [$lo..$hi]"
        }
    }

    # HARD 1b — needle_in_output: regex/text against run.json .finalOutput (PLAN/CHAT scenarios).
    if ($s.assert.needle_in_output) {
        $out = if ($null -ne $run.finalOutput) { [string]$run.finalOutput } else { "" }
        if ($s.assert.needle_in_output.regex) {
            $orx = ($s.assert.needle_in_output.regex -replace '\[\[:space:\]\]', '\s')
            if ($out -cnotmatch $orx) { $hardFail = $true; $reasons += "output regex /$($s.assert.needle_in_output.regex)/ not matched" }
        } elseif ($s.assert.needle_in_output.text) {
            if (-not $out.Contains($s.assert.needle_in_output.text)) { $hardFail = $true; $reasons += "output needle '$($s.assert.needle_in_output.text)' missing" }
        }
    }

    # HARD 1c — file_unchanged: listed paths must be byte-identical to the original fixture.
    if ($s.fixture -and $s.assert.file_unchanged) {
        $fxDir = Join-Path (Split-Path -Parent (Resolve-Path $Scenario)) $s.fixture
        foreach ($fu in @($s.assert.file_unchanged)) {
            $orig = Join-Path $fxDir $fu
            $proj = Join-Path $ProjectDir $fu
            $same = (Test-Path $orig) -and (Test-Path $proj) -and `
                    ((Get-FileHash $orig).Hash -eq (Get-FileHash $proj).Hash)
            if (-not $same) { $hardFail = $true; $reasons += "file $fu changed (expected unchanged)" }
        }
    }

    # HARD 1d — tool_invoked: a named tool MUST (or, with absent:true, must NOT) have been called.
    # {name, args_regex?, absent?}. Name presence reads the always-present conversation[].toolCalls[];
    # args_regex matches the raw arguments JSON in conversation[].toolCallDetails[] (additive run.json
    # field). Mirrors e2e-run.sh HARD 1d. [[:space:]] -> \s and -cmatch to track grep ERE semantics.
    if ($s.assert.tool_invoked) {
        foreach ($ti in @($s.assert.tool_invoked)) {
            if (-not $ti.name) { continue }
            if ($ti.args_regex) {
                $trx = ($ti.args_regex -replace '\[\[:space:\]\]', '\s')
                $tc = @($run.conversation | ForEach-Object { $_.toolCallDetails } |
                        Where-Object { $_ -and $_.name -eq $ti.name -and $_.arguments -cmatch $trx }).Count
            } else {
                $tc = @($run.conversation | ForEach-Object { $_.toolCalls } |
                        Where-Object { $_ -eq $ti.name }).Count
            }
            if ($ti.absent -eq $true) {
                if ($tc -ne 0) { $hardFail = $true; $reasons += "tool $($ti.name) was called $tc x, want absent" }
            } else {
                if ($tc -lt 1) {
                    $argNote = if ($ti.args_regex) { " with args /$($ti.args_regex)/" } else { "" }
                    $hardFail = $true; $reasons += "tool $($ti.name) not invoked$argNote"
                }
            }
        }
    }

    # HARD 1e — agent_order (multi-agent): the listed agent names must appear in this relative order in
    # the real execution sequence (run.json .multiAgent.agents[], sorted by start time). Subsequence
    # check that proves depends_on was respected. Mirrors e2e-run.sh HARD 1e.
    if ($s.assert.agent_order) {
        $aexp = @($s.assert.agent_order)
        $aact = @($run.multiAgent.agents | ForEach-Object { $_.agentName } | Where-Object { $_ })
        $ai = 0
        foreach ($t in $aact) { if ($ai -lt $aexp.Count -and $t -eq $aexp[$ai]) { $ai++ } }
        if ($ai -lt $aexp.Count) {
            $hardFail = $true; $reasons += "agent_order not satisfied: expected subsequence [$($aexp -join ', ')], saw [$($aact -join ', ')]"
        }
    }

    # HARD 2 — build/compile succeeded (only when build_cmd is present).
    if ($s.assert.build_cmd) {
        if ($BuildExit -ne 0) { $hardFail = $true; $reasons += "build_cmd exit=$BuildExit" }
    }

    # HARD 2b — browser-smoke (docs/0071 §8.5 layer 2): when `smoke` is declared, the headless-Chromium
    # check must pass (exit 0). exit 2 = could not run (no node/playwright/browser) — still a HARD fail
    # with an install hint. Mirrors e2e-run.sh.
    if ($s.PSObject.Properties.Name -contains 'smoke') {
        if ($SmokeExit -eq 2) {
            $hardFail = $true; $reasons += "browser-smoke unavailable (install: npm i -D playwright && npx playwright install chromium)"
        } elseif ($SmokeExit -ne 0) {
            $hardFail = $true; $reasons += "browser-smoke failed (see smoke.log)"
        }
    }

    # HARD 3 — no silent context overflow (docs/0057).
    if ($s.assert.no_context_overflow -eq $true -and $run.metrics.contextOverflow -eq $true) {
        $hardFail = $true; $reasons += "context overflow (silent truncation)"
    }

    # SOFT — tool_order as a SUBSEQUENCE of the real call order (warn only).
    if ($s.assert.tool_order) {
        $expected = @($s.assert.tool_order)
        $actual   = @($run.conversation | ForEach-Object { $_.toolCalls } | Where-Object { $_ })
        $i = 0
        foreach ($t in $actual) { if ($i -lt $expected.Count -and $t -eq $expected[$i]) { $i++ } }
        if ($i -lt $expected.Count) {
            Write-Host "  WARN [soft] tool_order not satisfied: expected subsequence [$($expected -join ', ')], saw [$($actual -join ', ')]"
        }
    }
    if ($s.PSObject.Properties.Name -contains 'judge') {
        Write-Host "  NOTE [soft] judge.criteria present — run the LlmTaskVerifier judge separately; not a regression gate."
    }

    if (-not $hardFail) { return 'PASS' }
    return "FAIL ($($reasons -join '; '))"
}

# Map a run to one failure-mode bucket for the gate's classification (docs/0069). PASS -> none.
# Mirrors e2e-run.sh classify_failure_mode: overflow first, then a precise guardrail marker, then the
# coarse status mapping, then a verdict-text fallback. Robust to a missing/unparsable run.json.
function ConvertTo-FailureMode {
    param([string]$Verdict, [string]$RunJsonPath)
    if ($Verdict -like 'PASS*') { return 'none' }
    $overflow = $false; $status = 'UNKNOWN'; $marker = ''
    if (Test-Path $RunJsonPath) {
        try {
            $r = Get-Content -Raw $RunJsonPath | ConvertFrom-Json
            if ($r.metrics.contextOverflow -eq $true) { $overflow = $true }
            if ($r.session -and $r.session.status) { $status = [string]$r.session.status }
            if ($r.metrics.failureMarker) { $marker = [string]$r.metrics.failureMarker }
        } catch {}
    }
    if ($overflow) { return 'overflow' }
    switch ($marker) {
        'LOOP_ABORTED'     { return 'loop-aborted' }
        'NOOP_WRITE_STALL' { return 'noop-write-stall' }
    }
    switch ($status) {
        'CANCELED'   { return 'abort' }
        'INCOMPLETE' { return 'loop' }
        'FAILED'     { return 'agent-fail' }
        'UNKNOWN'    { return 'crash' }
    }
    if ($Verdict -like '*build_cmd exit*') { return 'build-fail' }
    return 'wrong-output'
}

# ---------------------------------------------------------------------------
# L3 judge (SOFT tier) - an external LLM reviews WHAT the agent changed, not just
# whether the deterministic assertions held. Catches "build passes but the change
# is off-topic" that HARD needles cannot see. Mirrors e2e-run.sh run_judge.
# ---------------------------------------------------------------------------

# Fallback object for any judge answer that cannot be normalized.
function Get-JudgeUnparseable {
    [pscustomobject]@{ verdict = 'FAIL'; confidence = 0; reasons = @('judge output unparseable') }
}

# Normalize an arbitrary LLM answer into a compact {verdict, confidence, reasons} object.
# Defensive: whole-string JSON, then a fenced ``` block, then the first {...} span. Anything that
# does not yield verdict PASS|FAIL collapses to the unparseable fallback. Mirrors parse_judge_output.
function ConvertFrom-JudgeOutput {
    param([string]$Raw)
    if (-not $Raw) { return (Get-JudgeUnparseable) }
    $cand = $null
    try { $cand = $Raw | ConvertFrom-Json } catch {}
    if (-not $cand) {
        # Fenced block, if any, else the widest {...} span on the flattened text.
        $body = $null
        $fence = [regex]::Match($Raw, '(?s)```[a-zA-Z]*\s*(.*?)```')
        if ($fence.Success) { $body = $fence.Groups[1].Value }
        if (-not $body) {
            $flat = $Raw -replace "`r?`n", ' '
            $span = [regex]::Match($flat, '\{.*\}')
            if ($span.Success) { $body = $span.Value }
        }
        if ($body) { try { $cand = $body | ConvertFrom-Json } catch {} }
    }
    if (-not $cand -or $cand -isnot [System.Management.Automation.PSCustomObject]) { return (Get-JudgeUnparseable) }
    $v = if ($cand.verdict) { [string]$cand.verdict } else { '' }
    if ($v -ne 'PASS' -and $v -ne 'FAIL') { return (Get-JudgeUnparseable) }
    $conf = 0
    if ($null -ne $cand.confidence) { try { $conf = [double]$cand.confidence } catch { $conf = 0 } }
    $reasons = @()
    if ($null -ne $cand.reasons) { $reasons = @($cand.reasons | ForEach-Object { [string]$_ }) }
    return [pscustomobject]@{ verdict = $v; confidence = $conf; reasons = $reasons }
}

# Harness artifacts written into the work dir are excluded from the judge diff so the judge sees only
# the agent's changes. Matches the .sh diff -x list: run.json build.log server.log smoke.log
# .e2e-prompt.md .judge* build .refio (each matched as a path SEGMENT, at any depth, like diff -x).
function Test-JudgeDiffExcluded {
    param([string]$RelPath)
    foreach ($seg in ($RelPath -split '[\\/]')) {
        if ($seg -in @('run.json', 'build.log', 'server.log', 'smoke.log', '.e2e-prompt.md', 'build', '.refio')) { return $true }
        if ($seg -like '.judge*') { return $true }
    }
    return $false
}

# Recursive diff of fixture vs post-run project, harness artifacts excluded. Uses `git diff
# --no-index` per differing file when git is on PATH (real unified diffs); otherwise a
# PowerShell-native +/- line comparison. Returns one text blob (uncapped; caller truncates).
function Get-JudgeDiff {
    param([string]$Fixture, [string]$Work)
    $rel = { param($Root, $Full) $Full.Substring($Root.Length).TrimStart('\', '/') -replace '\\', '/' }
    $files = @{}
    foreach ($f in @(Get-ChildItem -Recurse -File -Path $Fixture -ErrorAction SilentlyContinue)) {
        $r = & $rel $Fixture $f.FullName
        if (-not (Test-JudgeDiffExcluded $r)) { $files[$r] = $true }
    }
    foreach ($f in @(Get-ChildItem -Recurse -File -Path $Work -ErrorAction SilentlyContinue)) {
        $r = & $rel $Work $f.FullName
        if (-not (Test-JudgeDiffExcluded $r)) { $files[$r] = $true }
    }
    $git = Get-Command git -ErrorAction SilentlyContinue
    $sb = New-Object System.Text.StringBuilder
    foreach ($r in ($files.Keys | Sort-Object)) {
        $a = Join-Path $Fixture $r
        $b = Join-Path $Work $r
        $aEx = Test-Path $a -PathType Leaf
        $bEx = Test-Path $b -PathType Leaf
        if ($aEx -and $bEx -and ((Get-FileHash $a).Hash -eq (Get-FileHash $b).Hash)) { continue }
        if ($git) {
            # git diff --no-index exits 1 on differences - expected, not an error.
            $savedEAP = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $aArg = if ($aEx) { $a } else { 'NUL' }
                $bArg = if ($bEx) { $b } else { 'NUL' }
                $out = & $git.Source diff --no-index --no-color -- $aArg $bArg 2>$null
                if ($out) { [void]$sb.AppendLine(($out -join "`n")) }
            } catch {} finally { $ErrorActionPreference = $savedEAP }
        } else {
            # Native fallback: header + -/+ lines via Compare-Object (unordered but judge-readable).
            [void]$sb.AppendLine("--- a/$r"); [void]$sb.AppendLine("+++ b/$r")
            $aLines = if ($aEx) { @(Get-Content $a) } else { @() }
            $bLines = if ($bEx) { @(Get-Content $b) } else { @() }
            foreach ($d in @(Compare-Object $aLines $bLines)) {
                $mark = if ($d.SideIndicator -eq '<=') { '-' } else { '+' }
                [void]$sb.AppendLine("$mark$($d.InputObject)")
            }
        }
    }
    return $sb.ToString()
}

# Run the judge for one scenario. Returns a {verdict, confidence, reasons} object, or $null when
# judging is not applicable (no JUDGE_MODEL, judge==tested model, multi-agent scenario without a
# prompt). Never throws to the caller: error paths degrade to the unparseable FAIL verdict or $null.
function Invoke-Judge {
    param([string]$Scenario, [string]$Work, [string]$Fixture, [string]$PromptFile)
    $judgeModel = $env:JUDGE_MODEL
    if (-not $judgeModel) { return $null }
    if ($Model -and $judgeModel -eq $Model) {
        Write-Host "  WARN judge skipped: JUDGE_MODEL equals the tested model ($Model) - a model must not grade itself"
        return $null
    }
    if (-not $PromptFile -or -not (Test-Path $PromptFile)) {
        Write-Host "  NOTE judge skipped: no prompt file for this scenario (multi-agent runs are not judged yet)"
        return $null
    }
    try {
        $s = Get-Content -Raw $Scenario | ConvertFrom-Json
        # judge_criteria (string or array), legacy fallback judge.criteria.
        $criteria = @()
        $rawCrit = $null
        if ($null -ne $s.judge_criteria) { $rawCrit = $s.judge_criteria }
        elseif ($s.judge -and $null -ne $s.judge.criteria) { $rawCrit = $s.judge.criteria }
        if ($null -ne $rawCrit) { $criteria = @($rawCrit | ForEach-Object { [string]$_ } | Where-Object { $_ }) }

        $diffText = Get-JudgeDiff -Fixture $Fixture -Work $Work
        if ($diffText.Length -gt 24000) { $diffText = $diffText.Substring(0, 24000) }
        $buildOut = ''
        $buildLog = Join-Path $Work 'build.log'
        if (Test-Path $buildLog) {
            $buildOut = Get-Content -Raw $buildLog
            if ($buildOut.Length -gt 8000) { $buildOut = $buildOut.Substring(0, 8000) }
        }

        $nl = "`n"
        $p = New-Object System.Text.StringBuilder
        [void]$p.Append("You are an impartial code-review judge for an automated coding-agent benchmark.$nl")
        [void]$p.Append("A coding agent was given the TASK below and produced the DIFF against the original project.$nl")
        [void]$p.Append("Decide whether the change actually accomplishes the task (not just compiles).$nl$nl")
        [void]$p.Append("Respond with ONLY a JSON object, no prose, no markdown fence:$nl")
        [void]$p.Append('{"verdict": "PASS" or "FAIL", "confidence": <number 0-1>, "reasons": ["short reason", ...]}')
        [void]$p.Append("$nl$nl## TASK$nl")
        [void]$p.Append((Get-Content -Raw $PromptFile))
        [void]$p.Append($nl)
        if ($criteria.Count -gt 0) {
            [void]$p.Append("$nl## EVALUATION CRITERIA$nl")
            foreach ($c in $criteria) { [void]$p.Append("- $c$nl") }
        }
        [void]$p.Append("$nl## DIFF (original project vs after the agent's run)$nl")
        [void]$p.Append('```diff'); [void]$p.Append($nl)
        if ($diffText.Trim()) { [void]$p.Append($diffText.TrimEnd()); [void]$p.Append($nl) } else { [void]$p.Append("(no file changes)$nl") }
        [void]$p.Append('```'); [void]$p.Append($nl)
        if ($buildOut) {
            [void]$p.Append("$nl## BUILD/TEST OUTPUT$nl")
            [void]$p.Append('```'); [void]$p.Append($nl)
            [void]$p.Append($buildOut.TrimEnd()); [void]$p.Append($nl)
            [void]$p.Append('```'); [void]$p.Append($nl)
        }
        $judgePrompt = Join-Path $Work '.judge-prompt.md'
        Set-Content -Path $judgePrompt -Value $p.ToString() -NoNewline -Encoding UTF8

        # The judge gets its own empty throwaway project: CHAT mode makes no tool calls, and the
        # sandbox must not point at the mutated project under evaluation.
        $jproj = Join-Path ([System.IO.Path]::GetTempPath()) ("refio-e2e-judge-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
        New-Item -ItemType Directory -Path $jproj | Out-Null
        $jrun = Join-Path $jproj 'judge-run.json'
        try {
            Invoke-Cli -CliArgs @(
                '--headless', '-p', $jproj, '--mode', 'CHAT', '--model', $judgeModel,
                '--prompt-file', $judgePrompt, '--output', 'json', '--output-file', $jrun,
                '--max-cost', $MaxCost) | Out-Null
            $raw = ''
            if (Test-Path $jrun) {
                try {
                    $jr = Get-Content -Raw $jrun | ConvertFrom-Json
                    if ($null -ne $jr.finalOutput) { $raw = [string]$jr.finalOutput }
                } catch {}
            }
            return (ConvertFrom-JudgeOutput -Raw $raw)
        } finally {
            Remove-Item -Recurse -Force $jproj -ErrorAction SilentlyContinue
        }
    } catch {
        return (Get-JudgeUnparseable)
    }
}

# Persist this run for the stabilization gate (docs/0069). No-op unless E2E_OUT_DIR is set, so default
# runs are completely unchanged. Writes <out>\<id>__<model>__<run>.run.json plus one results.jsonl
# record. Never throws (all failure paths swallowed) - the gate is observe-only. Mirrors
# e2e-run.sh emit_result_record; -ModelLabelOverride lets -SelfTest exercise this without touching the
# script-scope $Model (a plain assignment inside a function would only shadow it locally).
function Write-ResultRecord {
    param([string]$Id, [string]$Verdict, [string]$RunJsonPath, [string]$ModelLabelOverride = $script:Model,
          $Judge = $null)
    $outDir = $env:E2E_OUT_DIR
    if (-not $outDir) { return }
    $runIdx = $env:E2E_RUN_INDEX
    if (-not $runIdx -or $runIdx -notmatch '^\d+$') { $runIdx = 1 } else { $runIdx = [int]$runIdx }
    $modelLabel = if ($ModelLabelOverride) { $ModelLabelOverride } else { 'default' }
    $status = 'UNKNOWN'; $cost = 0; $tokens = 0; $mode = ''; $provider = ''
    $tokensIn = 0; $iters = 0; $apiCalls = 0; $duration = 0
    $tools = [ordered]@{}; $apiErrors = [ordered]@{}
    if (Test-Path $RunJsonPath) {
        try {
            $r = Get-Content -Raw $RunJsonPath | ConvertFrom-Json
            if ($r.session -and $r.session.status) { $status = [string]$r.session.status }
            if ($null -ne $r.metrics.costUsd) { $cost = $r.metrics.costUsd }
            if ($null -ne $r.metrics.tokensOut) { $tokens = $r.metrics.tokensOut }
            if ($r.session -and $r.session.mode) { $mode = [string]$r.session.mode }
            if ($r.session -and $r.session.provider) { $provider = [string]$r.session.provider }
            if ($null -ne $r.metrics.tokensIn) { $tokensIn = $r.metrics.tokensIn }
            if ($null -ne $r.metrics.toolCallCount) { $iters = $r.metrics.toolCallCount }
            if ($null -ne $r.metrics.apiCallCount) { $apiCalls = $r.metrics.apiCallCount }
            if ($null -ne $r.metrics.durationMs) { $duration = $r.metrics.durationMs }
            foreach ($t in @($r.conversation | ForEach-Object { $_.toolCalls } | Where-Object { $_ })) {
                if ($tools.Contains($t)) { $tools[$t]++ } else { $tools[$t] = 1 }
            }
            foreach ($e in @($r.apiLogs | ForEach-Object { $_.errorType } | Where-Object { $_ })) {
                if ($apiErrors.Contains($e)) { $apiErrors[$e]++ } else { $apiErrors[$e] = 1 }
            }
        } catch {}
    }
    $fmode = ConvertTo-FailureMode -Verdict $Verdict -RunJsonPath $RunJsonPath
    $vlabel = if ($Verdict -like 'PASS*') { 'PASS' } else { 'FAIL' }
    $reasons = @()
    if ($Verdict -match '^FAIL \((.*)\)$' -and $Matches[1]) { $reasons = @($Matches[1] -split '; ') }
    try {
        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
        # ":" is illegal in Windows filenames (model names carry it, e.g. "ollama/qwen3.5:9b") -
        # e2e-run.sh only strips "/" since it targets Unix; strip both here.
        $safeModel = $modelLabel -replace '[/:]', '-'
        if (Test-Path $RunJsonPath) {
            Copy-Item $RunJsonPath (Join-Path $outDir "${Id}__${safeModel}__${runIdx}.run.json") -Force -ErrorAction SilentlyContinue
        }
        $record = [ordered]@{
            scenario = $Id; model = $modelLabel; run = $runIdx
            verdict = $vlabel; failure_mode = $fmode; status = $status
            costUsd = $cost; tokensOut = $tokens
            mode = $mode; provider = $provider; tokensIn = $tokensIn
            iterations = $iters; apiCalls = $apiCalls; durationMs = $duration
            tools = $tools; apiErrors = $apiErrors
            # SOFT judge verdict object, or null when the run was not judged (mirrors e2e-run.sh).
            judge = $Judge
            reasons = $reasons
        }
        ($record | ConvertTo-Json -Compress -Depth 6) | Add-Content -Path (Join-Path $outDir 'results.jsonl')
    } catch {}
}

function Invoke-Scenario {
    param([string]$Scenario)
    if (-not (Test-Path $Scenario)) { throw "scenario not found: $Scenario" }
    $sdir = Split-Path -Parent (Resolve-Path $Scenario)
    $s = Get-Content -Raw $Scenario | ConvertFrom-Json
    $fixture = Join-Path $sdir $s.fixture
    # A scenario drives the turn with either a prompt_file (single agent) or a multi_agent YAML.
    $multiAgentFile = $null
    if ($s.multi_agent) {
        $multiAgentFile = Join-Path $sdir $s.multi_agent
        if (-not (Test-Path $multiAgentFile)) { throw "multi_agent file not found: $multiAgentFile" }
        $promptFile = $null
    } else {
        $promptFile = Join-Path $sdir $s.prompt_file
        if (-not (Test-Path $promptFile)) { throw "prompt_file not found: $promptFile" }
    }
    if (-not (Test-Path $fixture)) { throw "fixture dir not found: $fixture" }

    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("refio-e2e-{0}-{1}" -f $s.id, [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $work | Out-Null
    Copy-Item -Recurse -Force (Join-Path $fixture '*') $work
    $runJson = Join-Path $work 'run.json'

    # Optional fixture server (mirrors e2e-run.sh): serve a directory from the temp project over
    # loopback so the agent can drive http_request/fetch_webpage against a deterministic local endpoint.
    # The prompt's {{FIXTURE_SERVER}} placeholder becomes the base URL; the loopback opt-in
    # (security.allow_loopback) is enabled for this run only.
    $server = $null
    $effectivePrompt = $promptFile
    if ($s.fixture_server -and $s.fixture_server.dir -and $s.fixture_server.port) {
        $fsPort = [int]$s.fixture_server.port
        $fsDir  = Join-Path $work $s.fixture_server.dir
        $py = Get-Command python -ErrorAction SilentlyContinue
        if (-not $py) { $py = Get-Command python3 -ErrorAction SilentlyContinue }
        if (-not $py) { throw "fixture_server needs python/python3 on PATH" }
        if ($s.fixture_server.cmd) {
            # Custom server command (e.g. an intentionally-vulnerable CTF fixture) instead of the
            # stdlib static file server. {{PORT}} and {{DIR}} (the absolute served dir) are
            # substituted. Scenario cmds are authored bash-style ("exec python3 ..."); strip the
            # leading `exec` (a bash builtin) and map python3 to the resolved interpreter so the
            # same scenario runs on Windows. Mirrors e2e-run.sh fixture_server.cmd.
            $rendered = ($s.fixture_server.cmd -replace '\{\{PORT\}\}', "$fsPort") -replace '\{\{DIR\}\}', $fsDir
            $parts = @(($rendered -replace '^\s*exec\s+', '') -split '\s+' | Where-Object { $_ })
            $exeName = $parts[0]
            $exe = Get-Command $exeName -ErrorAction SilentlyContinue
            if (-not $exe -and $exeName -match '^python3?$') { $exe = $py }
            if (-not $exe) { throw "fixture_server cmd executable not found: $exeName" }
            $spArgs = @{
                FilePath = $exe.Source; PassThru = $true; WindowStyle = 'Hidden'
                RedirectStandardOutput = (Join-Path $work 'server.log')
                RedirectStandardError  = (Join-Path $work 'server.err.log')
            }
            $exeArgs = @($parts | Select-Object -Skip 1)
            if ($exeArgs.Count -gt 0) { $spArgs['ArgumentList'] = $exeArgs }
            $server = Start-Process @spArgs
        } else {
            $server = Start-Process -FilePath $py.Source -PassThru -WindowStyle Hidden -ArgumentList @(
                '-m', 'http.server', "$fsPort", '--bind', '127.0.0.1', '--directory', $fsDir)
        }
        $ready = $false
        for ($i = 0; $i -lt 50; $i++) {
            try { $c = New-Object System.Net.Sockets.TcpClient('127.0.0.1', $fsPort); $c.Close(); $ready = $true; break }
            catch { Start-Sleep -Milliseconds 100 }
        }
        if (-not $ready) { Write-Host "  WARN fixture_server not ready on 127.0.0.1:$fsPort after 5s" }
        $effectivePrompt = Join-Path $work '.e2e-prompt.md'
        (Get-Content -Raw $promptFile) -replace '\{\{FIXTURE_SERVER\}\}', "http://127.0.0.1:$fsPort" |
            Set-Content -NoNewline $effectivePrompt
    }

    $maxIter = if ($s.max_iterations) { $s.max_iterations } else { 20 }
    $mode    = if ($s.mode) { $s.mode } else { 'AGENT' }
    $cliArgs = @(
        '--headless', '-p', $work, '--mode', $mode,
        '--output', 'json', '--output-file', $runJson,
        '--debug-level', 'standard',
        '--config', "agent.max_iterations=$maxIter",
        '--max-cost', $MaxCost
    )
    # Single-agent scenarios pass --prompt-file; multi-agent ones pass --multi-agent <yaml> instead.
    if ($multiAgentFile) { $cliArgs += @('--multi-agent', $multiAgentFile) }
    else                 { $cliArgs += @('--prompt-file', $effectivePrompt) }
    if ($s.fixture_server -and $s.fixture_server.dir -and $s.fixture_server.port) {
        $cliArgs += @('--config', 'security.allow_loopback=true')
    }
    if ($Model) { $cliArgs += @('--model', $Model) }
    # Approve verification commands so a model that compiles/tests its own edit is not failed by a
    # headless rejection (see -AutoApprove above). Empty (via -NoAutoApprove) restores the raw
    # "reject every ASK command" behaviour.
    if ($AutoApprove) { $cliArgs += @('--auto-approve', $AutoApprove) }
    # -OllamaHost / -OllamaCtx are sugar over the validated config overrides so testing a model on a
    # different Ollama box (or a different context size) needs no raw key. Host accepts "box",
    # "box:11434", or "http://box:11434"; a bare host/port becomes http://host:11434.
    if ($OllamaHost) {
        $endpoint = switch -Regex ($OllamaHost) {
            '^https?://' { $OllamaHost; break }
            ':\d+$'      { "http://$OllamaHost"; break }
            default      { "http://${OllamaHost}:11434" }
        }
        $cliArgs += @('--config', "providers.ollama.ollama_endpoint=$endpoint")
    }
    if ($OllamaCtx -gt 0) { $cliArgs += @('--config', "providers.ollama.ollama_context_size=$OllamaCtx") }
    # Run-scope config overrides (-Config k=v ...). Applied last, so an explicit -Config wins over the
    # -OllamaHost/-OllamaCtx sugar above for the same key.
    foreach ($kv in $Config) { $cliArgs += @('--config', $kv) }

    Write-Host "> $($s.id) (mode=$mode, max_cost=$MaxCost) -> $work"
    $cliExit = Invoke-Cli -CliArgs $cliArgs
    # The server is only needed during the turn; stop it before build/assert (and before any early
    # return below) so no python process is left running.
    if ($server) { try { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue } catch {} }
    if (-not (Test-Path $runJson)) {
        Write-Output "| $($s.id) | FAIL (no run.json produced) | - |"
        Write-ResultRecord -Id $s.id -Verdict 'FAIL (no run.json produced)' -RunJsonPath $runJson
        return $false
    }

    # A non-zero CLI exit is a HARD failure: the headless turn aborted (cost ceiling, crash). It must
    # never be papered over by deterministic assertions the starting fixture happens to satisfy
    # (docs/0061). Mirrors e2e-run.sh. The status gate in Assert-Run is the in-run.json equivalent.
    if ($cliExit -ne 0) {
        $run = Get-Content -Raw $runJson | ConvertFrom-Json
        $st = if ($run.session -and $run.session.status) { $run.session.status } else { '?' }
        $verdictText = "FAIL (headless CLI exit=$cliExit)"
        Write-Output "| $($s.id) | $verdictText | status=$st cli_exit=$cliExit cost=`$$($run.metrics.costUsd) |"
        Write-ResultRecord -Id $s.id -Verdict $verdictText -RunJsonPath $runJson
        if (-not $Keep) { Remove-Item -Recurse -Force $work }
        return $false
    }

    $buildExit = 0
    if ($s.assert.build_cmd) {
        Push-Location $work
        # A native command writing to stderr (e.g. 'kotlinc' not on PATH) becomes a PowerShell error
        # record that, under -EAP Stop, terminates the whole -All run. Drop to Continue around the call
        # so a missing/failing build tool FAILs only its own scenario (its exit code is captured below),
        # mirroring e2e-run.sh. The catch covers any residual terminating error.
        $savedEAP = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { cmd /c $s.assert.build_cmd 1>"$work\build.log" 2>&1; $buildExit = $LASTEXITCODE }
        catch { $buildExit = 1 }
        finally { $ErrorActionPreference = $savedEAP; Pop-Location }
    }

    # Optional browser-smoke (docs/0071 §8.5): render the produced artifact in headless Chromium. Needs
    # node + playwright; exit 2 (unavailable) is surfaced as a HARD fail by Assert-Run, not hidden.
    # Same EAP-Continue guard as build_cmd above: a native command whose stderr is merged via 2>&1
    # into a redirected file still gets each stderr line wrapped as a PowerShell ErrorRecord, which a
    # missing playwright install (node writes to stderr) turns into a terminating error under this
    # script's global $ErrorActionPreference='Stop' - killing the entire -All run after this one
    # scenario instead of failing just it (observed 2026-07-01: every model's -All run died at the
    # first smoke-asserting scenario, silently dropping the remaining ~35 scenarios).
    $smokeExit = 0
    if ($s.PSObject.Properties.Name -contains 'smoke') {
        $node = Get-Command node -ErrorAction SilentlyContinue
        if ($node) {
            $smokeScript = Join-Path $ScriptDir 'browser-smoke.mjs'
            $savedEAP = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { & $node.Source $smokeScript $Scenario $work 1>"$work\smoke.log" 2>&1; $smokeExit = $LASTEXITCODE }
            catch { $smokeExit = 1 }
            finally { $ErrorActionPreference = $savedEAP }
        } else {
            'node not found on PATH' | Set-Content "$work\smoke.log"; $smokeExit = 2
        }
    }

    $verdict = Assert-Run -Scenario $Scenario -RunJsonPath $runJson -ProjectDir $work -BuildExit $buildExit -SmokeExit $smokeExit
    $run = Get-Content -Raw $runJson | ConvertFrom-Json
    Write-Output "| $($s.id) | $verdict | status=$($run.session.status) build_exit=$buildExit cost=`$$($run.metrics.costUsd) |"

    # SOFT judge tier: runs only when JUDGE_MODEL is set; never changes the HARD verdict.
    $judge = Invoke-Judge -Scenario $Scenario -Work $work -Fixture $fixture -PromptFile $effectivePrompt
    if ($judge) {
        $jReasons = ($judge.reasons -join '; ')
        if ($judge.verdict -eq 'PASS') {
            Write-Host "  NOTE [soft] judge PASS: confidence=$($judge.confidence) reasons=[$jReasons]"
        } else {
            Write-Host "  WARN [soft] judge $($judge.verdict): confidence=$($judge.confidence) reasons=[$jReasons] (advisory only, does not fail the run)"
        }
    }

    Write-ResultRecord -Id $s.id -Verdict $verdict -RunJsonPath $runJson -Judge $judge
    if (-not $Keep) { Remove-Item -Recurse -Force $work }
    return ($verdict -like 'PASS*')
}

function Invoke-SelfTest {
    Write-Host "# e2e-run.ps1 self-test (assertion engine only — no LLM call)"
    $sample = Join-Path $RepoRoot 'test_data\e2e\examples'
    $scen   = Join-Path $sample 'self-test.scenario.json'
    $proj   = Join-Path ([System.IO.Path]::GetTempPath()) ("refio-e2e-selftest-" + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path (Join-Path $proj 'src') -Force | Out-Null
    "fun f(x: String?) {`n    if (x != null) {`n        println(x)`n    }`n}" | Set-Content (Join-Path $proj 'src\Main.kt')

    $fails = 0
    $v = Assert-Run $scen (Join-Path $sample 'sample-run.pass.json')     $proj 0; Write-Host "  good-run      -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    $v = Assert-Run $scen (Join-Path $sample 'sample-run.failed.json')   $proj 0; Write-Host "  failed-status -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    $v = Assert-Run $scen (Join-Path $sample 'sample-run.overflow.json') $proj 0; Write-Host "  overflow      -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    $v = Assert-Run $scen (Join-Path $sample 'sample-run.pass.json')     $proj 1; Write-Host "  build-failed  -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    $empty = Join-Path ([System.IO.Path]::GetTempPath()) ("refio-e2e-empty-" + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path (Join-Path $empty 'src') -Force | Out-Null; '' | Set-Content (Join-Path $empty 'src\Main.kt')
    $v = Assert-Run $scen (Join-Path $sample 'sample-run.pass.json')     $empty 0; Write-Host "  needle-missing-> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 6: regex needle is shape-agnostic — one regex accepts both `!=` and `==` null guards.
    $rscen = Join-Path $proj 'regex-needle.scenario.json'
    '{ "id":"regex-needle","assert":{"needle_in_file":{"path":"src/Main.kt","regex":"x[[:space:]]*[!=]=[[:space:]]*null"}}}' | Set-Content $rscen
    $v = Assert-Run $rscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  regex-needle!= -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    "fun f(x: String?) {`n    if (x == null) return`n    println(x)`n}" | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $rscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  regex-needle== -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }

    # Case 7: needles_in_file[] — ALL must be present (helper defined AND used with the right arg).
    $ascen = Join-Path $proj 'array-needles.scenario.json'
    '{ "id":"array-needles","assert":{"needles_in_file":[{"path":"src/Main.kt","regex":"fun[[:space:]]+clamp[[:space:]]*\\("},{"path":"src/Main.kt","regex":"clamp[[:space:]]*\\([[:space:]]*score"}]}}' | Set-Content $ascen
    "fun clamp(value: Int, lo: Int, hi: Int) = maxOf(lo, minOf(hi, value))`nfun normalize(score: Int) = clamp(score, 0, 100)" | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $ascen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  array-needles  -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    "fun clamp(value: Int, lo: Int, hi: Int) = maxOf(lo, minOf(hi, value))`nfun normalize(score: Int) = score" | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $ascen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  array-missing  -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 8: scenario selection resolves a committed scenario by its id (not just by file path).
    $byid = Resolve-Scenario 'increase-retry-count'; Write-Host "  resolve-by-id  -> $(Split-Path -Leaf $byid)"; if ($byid -notlike '*increase-retry-count.json') { $fails = 1 }

    # Case 9: absent needle — pattern must NOT appear.
    $nscen = Join-Path $proj 'absent.scenario.json'
    '{ "id":"absent","assert":{"needle_in_file":{"path":"src/Main.kt","regex":"legacyName","absent":true}}}' | Set-Content $nscen
    'fun currentName() = 1' | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $nscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  absent-ok      -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    'fun legacyName() = 1' | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $nscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  absent-viol    -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 10: max_count — duplicate removed (<=1 line).
    $mscen = Join-Path $proj 'maxcount.scenario.json'
    '{ "id":"maxcount","assert":{"needle_in_file":{"path":"src/Main.kt","regex":"qty <= 0","max_count":1}}}' | Set-Content $mscen
    'fun v(qty: Int) { if (qty <= 0) error("x") }' | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $mscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  maxcount-ok    -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    "if (qty <= 0) a`nif (qty <= 0) b" | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $mscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  maxcount-viol  -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 11: min_count — helper called in >=2 places.
    $cscen = Join-Path $proj 'mincount.scenario.json'
    '{ "id":"mincount","assert":{"needle_in_file":{"path":"src/Main.kt","regex":"validate\\(","min_count":2}}}' | Set-Content $cscen
    "fun a() = validate(1)`nfun b() = validate(2)" | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $cscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  mincount-ok    -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    'fun a() = validate(1)' | Set-Content (Join-Path $proj 'src\Main.kt')
    $v = Assert-Run $cscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  mincount-viol  -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 12: needle_in_output — regex against run.json .finalOutput.
    $oscen = Join-Path $proj 'output.scenario.json'
    '{ "id":"output","assert":{"needle_in_output":{"regex":"null check"}}}' | Set-Content $oscen
    $v = Assert-Run $oscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  output-ok      -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    '{ "id":"output","assert":{"needle_in_output":{"regex":"nonexistent-phrase-xyz"}}}' | Set-Content $oscen
    $v = Assert-Run $oscen (Join-Path $sample 'sample-run.pass.json') $proj 0; Write-Host "  output-viol    -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 13: file_unchanged — must equal the original fixture byte-for-byte.
    $fxroot = Join-Path $proj 'fx'; New-Item -ItemType Directory -Path (Join-Path $fxroot 'src') -Force | Out-Null
    'object Frozen { const val V = 1 }' | Set-Content (Join-Path $fxroot 'src\Frozen.kt')
    $uscen = Join-Path $proj 'unchanged.scenario.json'
    '{ "id":"unchanged","fixture":"fx","assert":{"file_unchanged":["src/Frozen.kt"]}}' | Set-Content $uscen
    $uproj = Join-Path $proj 'up'; New-Item -ItemType Directory -Path (Join-Path $uproj 'src') -Force | Out-Null
    Copy-Item (Join-Path $fxroot 'src\Frozen.kt') (Join-Path $uproj 'src\Frozen.kt')
    $v = Assert-Run $uscen (Join-Path $sample 'sample-run.pass.json') $uproj 0; Write-Host "  unchanged-ok   -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    'object Frozen { const val V = 2 }' | Set-Content (Join-Path $uproj 'src\Frozen.kt')
    $v = Assert-Run $uscen (Join-Path $sample 'sample-run.pass.json') $uproj 0; Write-Host "  unchanged-viol -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 14: tool_invoked — by name / by args_regex / absent. Uses the subagent sample whose
    # conversation carries toolCallDetails (name + raw arguments JSON).
    $subrun = Join-Path $sample 'sample-run.subagent.json'
    $tscen  = Join-Path $proj 'tool-invoked.scenario.json'
    '{ "id":"tool-invoked","assert":{"tool_invoked":[{"name":"invoke_subagent"}]}}' | Set-Content $tscen
    $v = Assert-Run $tscen $subrun $proj 0; Write-Host "  tool-by-name   -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    '{ "id":"tool-invoked","assert":{"tool_invoked":[{"name":"invoke_subagent","args_regex":"code-reviewer"}]}}' | Set-Content $tscen
    $v = Assert-Run $tscen $subrun $proj 0; Write-Host "  tool-args-ok   -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    '{ "id":"tool-invoked","assert":{"tool_invoked":[{"name":"invoke_subagent","args_regex":"security-engineer"}]}}' | Set-Content $tscen
    $v = Assert-Run $tscen $subrun $proj 0; Write-Host "  tool-args-miss -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    '{ "id":"tool-invoked","assert":{"tool_invoked":[{"name":"delegate_to_strong_model"}]}}' | Set-Content $tscen
    $v = Assert-Run $tscen $subrun $proj 0; Write-Host "  tool-missing   -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    '{ "id":"tool-invoked","assert":{"tool_invoked":[{"name":"invoke_subagent","absent":true}]}}' | Set-Content $tscen
    $v = Assert-Run $tscen $subrun $proj 0; Write-Host "  tool-absent-vio-> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 15: agent_order — listed agents must appear in execution order in .multiAgent.agents[].
    $marun  = Join-Path $sample 'sample-run.multiagent.json'
    $aoscen = Join-Path $proj 'agent-order.scenario.json'
    '{ "id":"agent-order","assert":{"agent_order":["analyst","coder"]}}' | Set-Content $aoscen
    $v = Assert-Run $aoscen $marun $proj 0; Write-Host "  agent-order-ok -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    '{ "id":"agent-order","assert":{"agent_order":["coder","analyst"]}}' | Set-Content $aoscen
    $v = Assert-Run $aoscen $marun $proj 0; Write-Host "  agent-order-rev-> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    '{ "id":"agent-order","assert":{"agent_order":["analyst","tester"]}}' | Set-Content $aoscen
    $v = Assert-Run $aoscen $marun $proj 0; Write-Host "  agent-order-miss-> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 16: browser-smoke HARD tier — gated on the runner's exit (5th arg). 0=pass, 1=fail, 2=unavailable.
    $smscen = Join-Path $proj 'smoke.scenario.json'
    '{ "id":"smoke","smoke":{"entry":"index.html","dom_present":["#x"]}}' | Set-Content $smscen
    $v = Assert-Run $smscen (Join-Path $sample 'sample-run.pass.json') $proj 0 0; Write-Host "  smoke-ok       -> $v"; if ($v -notlike 'PASS*') { $fails = 1 }
    $v = Assert-Run $smscen (Join-Path $sample 'sample-run.pass.json') $proj 0 1; Write-Host "  smoke-fail     -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }
    $v = Assert-Run $smscen (Join-Path $sample 'sample-run.pass.json') $proj 0 2; Write-Host "  smoke-unavail  -> $v"; if ($v -notlike 'FAIL*') { $fails = 1 }

    # Case 17: stabilization-gate emission (docs/0069) - failure-mode classifier + results.jsonl
    # record. Pure: no LLM, reuses the bundled sample run.json files. Pins the buckets a gate counts.
    $cm = ConvertTo-FailureMode 'PASS' (Join-Path $sample 'sample-run.pass.json')
    Write-Host "  classify-pass  -> $cm"; if ($cm -ne 'none') { $fails = 1 }
    $cm = ConvertTo-FailureMode 'FAIL (context overflow (silent truncation))' (Join-Path $sample 'sample-run.overflow.json')
    Write-Host "  classify-ovfl  -> $cm"; if ($cm -ne 'overflow') { $fails = 1 }
    $cm = ConvertTo-FailureMode 'FAIL (session.status=FAILED (want SUCCESS))' (Join-Path $sample 'sample-run.failed.json')
    Write-Host "  classify-fail  -> $cm"; if ($cm -ne 'agent-fail') { $fails = 1 }
    $cm = ConvertTo-FailureMode 'FAIL (build_cmd exit=1)' (Join-Path $sample 'sample-run.pass.json')
    Write-Host "  classify-build -> $cm"; if ($cm -ne 'build-fail') { $fails = 1 }
    $cm = ConvertTo-FailureMode 'FAIL (/x/ matched 0 line(s) in src/Main.kt, want [1..1000000])' (Join-Path $sample 'sample-run.pass.json')
    Write-Host "  classify-wrong -> $cm"; if ($cm -ne 'wrong-output') { $fails = 1 }
    $cm = ConvertTo-FailureMode 'FAIL (session.status=INCOMPLETE (want SUCCESS))' (Join-Path $sample 'sample-run.loop-aborted.json')
    Write-Host "  classify-loop  -> $cm"; if ($cm -ne 'loop-aborted') { $fails = 1 }
    $cm = ConvertTo-FailureMode 'FAIL (session.status=INCOMPLETE (want SUCCESS))' (Join-Path $sample 'sample-run.noop-stall.json')
    Write-Host "  classify-noop  -> $cm"; if ($cm -ne 'noop-write-stall') { $fails = 1 }

    # Write-ResultRecord writes a named run.json copy + one valid JSONL verdict record into
    # $env:E2E_OUT_DIR. -ModelLabelOverride avoids touching the script-scope $Model.
    $gateOut = Join-Path ([System.IO.Path]::GetTempPath()) ("refio-e2e-gate-" + [guid]::NewGuid().ToString('N').Substring(0,8))
    $prevOutDir = $env:E2E_OUT_DIR; $prevRunIdx = $env:E2E_RUN_INDEX
    $env:E2E_OUT_DIR = $gateOut; $env:E2E_RUN_INDEX = '3'
    try {
        Write-ResultRecord -Id 'demo-scn' -Verdict 'PASS' -RunJsonPath (Join-Path $sample 'sample-run.pass.json') -ModelLabelOverride 'ollama/qwen3.5:4b'
    } finally {
        $env:E2E_OUT_DIR = $prevOutDir; $env:E2E_RUN_INDEX = $prevRunIdx
    }
    $rec = Get-Content (Join-Path $gateOut 'results.jsonl') -Tail 1 | ConvertFrom-Json
    Write-Host "  gate-emit      -> scenario=$($rec.scenario) run=$($rec.run) verdict=$($rec.verdict) tools=$(@($rec.tools.PSObject.Properties).Count)"
    if ($rec.verdict -ne 'PASS') { Write-Host "  !! emitted record must have verdict PASS"; $fails = 1 }
    if ($rec.run -ne 3) { Write-Host "  !! emitted record must carry run index 3"; $fails = 1 }
    if ($rec.scenario -ne 'demo-scn') { Write-Host "  !! emitted record must carry the scenario id"; $fails = 1 }
    if (-not (Test-Path (Join-Path $gateOut 'demo-scn__ollama-qwen3.5-4b__3.run.json'))) { Write-Host "  !! emitted run.json copy must exist"; $fails = 1 }
    if ($rec.tools.grep_search -ne 1) { Write-Host "  !! emitted record must carry a per-tool histogram (grep_search=1)"; $fails = 1 }
    if (@($rec.tools.PSObject.Properties).Count -ne 3) { Write-Host "  !! emitted record tools histogram must have 3 distinct tools"; $fails = 1 }
    if ($rec.iterations -ne 3) { Write-Host "  !! emitted record must carry iterations=3 (toolCallCount)"; $fails = 1 }
    Remove-Item -Recurse -Force $gateOut

    Remove-Item -Recurse -Force $proj, $empty
    if ($fails -eq 0) { Write-Host 'self-test OK' } else { throw 'self-test FAILED' }
}

if ($SelfTest) { Invoke-SelfTest; exit 0 }
if ($List) { Show-Scenarios; exit 0 }

# Fail fast (mirrors e2e-run.sh's `die` before it wastes an -All run on 41 back-to-back "CLI not
# found" scenario failures).
if (-not (Get-Command $Cli -ErrorAction SilentlyContinue) -and -not (Test-Path $Cli)) {
    throw "CLI not found/executable: $Cli (build it: ./gradlew :cli:installDist)"
}

# cli.bat is a legacy Windows batch launcher: PowerShell's `&` operator has to shell out through
# cmd.exe to run a .bat, and cmd.exe RE-PARSES shell metacharacters (| & ( )) in any argument - even
# a PowerShell-quoted one - as pipes/grouping. A value like -AutoApprove's default regex
# ('\b(kotlinc|gradlew|...)\b') gets torn apart into separate "commands" (kotlinc, gradlew, ...) and
# cli.bat never actually runs: no logback output, no run.json, every single scenario reads as a bare
# "no run.json produced" FAIL in well under a second (this broke every run 2026-07-01 across all 6
# models before being caught). java.exe is a native PE executable, so PowerShell hands it each array
# element as a literal argv entry with no shell re-parsing - invoke java directly, bypassing cli.bat.
$JavaExe = $null
$CliClasspath = $null
if ($Cli -match '\.(bat|cmd)$') {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $candidate) { $JavaExe = $candidate }
    }
    if (-not $JavaExe) {
        $found = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($found) { $JavaExe = $found.Source }
    }
    $libDir = Join-Path (Split-Path -Parent $Cli) '..\lib'
    if (Test-Path $libDir) {
        $CliClasspath = (Get-ChildItem -Path $libDir -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
    }
    if (-not $JavaExe -or -not $CliClasspath) {
        throw "could not resolve java.exe/classpath for $Cli (set JAVA_HOME, or check cli/build/install/cli/lib exists - build it: ./gradlew :cli:installDist)"
    }
}

# Runs the CLI (bypassing cli.bat via java.exe when $Cli is a .bat, see above) and returns its exit
# code. Centralizing this also means a future argument containing shell metacharacters never has to
# be reasoned about at each call site.
# `| Out-Host` is required, not cosmetic: a bare native-command call inside a function sends its
# stdout/stderr lines onto the function's own OUTPUT stream, so `$cliExit = Invoke-Cli ...` at the
# call site would capture the CLI's entire console output (logback banner included) instead of the
# exit code - Out-Host drains that stream to the console immediately, leaving only the trailing
# `return $LASTEXITCODE` on the function's output.
# `2>&1` + EAP=Continue: the CLI writes its startup banner and HeadlessTurnListener progress to
# stderr. Piping a native command merges its stderr into the pipeline as ErrorRecords, which under
# this script's global $ErrorActionPreference='Stop' turns the very first stderr line into a
# terminating error - killing the whole run at the first scenario before any run.json is produced.
# Merging stderr into the success stream (2>&1) keeps the progress visible as plain output without
# raising errors; the local EAP=Continue is belt-and-suspenders, mirroring the build_cmd/smoke guards.
function Invoke-Cli {
    param([string[]]$CliArgs)
    $savedEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($JavaExe -and $CliClasspath) {
            & $JavaExe '-cp' $CliClasspath 'pl.jclab.refio.cli.MainKt' @CliArgs 2>&1 | Out-Host
        } else {
            & $Cli @CliArgs 2>&1 | Out-Host
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedEAP
    }
}

# Build the run list: -All discovers every scenario; otherwise resolve each positional by id or path.
$resolved = @()
if ($All) {
    $resolved = @(Get-Scenarios)
    if (-not $resolved) { throw "no scenarios found in $E2EDir" }
} else {
    if (-not $Scenarios) { throw "no scenarios selected (try -List, -All, or pass <id|scenario.json>)" }
    foreach ($s in $Scenarios) { $resolved += (Resolve-Scenario $s) }
}

Write-Output "| scenario | verdict | metrics |"
Write-Output "|---|---|---|"
$overall = 0
foreach ($s in $resolved) {
    # Invoke-Scenario streams its verdict row via Write-Output AND returns a boolean on the same
    # success stream. Capturing it directly in `if (-not (Invoke-Scenario ...))` swallowed the row
    # (the table stayed empty) and mis-read the boolean. Capture everything, print only the table
    # row(s), and derive pass/fail from the row text so the verdict is always shown.
    $captured = @(Invoke-Scenario $s)
    $rows = @($captured | Where-Object { $_ -is [string] -and $_ -match '^\|' })
    $rows | ForEach-Object { Write-Output $_ }
    if (-not ($rows | Where-Object { $_ -match '\|\s*PASS' })) { $overall = 1 }
}
exit $overall
