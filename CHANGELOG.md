# Changelog

## 0.0.1
- add workflow v2 scaffolding (IntentRouter, WorkflowOrchestrator, adapter executors, SwingWorkflowListener)
- add .aiignore support for RAG indexing, project analysis, and automatic searches (@codebase/@grep); UI shows patterns and overrides defaults
- fix input panel staying enabled during background context refresh
- **refactor**: separate project context from system prompt using dedicated `systemMessages` parameter for improved behavior with smaller local models (Qwen3:14b, Llama); context now sent as separate system message in all modes (CHAT/PLAN/AGENT)
- **fix**: reversed systemMessages order (context first, policies second) to put policies closer to user question, improving retention based on NVIDIA RULER findings about recency bias in LLMs
- **CRITICAL FIX**: context now sent as **user message** (after user question) instead of system message - local models (Ollama/Llama/Qwen) completely ignore multiple system messages; user messages provide better retention and are properly processed
