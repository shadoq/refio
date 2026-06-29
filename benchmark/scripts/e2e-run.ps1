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
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Scenarios
)

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
        $server = Start-Process -FilePath $py.Source -PassThru -WindowStyle Hidden -ArgumentList @(
            '-m', 'http.server', "$fsPort", '--bind', '127.0.0.1', '--directory', $fsDir)
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
    & $Cli @cliArgs
    $cliExit = $LASTEXITCODE
    # The server is only needed during the turn; stop it before build/assert (and before any early
    # return below) so no python process is left running.
    if ($server) { try { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue } catch {} }
    if (-not (Test-Path $runJson)) { Write-Output "| $($s.id) | FAIL (no run.json produced) | - |"; return $false }

    # A non-zero CLI exit is a HARD failure: the headless turn aborted (cost ceiling, crash). It must
    # never be papered over by deterministic assertions the starting fixture happens to satisfy
    # (docs/0061). Mirrors e2e-run.sh. The status gate in Assert-Run is the in-run.json equivalent.
    if ($cliExit -ne 0) {
        $run = Get-Content -Raw $runJson | ConvertFrom-Json
        $st = if ($run.session -and $run.session.status) { $run.session.status } else { '?' }
        Write-Output "| $($s.id) | FAIL (headless CLI exit=$cliExit) | status=$st cli_exit=$cliExit cost=`$$($run.metrics.costUsd) |"
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
    $smokeExit = 0
    if ($s.PSObject.Properties.Name -contains 'smoke') {
        $node = Get-Command node -ErrorAction SilentlyContinue
        if ($node) {
            $smokeScript = Join-Path $ScriptDir 'browser-smoke.mjs'
            & $node.Source $smokeScript $Scenario $work 1>"$work\smoke.log" 2>&1
            $smokeExit = $LASTEXITCODE
        } else {
            'node not found on PATH' | Set-Content "$work\smoke.log"; $smokeExit = 2
        }
    }

    $verdict = Assert-Run -Scenario $Scenario -RunJsonPath $runJson -ProjectDir $work -BuildExit $buildExit -SmokeExit $smokeExit
    $run = Get-Content -Raw $runJson | ConvertFrom-Json
    Write-Output "| $($s.id) | $verdict | status=$($run.session.status) build_exit=$buildExit cost=`$$($run.metrics.costUsd) |"
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

    Remove-Item -Recurse -Force $proj, $empty
    if ($fails -eq 0) { Write-Host 'self-test OK' } else { throw 'self-test FAILED' }
}

if ($SelfTest) { Invoke-SelfTest; exit 0 }
if ($List) { Show-Scenarios; exit 0 }

if (-not (Get-Command $Cli -ErrorAction SilentlyContinue) -and -not (Test-Path $Cli)) {
    Write-Warning "CLI not found at $Cli (build it: ./gradlew :cli:installDist)"
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
