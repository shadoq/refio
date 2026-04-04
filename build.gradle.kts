// Root project — delegates to submodules
// :core          — IDE-independent core (LLM, tools, DB, agents, workflow)
// :intellij-plugin — IntelliJ IDEA plugin (UI, services, context providers)
// :cli           — Standalone CLI + Compose Desktop GUI (future)
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.intellij") version "1.17.4" apply false
}
