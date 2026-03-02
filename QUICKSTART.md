# Refio Quickstart Guide

**Refio** is a local-first AI coding assistant for IntelliJ IDEA that minimizes LLM context through intelligent RAG and code analysis. Get up and running in minutes!

## 🚀 Quick Installation

### Prerequisites

1. **Install Ollama** from [https://ollama.com/](https://ollama.com/)
2. **Download required models**:
   ```bash
   ollama pull nomic-embed-text:latest
   ollama pull qwen2.5-coder:14b
   ollama pull gpt-oss:20b
   ```
3. **Set Ollama context** to minimum 32k tokens (preferably 64k)

### Development Setup

1. **Prerequisites**: JDK 17 + IntelliJ IDEA 2024.x
2. **Clone repository**:
   ```bash
   git clone https://github.com/shadoq/refio.git && cd refio
   ```
3. **Launch sandbox IDE**:
   ```bash
   cd agent/plugin
   ./gradlew runIde          # Linux/macOS  
   .\gradlew.bat runIde      # Windows
   ```
4. **Optional**: Configure providers via Settings or `~/.refio/config.yaml`

## 🎯 First Steps

1. **Open Refio**: Go to `View > Tool Windows > Refio` in the launched IDE
2. **Simple Interface**: You'll see a clean chat window by default
3. **Advanced View**: Enable in `Settings → General` to see Context Preview, RAG Components, Logs, and Debug Panel

## 💬 Three Operation Modes

### Chat Mode
- **Best for**: Quick questions, code explanations, conversational help
- **How it works**: Enter prompt → Dynamic context built → LLM response

### Plan Mode  
- **Best for**: Complex tasks requiring approval
- **How it works**: Creates step-by-step plan → User approves each step → Read-only execution

### Agent Mode
- **Best for**: Autonomous code changes
- **How it works**: Full read/write access → Automatic execution → Snapshots for rollback

## 📝 Try These Example Prompts

**Simple Chat Examples:**
```
Explain this function: @file
```

**Agent/Plan Examples:**
```
Refactor this class to use dependency injection: @file
```

## 🎮 Fun Test Prompts

Try creating games to test Refio's capabilities:

**Snake Game:**
```
Write a snake game in javascript, css, html in one file. Build a classic Snake game on a 30x30 grid with keyboard controls, CPU mode, and multiple game modes (human vs human, cpu vs human, cpu vs cpu). Use filename "snake.html"
```

## 🔧 Common Issues

**Plugin doesn't load?**
- Ensure JDK 17 is installed
- Try `./gradlew clean build runIde`

**Ollama connection failed?**
- Verify Ollama is running: `ollama serve`
- Check models are downloaded: `ollama list`
- Default endpoint: `http://localhost:11434`

**Out of context errors?**
- Increase Ollama context: `ollama run qwen2.5-coder:14b` then `/set parameter num_ctx 65536`
- Use more specific `@file` instead of `@codebase`

## 📚 Next Steps

- **Full Documentation**: See [README.md](README.md) for complete architecture overview
- **Build Plugin ZIP**: Run `./gradlew buildPlugin` (output in `build/distributions/`)
- **Install from ZIP**: `File > Settings > Plugins > Install Plugin from Disk`
- **Advanced Features**: Enable Advanced View for RAG monitoring and debugging

---

**Note**: Refio is experimental software created as a proof-of-concept that coding agents can build other coding agents. Expect some rough edges and report issues on GitHub!

Happy coding with your AI assistant! 🤖✨