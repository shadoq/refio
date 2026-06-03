<#
.SYNOPSIS
    Run one prompt across several models, N times each, headless via refio.bat.

.DESCRIPTION
    For every model in $models and every run 1..$Runs the script substitutes
    {{MODEL_ID}} (filename-safe) and {{N}} (01..NN) into the prompt template, then
    drives refio in headless AGENT mode. Each run writes:
      - the model's output file(s) under -OutputDir (PathSandbox = --project)
      - a run.json with metrics (tokens/cost/durationMs) under -DebugDir

    refio has no template engine of its own (that was the deferred JS runner from
    docs/0059) — this loop does the substitution and refio gets a concrete prompt.

    Note: output files written BY the agent are sandboxed to -OutputDir, but run.json
    is written by the CLI process itself (--output-file), so -DebugDir may be anywhere,
    including outside -OutputDir.

.PARAMETER OutputDir
    Working dir where the agent writes model output files (sandbox = --project).

.PARAMETER DebugDir
    Dir for the run.json debug files. May live outside OutputDir.

.PARAMETER Runs
    Number of samples per model (numbered 01..NN). Default 6.

.EXAMPLE
    .\benchmark-models.ps1
    .\benchmark-models.ps1 -OutputDir "D:\bench\html" -DebugDir "D:\bench\debug" -Runs 10

.NOTES
    The prompt template MUST contain both placeholders so files stay unique:
        - Save the implementation to the file "website_museum_{{MODEL_ID}}_{{N}}.html"
    refio.bat runs the installed dist — if new flags aren't recognized, run:
        gradlew.bat -p "D:/_work/Saas/refio" :cli:installDist
#>
param(
    [string]$OutputDir    = "D:\_work\AiDevs\Agent",                           # katalog roboczy: tu agent zapisuje pliki modelu (sandbox = --project)
    [string]$DebugDir     = (Join-Path $OutputDir "_debug"),                  # katalog na pliki run.json (debug); może być poza OutputDir
    [int]   $Runs         = 1,                                              # liczba prób na model (01..NN)
    [string]$TemplateFile = "D:\_work\AiDevs\Agent\_prompts\website_museum.md",
    [string]$RefioBat     = "D:\_work\Saas\refio\refio.bat"
)
$ErrorActionPreference = "Stop"

# modele do porównania (surowe id, dokładnie jak do --model):
# Re-run na poprawionym diście (headless zawsze streamuje → brak 300s socket-idle timeoutu).
# Lista = modele które wcześniej timeoutowały (fix powinien je odblokować) + większe qwen3.5.
$models = @(
  "ollama/qwen3.5:4b",          # wcześniej timeout (JSON) → teraz streaming
  "ollama/qwen3.5:9b",          # wcześniej timeout (JSON) → teraz streaming
  "ollama/qwen3.5:27b",         # native, większy — test streamingu
  "ollama/qwen3.5:35b",         # native MoE (23GB) — wcześniej zakładany hang
  "ollama/devstral:24b"         # native, wcześniej timeout
#   "ollama/gpt-oss:20b",       # już SUCCESS w poprzednim sweepie
#   "ollama/gemma4:26b",        # już SUCCESS
#   "ollama/deepseek-r1:32b",   # już zmierzony (loop)
)

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $DebugDir  | Out-Null
$template = Get-Content -Raw $TemplateFile

foreach ($model in $models) {
  $safeId = $model -replace '[/:\\]', '-'        # ollama/qwen3.5:9b -> ollama-qwen3.5-9b
  foreach ($run in 1..$Runs) {
    $idx = "{0:D2}" -f $run                       # 01..06 — próba w obrębie tego modelu

    $prompt = $template.Replace("{{MODEL_ID}}", $safeId).Replace("{{N}}", $idx)
    $promptFile = Join-Path $env:TEMP "refio_prompt_${safeId}_${idx}.md"
    Set-Content -Path $promptFile -Value $prompt -Encoding UTF8

    $jsonOut = Join-Path $DebugDir "run_${safeId}_${idx}.json"
    Write-Host "=== $model  proba $idx / $Runs  -> $jsonOut ===" -ForegroundColor Cyan
    & $RefioBat `
        -p $OutputDir `
        --headless `
        --model $model `
        --prompt-file $promptFile `
        --output json `
        --output-file $jsonOut
  }
}
