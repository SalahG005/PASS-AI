# Export PASS-AI agent traces to a LoRA-ready chat JSONL (Unsloth / Axolotl style)
# Usage (from a workspace that has .passai/traces):
#   powershell -File export-traces.ps1 -WorkspaceRoot "C:\path\to\project" -OutFile dataset.jsonl

param(
  [Parameter(Mandatory = $true)]
  [string]$WorkspaceRoot,
  [string]$OutFile = "pass-ai-lora-dataset.jsonl"
)

$traceDir = Join-Path $WorkspaceRoot ".passai\traces"
if (-not (Test-Path $traceDir)) {
  Write-Error "No traces at $traceDir — run Agent tasks in PASS-AI first."
  exit 1
}

$outPath = if ([System.IO.Path]::IsPathRooted($OutFile)) { $OutFile } else { Join-Path (Get-Location) $OutFile }
if (Test-Path $outPath) { Remove-Item $outPath }

Get-ChildItem $traceDir -Filter "*.jsonl" | ForEach-Object {
  Get-Content $_.FullName | ForEach-Object {
    if (-not $_.Trim()) { return }
    $row = $_ | ConvertFrom-Json
    if (-not $row.messages) { return }
    $sample = @{
      messages = $row.messages
      meta = @{
        model = $row.model
        ts = $row.ts
        files = @($row.files | ForEach-Object { $_.path })
      }
    }
    ($sample | ConvertTo-Json -Compress -Depth 20) | Add-Content -Path $outPath -Encoding utf8
  }
}

Write-Host "Wrote $outPath"
Write-Host "Next: fine-tune Qwen2.5-Coder-7B with Unsloth, export GGUF, then:"
Write-Host "  ollama create pass-ai-tuned -f Modelfile.pass-ai-tuned"
Write-Host "  set app.ollama.agent-model=pass-ai-tuned"
