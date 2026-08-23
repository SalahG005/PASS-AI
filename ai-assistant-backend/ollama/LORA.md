# PASS-AI LoRA fine-tune notes (local only — no paid APIs)
#
# After Agent tooling is stable, collect good traces then fine-tune lightly.
#
# 1) Traces are auto-saved under the workspace at:
#      .passai/traces/YYYY-MM-DD.jsonl
#    Each line is one successful agent turn (user → tools → proposals → reply).
#
# 2) Export a clean dataset (filter traces you liked):
#      # PowerShell
#      Get-Content .passai\traces\*.jsonl | Out-File dataset.jsonl
#
# 3) Train a LoRA on qwen2.5-coder-7b (example with Unsloth on a desktop GPU):
#      - Base: Qwen/Qwen2.5-Coder-7B-Instruct
#      - Format each JSONL row as chat messages (system/user/assistant)
#      - Rank 8–16, few hundred–few thousand examples is enough
#
# 4) Export GGUF / Modelfile and load in Ollama:
#      ollama create pass-ai-tuned -f Modelfile.pass-ai-tuned
#      # then set app.ollama.agent-model=pass-ai-tuned
#
# Specialize on: PASS tool JSON format, Angular+Spring stack, bilingual FR/EN.
# Do NOT full-pretrain. Prefer scaffolding + this LoRA only when tools are solid.
