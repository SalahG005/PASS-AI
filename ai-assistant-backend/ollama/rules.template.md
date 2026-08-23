# PASS AI project rules (injected into every Chat / Agent turn)
# Edit this file in your workspace root as `.passai/rules.md`.

## Stack
- Prefer Angular + Spring Boot patterns already used in this repo.
- Keep changes minimal and match existing style.

## Agent
- Use write_file / pass-file for file changes; user reviews diffs before Apply.
- Do not suggest paid cloud models; Ollama local only.

## Static HTML/CSS sites
- styles.css must define :root design tokens; pages must look polished, not bare tutorial HTML.
- Keep one consistent palette and typography across phases and pages.
