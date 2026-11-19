# BTECH Bot (BTECH-BOT-V2 Android)

Native Android application built with Kotlin, Jetpack Compose, and Material Design 3.

## Features
- **Multi-Mode Bot Chat**: Switch between *BTECH Core*, *Dev & Debug*, *Finance & Ops*, and *Bot Automator* personas with optional custom session directives.
- **Slash Command Templates**: Quick-launch `/summarize`, `/debug`, `/budget`, `/automate`, and `/draft` workflows directly from the chat composer or Commands tab.
- **Local Session Persistence & Preferences**: Automatically saves conversation threads locally and persists theme, streaming animation, and layout density settings with DataStore.
- **Adaptive Edge-to-Edge UI**: Supports phones (`NavigationBar`) and larger screens (`NavigationRail`) with custom bundled typography (`Space Grotesk`, `Plus Jakarta Sans`, `JetBrains Mono`) and an adaptive launcher icon.

## AI Backend Status
All AI requests are routed through `AiRepository` (`PlaceholderAiRepository`), which currently returns clearly labeled sample responses because Firebase setup was declined. Connecting **Firebase AI Logic** enables live streaming Gemini responses without needing an API key.