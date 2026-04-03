# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NoDriver4j is a browser automation framework consisting of three components: a CDP (Chrome DevTools Protocol) library and JavaFX desktop application written in Java, paired with a custom-patched Chromium build (C++) designed to resist anti-bot fingerprinting.

## Environment & Build

- **Build system**: Maven
- **Java**: JDK 23, JavaFX 23
- **IDE (Java)**: IntelliJ IDEA
- **IDE (C++)**: Visual Studio Code
- **OS**: Windows 11 (WSL available)

## Architecture

```
UI (JavaFX)  -->  Services  -->  Core (Browser/CDP)  -->  Custom Chromium
     |               |
 Persistence      Scripts
```

### Core (`org.nodriver4j.core`)

Owns the browser lifecycle and page-level automation. `Browser` is responsible for launching Chrome, establishing a CDP connection, and tearing everything down on close. `Page` is the high-level automation surface — navigation, clicking, typing, scrolling, waiting — with all interactions modeled on human behavior (Bezier curve mouse paths, Fitts's Law step counts, Gaussian keystroke timing). `BrowserConfig` is the single immutable config object for all browser launch settings. `Fingerprint` provides persistent browser identity by loading fingerprint profiles from a JSONL data file.

`BrowserManager` exists for standalone dev/testing convenience and is **not** used in production task execution — that role belongs to `TaskExecutionService`. Some testing may require `TaskExecutionService`. Decide between the two on a case by case basis.

### CDP (`org.nodriver4j.cdp`)

Handles all Chrome DevTools Protocol communication. A single `CDPClient` holds the browser-level WebSocket and demultiplexes incoming messages to the correct `CDPSession` by session ID. Each `CDPSession` represents one tab or frame. This design allows multi-tab automation over a single connection. The custom Chromium build extends the protocol with commands like `DOM.getShadowRoot` for piercing closed Shadow DOMs — invisible to page JavaScript.

### Scripts (`org.nodriver4j.scripts`)

Pluggable automation workflows. Each script implements `AutomationScript` and is registered by name in `ScriptRegistry`. The registry maps internal names (stored in the database) to display names (shown in the UI) and factory functions. Scripts receive a `Page`, profile data, a logger, and a cancellation-aware context at runtime — they own only the workflow logic and delegate everything else.

### Services (`org.nodriver4j.services`)

Orchestration and infrastructure, organized into subpackages:

- **Root** — `TaskExecutionService` is the singleton bridge between the UI and the browser core — it builds configs from database entities and settings, manages the port pool, launches browsers, and runs scripts on background threads. `TaskContext` provides cooperative cancellation and resource registration so that stopping a task tears down IMAP connections, HTTP clients, and other I/O immediately. `TaskLogger` handles per-task logging. `ScreencastService` manages browser screen capture.
- **`captcha/capsolver`** — CapSolver API integration (`CapSolverService`, `CapSolverException`).
- **`captcha/twocaptcha`** — 2Captcha API integration (`TwoCaptchaService`, `TwoCaptchaException`).
- **`aycd`** — AutoSolveAI integration (`AutoSolveAIService`, `AutoSolveAIException`).
- **`imap`** — `GmailClient` provides shared IMAP connections. `EmailPollingBase` is the base for email extractors, with concrete implementations in `imap/impl` (e.g., `FunkoVerificationExtractor`, `UberOtpExtractor`).
- **`sms`** — SMS verification providers (`DaisySmsService`, `SmsManService`, `TextVerifiedService`) sharing a common `SmsServiceBase` and `SmsService` interface.
- **`proxy`** — `ProxyDiagnosticService` for proxy health checks.
- **`response`** — Response DTOs organized by domain: `response/captcha` (Arkose, AutoSolveAI, ReCaptchaV3), `response/proxy` (diagnostic results), `response/sms` (SMS activations).

### Persistence (`org.nodriver4j.persistence`)

Two storage mechanisms: SQLite for structured data (profiles, proxies, tasks, fingerprints, and their groups) and a JSON file for application settings (Chrome path, API keys, default options). The database uses version-based migrations applied sequentially on startup. All entity access goes through a generic `Repository<T>` interface with concrete implementations per entity type. Data lives in `nodriver4j-data/` relative to the working directory.

### UI (`org.nodriver4j.ui`)

JavaFX desktop application with a custom undecorated dark-themed window. Sidebar navigation loads pages into a shared content area — only one page is visible at a time. Organized into subpackages:

- **Root** — `NoDriverApp` (application entry point).
- **`components`** — Shared UI components (`GroupCard`, `WindowTitleBar`).
- **`controllers`** — Top-level controllers (`MainController`, `GroupManagerController`).
- **`profile`** — Profile group management (`ProfileManagerController`, `ProfileGroupCard`, `CreateProfileGroupDialog`), with `profile/detail` for individual profile views (`ProfileCard`, `ProfileDetailDialog`, `ProfileGroupDetailController`).
- **`proxy`** — Proxy group management (`ProxyManagerController`, `ProxyGroupCard`, `CreateProxyGroupDialog`), with `proxy/detail` for individual proxy views (`ProxyRow`, `ProxyGroupDetailController`).
- **`task`** — Task group management (`TaskManagerController`, `TaskGroupCard`, `CreateTaskGroupDialog`), with `task/detail` for individual task views (`TaskRow`, `CreateTaskDialog`, `EditTaskDialog`, `ChangeProxiesDialog`, `TaskGroupDetailController`, `ViewBrowserWindow`).
- **`settings`** — `SettingsController` for application configuration.
- **`util`** — UI helpers (`SmoothScrollHelper`, `WindowResizeHelper`).

FXML and CSS resources live under `src/main/resources/org/nodriver4j/ui/`.

### Key Architectural Decisions

- **Startup order**: Database initializes before any UI loads, because controllers query persistence during their `initialize()` phase.
- **Shutdown order**: Running tasks are stopped and the execution service shuts down before the database closes, so final status writes succeed.
- **Data directory**: `nodriver4j-data/` contains the SQLite database, JSON settings, and per-task browser userdata directories.

## Coding Conventions

- Follow SOLID principles strictly.
- Before implementing a function, verify it doesn't already exist elsewhere in the project.
- Getter methods omit the `get` prefix: `firstName()` not `getFirstName()`.
- Prefer updating existing config classes over creating new ones — maintain a single source of truth.
- Use existing utility classes, exceptions, and patterns when applicable.

## Workflow: Creating or Making Large Additions to a Class

1. **SRP & redundancy check**: State what the new class IS responsible for, what it is NOT responsible for (delegated to other classes), and which existing classes might need refactoring.
2. **Clarify**: Ask any questions before writing code.
3. **Implement**: Code the file using existing utils, exceptions, and classes. Avoid writing functions that duplicate existing functionality.
4. **Verify**: Confirm the file isn't missing any functionality.
5. **Summarize**: Include a "Key Features of the Implementation" summary.

## Workflow: Smaller Updates to Specific Functions

1. **SRP & redundancy check** on the planned updates.
2. **Implement**: Output the updated function(s).
3. **Verify**: Confirm the new code isn't missing any functionality.
4. **Summarize**: Briefly describe the changes.

## When Outputting Code Changes

Always state:
1. What files were **added**
2. What files were **updated**
3. What files were **removed**

## Adding a New Automation Script

1. Create a class implementing `AutomationScript` in `org.nodriver4j.scripts`.
2. Register it in `ScriptRegistry`'s static initializer with an internal name, display name, and factory.
3. The script receives `Page`, `ProfileEntity`, `TaskLogger`, and `TaskContext` at runtime.
