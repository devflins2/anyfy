# Contributing to Anyfy

Welcome! Thank you for your interest in contributing to **Anyfy**, a privacy-conscious, modern Android music streaming application featuring an elegant monochrome grey design and synchronized Together Listening rooms.

Whether you are fixing a bug, adding new features, improving documentation, or reporting an issue, we welcome contributions from developers of all skill levels. This guide will walk you through setting up your environment, understanding our project structure, and submitting your contributions.

---

## Table of Contents
1. [Welcome](#1-welcome)
2. [Project Rules & Architecture Constraints](#2-project-rules--architecture-constraints)
3. [Getting Started](#3-getting-started)
4. [Development Environment](#4-development-environment)
5. [Build & Test](#5-build--test)
6. [Project Structure](#6-project-structure)
7. [Coding Style & Best Practices](#7-coding-style--best-practices)
8. [Bug Reports](#8-bug-reports)
9. [Feature Suggestions](#9-feature-suggestions)
10. [Pull Requests](#10-pull-requests)
11. [Commit Messages](#11-commit-messages)

---

## 1. Welcome

Anyfy is built with a focus on simplicity, responsiveness, and user privacy. We believe in providing an uncluttered, high-performance listening experience. Even if this is your very first open-source contribution, don't worry—our community and maintainers are here to guide you through the process.

Please make sure to review our [Project Rules](#2-project-rules--architecture-constraints) before starting work on any feature or bug fix.

---

## 2. Project Rules & Architecture Constraints

To maintain Anyfy's security, privacy, and architectural consistency, all contributions must strictly adhere to the following rules:

- **On-Device yt-dlp Only**: `yt-dlp` must strictly remain on-device. Never move stream extraction, scraping, or processing to cloud servers or remote proxies.
- **Do Not Replace Media3/ExoPlayer**: AndroidX Media3 / ExoPlayer is our core audio engine. Do not replace or substitute it with other audio players without prior discussion and explicit core maintainer approval.
- **Do Not Break Socket.IO Together Listening**: The synchronized playback and room state management in the backend must remain fully backward-compatible and functional.
- **No Fake/Hardcoded Music Data**: Never commit mock, dummy, or hardcoded music streams, song metadata, or simulated playback fixtures. Real stream resolution and API integrations must be used.
- **Never Commit Secrets**: Do not commit secrets, private tokens, cookies, API keys, or keystore signing keys (`*.jks`, `*.keystore`, `google-services.json`, or production `.env` files). Use environment variables and the provided `.env.example`.
- **Preserve Monochrome Grey UI**: Anyfy's visual identity relies on a clean, minimalist monochrome grey color palette. Do not introduce contrasting neon accents or alter the theme unless explicitly approved.
- **Respect Third-Party Licenses**: Anyfy is open-source (GPL-3.0). Respect third-party licenses (Apache 2.0, MIT, LGPL, etc.) and always retain appropriate upstream attribution headers and notices.
- **Tests Required Before Merge**: All pull requests must pass local tests (`./gradlew testDebugUnitTest`) before being merged.

---

## 3. Getting Started

Follow these steps to set up your local Git workspace:

### Step 1: Fork the Repository
Click the **Fork** button at the top-right of the Anyfy GitHub repository to create your own copy on your GitHub account.

### Step 2: Clone Your Fork
Clone your newly forked repository to your local machine:
```bash
git clone https://github.com/<your-username>/anyfy.git
cd anyfy
```

### Step 3: Configure Upstream Remote
Keep your fork updated with the official repository:
```bash
git remote add upstream https://github.com/devflins2/anyfy.git
git fetch upstream
```

### Step 4: Create a Feature Branch
Always work on a separate branch rather than `main`:
```bash
git checkout -b feature/my-feature
```
*(For bug fixes, use `fix/issue-description`)*

---

## 4. Development Environment

To develop Anyfy, make sure your machine meets the following requirements:

### Prerequisites
- **Android Studio**: Android Studio (Ladybug, Koala, or Hedgehog recommended).
- **JDK 17**: Ensure Java 17 is installed and set as the Gradle JDK in Android Studio (*Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK*).
- **Android SDK**: API level 34+ installed via the SDK Manager.
- **Node.js & npm** (optional, for backend development): Node.js v18 or v20+.

### Setup Steps
1. Launch Android Studio.
2. Select **Open** and choose the root directory of this repository.
3. Allow Android Studio to complete the initial **Gradle Sync**. If prompted to update Android Gradle Plugin or install missing SDK platforms, proceed.
4. Copy `.env.example` to `.env` in the root directory if you need local environment configurations:
   ```bash
   cp .env.example .env
   ```

---

## 5. Build & Test

You can build and test Anyfy directly from the terminal or using Android Studio's built-in actions.

### Command Line (Linux/macOS)
- **Build Debug APK**:
  ```bash
  ./gradlew assembleDebug
  ```
- **Run Unit Tests**:
  ```bash
  ./gradlew testDebugUnitTest
  ```

### Command Line (Windows PowerShell)
- **Build Debug APK**:
  ```powershell
  .\gradlew.bat assembleDebug
  ```
- **Run Unit Tests**:
  ```powershell
  .\gradlew.bat testDebugUnitTest
  ```

### Backend (Socket.IO Server)
If you are working on or testing the Together Listening feature:
```bash
cd backend
npm install
npm test
npm start
```

---

## 6. Project Structure

Anyfy is structured into clean layers separating UI, business logic, data persistence, playback services, and backend synchronization:

```
├── app/src/main/java/com/example/
│   ├── ui/          # Jetpack Compose UI (Screens, Components, Theme, Navigation)
│   ├── viewmodel/   # Architecture ViewModels managing UI state and business logic
│   ├── data/        # Repositories, Room Database, local preferences, models
│   └── service/     # Media3/ExoPlayer MediaSessionService & on-device integrations
├── backend/         # Node.js Socket.IO server powering Together Listening rooms
├── gradle/          # Gradle wrapper and build configuration
└── scripts/         # Automation and helper scripts
```

- **`ui/`**: Pure Jetpack Compose code. Contains our monochrome UI theme, responsive screens (player, search, library, together listening), and reusable composables.
- **`viewmodel/`**: Kotlin StateFlows and Coroutines that feed state to composables and handle user interactions.
- **`data/`**: Abstractions for local caches, repositories, playlist databases, and metadata parsers.
- **`service/`**: Background playback engine utilizing AndroidX Media3 (`MediaSessionService`) and on-device extraction with `yt-dlp`.
- **`backend/`**: Node.js microservice maintaining real-time playback synchronization across users via WebSockets/Socket.IO.

---

## 7. Coding Style & Best Practices

To keep the codebase maintainable and readable:

### Kotlin Conventions
- Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Use idiomatic Kotlin constructs (`when`, `let`, `apply`, extension functions).
- Favor immutability (`val` over `var`, immutable collections).
- Use structured concurrency with Kotlin Coroutines and Flow; do not block the main dispatcher.

### Jetpack Compose Best Practices
- Keep composables stateless whenever possible through **state hoisting**.
- Optimize recomposition with `remember`, `derivedStateOf`, and immutable state holders.
- Adhere strictly to the monochrome grey theme parameters defined in `ui/theme/`.

### Meaningful Naming
- Use clear, intention-revealing names for classes, functions, and variables (e.g., `SynchronizedPlaybackRepository` instead of `SyncMgr`).
- Avoid cryptic abbreviations or single-letter variable names outside short lambda expressions.

---

## 8. Bug Reports

Encountered an issue? Help us fix it by submitting a detailed bug report via GitHub Issues.

Please include:
1. **Anyfy Version**: Commit hash or release version.
2. **Device Details**: Device model and Android OS version (e.g., Google Pixel 7, Android 14).
3. **Steps to Reproduce**: Detailed, numbered steps to trigger the bug.
4. **Expected vs. Actual Behavior**: What did you expect to happen, and what actually occurred?
5. **Logs & Screenshots**: Relevant `logcat` snippets, error traces, or screenshots.
6. **Privacy Warning**: **Always redact personal information**, such as account tokens, session IDs, private network addresses, or filenames from logs before posting.

---

## 9. Feature Suggestions

Have an idea to make Anyfy better? Open a feature suggestion under GitHub Issues.

When proposing a feature, please describe:
1. **The Problem**: What user problem, limitation, or friction does this feature solve?
2. **Proposed Solution**: How should the feature work from the user's perspective? Include rough wireframes or mockups if applicable.
3. **User Benefit**: Why is this feature valuable to the broader Anyfy community?
4. **Architecture Fit**: How does it align with Anyfy's principles (monochrome design, on-device `yt-dlp`, Media3 integration)?

---

## 10. Pull Requests

Ready to submit your code? Follow this pull request workflow:

1. **Rebase on Main**: Ensure your branch is updated with the latest upstream changes:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```
2. **Run Tests**: Verify that all unit tests pass and debug builds succeed:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. **Open Pull Request**: Push your branch to your fork and click **New Pull Request** on GitHub.
4. **Describe Changes**: Fill out the PR template clearly:
   - What changed and why?
   - Link related issues (e.g., `Closes #42`).
   - Include before/after screenshots for any UI-related updates.
5. **Review & Iterate**: A maintainer will review your code. Be open to feedback and push fixes to your branch as needed.

---

## 11. Commit Messages

We adhere to the [Conventional Commits](https://www.conventionalcommits.org/) specification to keep our Git history clear and automated changelogs accurate.

### Format
```
<type>(<scope>): <short description>
```

### Allowed Types
- **`feat`**: A new user-facing feature.
- **`fix`**: A bug fix.
- **`docs`**: Documentation changes only.
- **`style`**: Formatting, white-space, or lint fixes (no functional changes).
- **`refactor`**: Code restructuring without altering functionality or APIs.
- **`perf`**: Performance optimizations.
- **`test`**: Adding or updating unit tests.
- **`chore`**: Maintenance, build tool adjustments, or dependency updates.

### Examples
- `feat(player): add repeat mode toggle to monochrome player screen`
- `fix(service): prevent audio stutter when switching network interfaces`
- `docs: update setup instructions in CONTRIBUTING.md`
- `chore(deps): bump androidx.media3 to latest stable version`

---

Thank you for contributing to Anyfy and helping make private, synchronized music listening seamless!
