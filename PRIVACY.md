# Privacy Policy

Refio is a local-first IntelliJ plugin. By default, it keeps project data on your machine and does not include telemetry, analytics, or cloud sync.

## What Stays Local

- Chat history, plans, subtasks, snapshots, API logs, RAG indexes, and project analysis data are stored locally in the project SQLite database.
- Project configuration can be stored locally in `.refio/config.yaml` and user configuration in `~/.refio/config.yaml`.
- When you use local providers such as **Ollama** or **LM Studio**, prompts and responses stay on your machine.

## When Data Can Leave Your Machine

- Prompts, selected context, and model inputs are sent to a cloud provider only when you explicitly configure and use one, such as **OpenAI**, **Anthropic**, **Gemini**, or **OpenRouter**.
- Refio sends only the data required for the request you make. It does not sync your repository to Refio servers because Refio does not operate a cloud backend for the plugin.

## No-Egress Mode

- Refio includes a **no-egress mode** that blocks cloud LLM providers.
- When enabled, only local providers are allowed.
- This is intended for privacy-sensitive or compliance-restricted environments.

## Telemetry And Tracking

- Refio does **not** collect telemetry or usage analytics.
- Refio does **not** track prompts, file contents, or IDE activity for vendor analytics.

## Secrets And Credentials

- API keys and provider credentials are configured locally.
- Refio masks secrets in logs where supported by the codebase.
- Secrets are not forwarded to third parties other than the provider you explicitly configure for that request.

## MCP Connections

- MCP servers are used only when you explicitly configure or enable them.
- Each MCP connection can expose external resources or tools according to its own configuration and the server you connect to.

## How To Verify

- Refio is open source under the MIT license.
- You can inspect the code, configuration flow, network behavior, and storage format directly in this repository.

## Practical Summary

- Local providers: data stays local
- Cloud providers: request data goes to the provider you chose
- No telemetry: no usage tracking by Refio
- No cloud sync: project data remains in your environment
