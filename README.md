# WaRevamp

**WaRevamp** is an [Xposed Framework](https://github.com/rovo89/Xposed) module that enhances WhatsApp and WhatsApp Business with privacy controls, anti-revoke/view-once bypass, media utilities, and UI customizations. It is the continuation of the legacy **MdgWa** project.

> **Minimum Android:** 8.0 (API 26) &nbsp;|&nbsp; **Target:** WhatsApp & WhatsApp Business &nbsp;|&nbsp; **Version:** 0.1.1

---

## Table of Contents

- [Features](#features)
- [Architecture Overview](#architecture-overview)
- [Project Structure](#project-structure)
- [How It Works](#how-it-works)
  - [Xposed Entry Point](#xposed-entry-point)
  - [Hook Loading Pipeline](#hook-loading-pipeline)
  - [DexKit & Reference Caching](#dexkit--reference-caching)
  - [Broadcast / IPC System](#broadcast--ipc-system)
- [Key Classes](#key-classes)
- [Building](#building)
- [Download](#download)
- [Community](#community)
- [Support the Project](#support-the-project)

---

## Features

| Category | Feature | Description |
|---|---|---|
| **Privacy** | Hide Read Receipts | Prevents WhatsApp from sending blue ticks |
| **Privacy** | Hide Delivery Receipts | Suppresses double-tick delivery confirmations |
| **Privacy** | Hide Typing / Recording | Hides "typing…" and "recording…" indicators |
| **Privacy** | Do Not Disturb Mode | Blocks incoming-message events entirely |
| **Privacy** | Freeze Last Seen | Keeps last-seen timestamp static |
| **Privacy** | Call Privacy | Hides call-related information |
| **Functions** | Anti-Revoke | Recovers messages deleted by the sender; marks them with a configurable indicator |
| **Functions** | Anti-View-Once | Allows ephemeral (view-once) media to be viewed multiple times |
| **Functions** | Custom Privacy | Per-contact toggles for privacy settings |
| **Media** | Media Quality Control | Selects the quality/resolution when downloading media |
| **Media** | Download View-Once | Saves ephemeral photos and videos to storage |
| **Media** | Download Status | Saves WhatsApp Status updates to storage |
| **Media** | Disable FLAG_SECURE | Allows screenshots inside WhatsApp |
| **Customization** | Hide Archived Chats | Hides the archived-chats section from the chat list |
| **Customization** | Separate Groups | Shows individual and group chats in separate tabs |
| **Others** | Custom Menu | Adds extra items to the WhatsApp context menus |
| **Others** | Raise Pinned Limit | Increases the maximum number of pinned messages |
| **Others** | Miscellaneous Tweaks | Additional small quality-of-life changes |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────┐
│             Module UI (Android App)              │
│  MainActivity ─ Preferences ─ ModuleSender ────► broadcast
└────────────────────────────────┬────────────────┘
                                 │ (broadcast IPC)
┌────────────────────────────────▼────────────────┐
│            Xposed Layer (WhatsApp process)       │
│                                                  │
│  ModuleStart ──► HooksLoader ──► References      │
│                       │          (DexKit cache)  │
│                       │                          │
│              ┌────────▼──────────┐               │
│              │  Plugin Hooks (17)│               │
│              │  each extends     │               │
│              │  HooksBase        │               │
│              └───────────────────┘               │
│                                                  │
│  WhatsAppReceiver ◄── broadcasts ◄── Module UI   │
└──────────────────────────────────────────────────┘
```

The module lives in **two processes**:

1. **Module process** — the regular Android app that shows settings (uses `MainActivity`).
2. **WhatsApp process** — loaded by Xposed; applies all hooks at runtime inside WhatsApp.

They communicate via **Android broadcasts** (see [Broadcast / IPC System](#broadcast--ipc-system)).

---

## Project Structure

```
app/src/main/java/its/madruga/warevamp/
│
├── App.java                        # Application subclass; starts the broadcast system
│
├── app/                            # Module-side UI & utilities
│   ├── core/
│   │   ├── Utils.java              # Package name constants & install helper
│   │   └── XposedChecker.java      # Detects whether the module is active
│   └── ui/
│       ├── activitys/
│       │   ├── MainActivity.java   # Main settings screen
│       │   └── AboutActivity.java  # About / version screen
│       └── fragments/
│           ├── RootFragment.java   # Root preference fragment
│           └── views/InfoCard.java # Custom info-card view
│
├── broadcast/                      # Cross-process communication
│   ├── Events.java                 # Intent action string constants
│   ├── Receivers.java              # Receiver registration helpers
│   ├── Senders.java                # Sender initialization helpers
│   ├── receivers/
│   │   ├── WhatsAppReceiver.java   # Handles commands from the Module UI
│   │   └── ModuleReceiver.java     # Handles replies from WhatsApp
│   └── senders/
│       ├── WhatsAppSender.java     # Sends events to the WhatsApp process
│       └── ModuleSender.java       # Sends events from the Module UI
│
└── module/                         # Xposed / WhatsApp side
    ├── ModuleStart.java            # Xposed entry point (3 Xposed interfaces)
    ├── core/
    │   ├── FMessageInfo.java       # Reflection wrapper around WhatsApp's FMessage
    │   ├── WppCallback.java        # Activity-lifecycle watcher; prompts restart
    │   ├── WppUtils.java           # JID helpers, resource look-ups, DB queries
    │   └── databases/
    │       ├── WaDatabase.java     # Singleton accessor for wa.db (contacts)
    │       ├── MsgstoreDatabase.java  # Message-store DB
    │       ├── AxolotlDatabase.java   # Signal (E2E) key DB
    │       ├── StickerDatabase.java   # Sticker DB
    │       └── utils/Database.java    # Abstract base for all DB singletons
    ├── hooks/
    │   ├── core/
    │   │   ├── HooksBase.java      # Base class for every hook; logging + prefs
    │   │   └── HooksLoader.java    # Orchestrates DexKit init and plugin loading
    │   ├── privacy/                # HideReadHook, HideReceiptHook, DndModeHook …
    │   ├── functions/              # AntiRevokeHook, AntiViewOnceHook, CustomPrivacyHook …
    │   ├── media/                  # MediaQualityHook, DownloadStatusHook …
    │   ├── customization/          # HideArchivedChatsHook, SeparateGroupsHook
    │   └── others/                 # MenuHook, OthersHook, PinnedLimit
    └── references/
        ├── References.java         # DexKit searches; exposes reflected methods/fields
        ├── ReferencesCache.java    # Persists discovered methods to SharedPreferences
        ├── ReferencesUtils.java    # Field/method lookup utilities
        └── ModuleResources.java    # Resource ID holders injected into WhatsApp
```

---

## How It Works

### Xposed Entry Point

`ModuleStart` implements three Xposed interfaces:

| Interface | Method | Purpose |
|---|---|---|
| `IXposedHookZygoteInit` | `initZygote()` | Stores the module APK path before any app starts |
| `IXposedHookLoadPackage` | `handleLoadPackage()` | Detects WhatsApp and triggers `HooksLoader`; also activates the module-active flag in the Module UI's own process |
| `IXposedHookInitPackageResources` | `handleInitPackageResources()` | Injects module strings, arrays, drawables, and layouts into WhatsApp's resource table so they appear as native resources |

### Hook Loading Pipeline

When WhatsApp's `Application.onCreate()` is called (intercepted via `Instrumentation.callApplicationOnCreate`), the following steps occur in order:

1. **DexKit Init** — `References.initDexKit(sourceDir)` opens WhatsApp's APK with [DexKit](https://github.com/LuckyPray/DexKit) for obfuscation-safe method/class discovery.
2. **Reference Resolution** — `References.start()` searches for all obfuscated WhatsApp methods/fields needed by the hooks and stores them via `ReferencesCache`.
3. **Plugin Loading** — `HooksLoader.plugins()` iterates over the 17 hook classes, instantiates each via reflection, and calls `doHook()`.
4. **Broadcasts Start** — `WhatsAppSender` and `WhatsAppReceiver` are registered for IPC with the Module UI.
5. **Error Reporting** — If any hook fails during `doHook()`, its name is collected and shown in an `AlertDialog` the next time the WhatsApp home screen opens.

### DexKit & Reference Caching

WhatsApp's code is heavily obfuscated — class and method names change with every update. WaRevamp solves this with two layers:

- **DexKit** searches WhatsApp's bytecode at runtime using string literals, opcodes, or type signatures to locate the real method/class regardless of obfuscation.
- **`ReferencesCache`** persists discovered method paths (`ClassName~methodName~paramType1:paramType2`) to a `SharedPreferences` file. On subsequent launches the module reads the cache instead of re-running DexKit. The cache is automatically invalidated whenever WhatsApp's version string changes.

### Broadcast / IPC System

The Module UI and the WhatsApp process run in separate sandboxes. They communicate through explicit Android broadcasts:

```
Module UI ──(broadcast)──► WhatsAppReceiver
                            ├─ onReboot()          → restart WhatsApp
                            └─ onCleanDatabase()   → purge cached references

WhatsApp ──(broadcast)──► ModuleReceiver
                           └─ onIsActive()         → confirm module is running
```

`WppCallback` monitors WhatsApp's activity lifecycle; when a `needRestart` flag is set it prompts the user to restart WhatsApp so settings take effect.

---

## Key Classes

### `ModuleStart`
The Xposed entry point. Bridges the module with WhatsApp by implementing all three Xposed hook interfaces. Also exposes a thread-safe `XSharedPreferences` instance used by every hook to read user settings.

### `HooksLoader`
Central orchestrator. Initialises DexKit, resolves all references, loads every hook plugin, and registers broadcasts. Also hooks `homeActivityClass.onCreate` to surface any hook errors to the user.

### `HooksBase`
Abstract base class extended by all 17 hooks. Provides `loader` (WhatsApp's `ClassLoader`), `prefs` (`XSharedPreferences`), and `log()`/`wppLog()` helpers. The `doHook()` method is overridden by each hook to install its intercepts.

### `References`
Wraps DexKit to find obfuscated WhatsApp methods, fields, and classes. Each discovered reference is also persisted through `ReferencesCache` for fast retrieval on subsequent launches.

### `ReferencesCache`
Serialises `Method`, `Constructor`, `Field`, and `Class` references as path strings in SharedPreferences. Detects WhatsApp version changes and clears stale cache entries automatically.

### `FMessageInfo`
A reflection-based wrapper around WhatsApp's internal `FMessage` object. Provides clean Java accessors for the message text (`getMessageStr()`), media file (`getMediaFile()`), media type (`getMediaType()`), and the message key (`getKey()`) which contains the message ID, sender JID, and direction flag.

### `AntiRevokeHook`
Hooks the method WhatsApp calls to delete a message. Before the deletion is processed, the hook:
1. Saves the message key to a contact-specific SharedPreferences entry.
2. Refreshes the conversation activity so the recovered message is immediately visible.
3. Later, when the message bubble is rendered, annotates it with a configurable indicator (text prefix or a red block icon).

### `WppUtils`
Utility methods shared across hooks:
- `stripJID()` — strips the `@s.whatsapp.net` / `@g.us` suffix from a JID.
- `getRawString()` — extracts the string from WhatsApp's opaque JID object via reflection.
- `getContactName()` — queries `wa.db` for a contact's display name.
- `getResourceId()` — resolves a resource name to its integer ID within WhatsApp's process.

---

## Building

**Requirements:** Android Studio 2023.1.1 (Hedgehog) or later, JDK 17, a rooted device with [LSPosed](https://github.com/LSPosed/LSPosed) or a compatible Xposed manager.

1. Clone the repository.
2. Copy `gradle.properties.example` to `gradle.properties` and fill in your signing details (or use the debug build type which skips signing).
3. Run `./gradlew assembleDebug` (or open the project in Android Studio and build).
4. Install the APK, enable the module in your Xposed manager, and restart WhatsApp.

---

## Download

- [Releases](https://github.com/ItsMadruga/WaRevamp/releases)

---

## Community

- [Telegram Channel](https://t.me/warevampmodule)
- [Telegram Group](https://t.me/warevampgroup)

---

## Support the Project

<div align="center">
 <a href="https://buymeacoffee.com/kaioreis" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-yellow.png" alt="Buy Me A Coffee" height="41" width="174"></a>
</div>
