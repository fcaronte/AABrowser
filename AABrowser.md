\# AGENT INSTRUCTIONS & PROJECT CONTEXT: AABrowser

---

## 0. Project Memory & Continuity Rule (CRITICAL)
- **File Requirement**: A dynamic memory log file named `PROJECT_MEMORY.md` must be created and strictly maintained in the project's root directory.
- **Update Workflow**: After completing every major block of code, refactoring, or feature integration, the AI agent MUST update `PROJECT_MEMORY.md` with:
  1. **Current Status**: Development milestone reached 
  2. **Modified / Created Files**: List of modified files and their specific role.
  3. **Architectural Decisions**: Technical choices made (e.g., state management, sensor smoothing algorithms).
  4. **Next Steps (TODO)**: Concrete roadmap items for the next session.
- **Agent Initialization**: Every time a new AI agent or session starts, it must first inspect `PROJECT_MEMORY.md` and `AABrowser.md` to resume work seamlessly.

---

\## 1. User Interaction & Language Preference

\- \*\*Primary Communication Language\*\*: Always communicate, answer questions, explain code, and discuss technical decisions with the user in \*\*Italian\*\*.

\- \*\*Release Changelogs Exception\*\*: Official release notes/changelogs generated for the project must remain \*\*bi-lingual\*\* (Italian 🇮🇹 first, followed by English 🇬🇧) as specified in Section 3.

\---

\## 2. Project Overview & Architecture

\*\*AABrowser\*\* is an Android web-browser application designed specifically for \*\*Android Auto\*\*. Its core goal is to provide a seamless, touch-friendly, and responsive web browsing experience on head units using native templates and custom WebView interactions.

\### Primary Tech Stack & Structure

\- \*\*Language\*\*: Kotlin / Android SDK

\- \*\*Dependency Management\*\*: Centralized via \`gradle/libs.versions.toml\` (version catalog)

\- \*\*CI/CD\*\*: GitHub Actions workflows for automated APK compilation and release generation

\- \*\*Logging\*\*: Uses custom \`AppLog\` utility to strip/sanitize sensitive user data from logs prior to export or sharing

\- \*\*Updater/Installer\*\*: Updates are automated via \`DownloadManager\` + \`FileProvider\` and targeted for installation through \*\*KingInstaller\*\* (\`com.example.kinginstaller\`)

\---

\## 3. Core Modules & Key Features

\### AABrowser Widescreen (Wide Mode)

\- \*\*Concept\*\*: Native Android Auto template-based UI (inspired by apps like Xtream Player) occupying the primary screen space (navigation/map area).

\- \*\*Layout Logic\*\*:

  - Supports Full Screen and Split View modes.

  - Custom fixed sidebar on the left side (\`Left Sidebar\` option).

  - \*Hard Constraint\*: Android Auto stock template framework enforces that the right stock bar MUST keep at least one action button locked at the top-right corner.

  - \*Android Auto Limitations\* (set by Google for Car App Library apps, not bugs; the "Beta" label was dropped in 2.4): Only the full-screen Android Auto keyboard is available for text fields (Car App Library apps cannot show a docked keyboard; Android Auto may lock it while driving). Double-tap-to-zoom is disabled and drag gestures are not available (the host gives no finger position), for touch stability and safety while driving.

\### Web Engine & Injected JavaScript

\- JS injections are decoupled into modular, readable JavaScript files (located in assets/res).

\- Handles site-specific optimizations (e.g., skip button handling on YouTube playlists across multiple UI languages, touch interactions on Zappr, media auto-pause logic).

\### Media & Audio Handling

\- \*\*DRM Support\*\*: Widevine L3 implementation.

\- \*\*Audio Focus\*\*: Automatically pauses/ducks playback during reverse gear / rear parking camera activation.

\- \*\*Media Player\*\*: Integrated player with options to disable completely or run in the background.

\### Internationalization (i18n)

\- Multilingual resources stored across \`app/src/main/res/values-\*/strings.xml\`.

\- Supported languages include: \`ar\`, \`de\`, \`es\`, \`fr\`, \`hi\`, \`hu\`, \`it\`, \`pt\`, \`ru\`, plus base \`values\`.

\- \*Rule\*: When modifying UI elements or web-scraping/JS hooks relying on localized text (like YouTube "Skip" buttons), ensure multi-language resilience.

\---

\## 4. Rules & Guidelines for AI Agents

When modifying code, refactoring, or generating files for AABrowser, strictly adhere to the following rules:

\### Code Formatting & Maintenance

1\. \*\*Optimize Imports\*\*: Always remove unused imports and keep import blocks clean (\`Ctrl + Alt + O\` / IntelliJ standard).

2\. \*\*Architecture Separation\*\*: Keep Classic Mode (standard WebView) logic cleanly separated from Widescreen Mode (Car App Library templates) to avoid regressions.

3\. \*\*Log Security\*\*: Never log raw URLs, tokens, or personal context using standard \`Log.d\` / \`Log.e\`. Always wrap logs through the \`AppLog\` sanitizer class.

4\. \*\*Dependency Management (TOML)\*\*: Always define dependencies and library versions in \`gradle/libs.versions.toml\` (Version Catalog). Never hardcode dependency strings directly in \`build.gradle\` files.

\### Release Changelog Generation Standard

When tasked with creating release updates, strictly follow this format:

\- \*\*Bi-lingual\*\*: Provide notes in \*\*Italian (🇮🇹)\*\* first, followed by \*\*English (🇬🇧)\*\*.

\- \*\*Widescreen Disclaimer\*\*: If referencing Wide Mode features, highlight the constraints imposed by Android Auto (e.g., keyboard/zoom limits). Since 2.4 Wide Mode is no longer labeled \*\*Beta\*\*.

\- \*\*Donation Section\*\*: End EVERY release note in both languages with styled clickable Markdown links (do NOT output raw URLs in text):

\`\`\`markdown

\# ☕ Supporta il Progetto / Support the Project

Se ti piace usare l'applicazione e desideri supportare lo sviluppo, puoi farlo tramite i seguenti link:

\- \*\*\[Dona tramite PayPal\](https://www.paypal.com/paypalme/FCaronte/4)\*\*

\- \*\*\[Offr

imi un Caffè (Buy Me a Coffee)\](http://buymeacoffee.com/fcaronte)\*\*
