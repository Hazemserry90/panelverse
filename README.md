# PanelVerse

**PanelVerse** is an Android app for reading **manga** and **comics** (Marvel, DC and more) in one place — with a modern ink-inspired UI, a built-in reader, cloud-synced favourites and an offline **AI recommendation assistant**.

> Formerly named *MangaVerse*.

---

## ✨ Features

- **Big, categorised library** — Manga, Marvel, DC, Invincible and Comics, all in a searchable list.
- **Search & filter** — live search plus category chips.
- **Built-in reader** — open any title and read it inside the app.
- **Favourites** — saved to your account and synced across devices (Firebase Auth + Realtime Database).
- **AI Assistant** (new tab)
  - Pick interest chips (Superheroes, Action, Dark & Gritty, Horror, Sports, …) or type what you're in the mood for.
  - Get instant recommendations from the catalogue.
  - **Learns from your reading:** suggests titles *similar to the last thing you read*.
  - Runs fully offline — no API key, no cost.
- **Reading history** — remembers what you opened.
- **Notifications** — periodic "pick up where you left off" reminders (WorkManager).
- **Light / dark theme** — cream "paper" and deep ink "night" palettes.
- **Unique cover art** — every title ships with its own cover, bundled for offline use.

---

## 🧱 Tech stack

| Area | Used |
|---|---|
| Language | Kotlin |
| UI | AndroidX, Material 3, View/XML layouts, RecyclerView |
| Images | Coil |
| Auth / data | Firebase Authentication, Firebase Realtime Database |
| Background | WorkManager |
| GIF | android-gif-drawable |
| Build | Gradle (Kotlin DSL), Android Gradle Plugin 8.7.0, Gradle 8.9 |
| SDK | `minSdk 24`, `compileSdk`/`targetSdk 34` |
| Package | `com.example.testing` |

---

## 📂 Project structure

```
app/src/main/
├── java/com/example/testing/
│   ├── MainActivity.kt            # hosts the nav graph
│   ├── splashscreen.kt            # splash
│   ├── NavbarFragment.kt          # bottom-nav tab host (Home / AI / Saved / Profile / Settings)
│   ├── HomeFragment.kt            # catalogue + search + category chips
│   ├── AssistantFragment.kt       # offline AI recommender (+ reading-history suggestions)
│   ├── FavoritesFragment.kt       # cloud-synced favourites
│   ├── ProfileFragment.kt / SettingsFragment.kt
│   ├── StartFragment.kt / GameFragment.kt / EndgameFragment.kt  # auth screens
│   ├── ReaderActivity.kt          # in-app reader
│   ├── MangaItem.kt / MangaAdapter.kt
│   ├── ReadingHistoryManager.kt   # local reading history
│   └── NotificationWorker.kt      # periodic reminders
└── res/                           # layouts, drawables (covers), themes, menu
```

---

## 🚀 Building

### Requirements
- **Android Studio** (or the Android command-line tools)
- **JDK 17+**
- Android **SDK Platform 34** and **Build-Tools 34.0.0**

### Steps
1. Clone the repo:
   ```bash
   git clone https://github.com/Hazemserry90/panelverse.git
   cd panelverse
   ```
2. Create **`local.properties`** in the project root with your SDK path:
   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```
3. Add your Firebase config file at **`app/google-services.json`** (Firebase console → Project settings → Android app `com.example.testing`).
4. Build:
   ```bash
   ./gradlew assembleDebug
   ```
   The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> `local.properties` and `app/google-services.json` are **git-ignored** (they contain machine-specific paths and API keys), so you must supply your own.

---

## 📚 Content sources

PanelVerse is a reader/catalogue front-end. All manga and comic content is streamed from third-party sites:

- **Comics:** readcomicsonline.ru
- **Manga:** mangakatana.com

Cover images belong to their respective publishers/owners and are used here for identification only.

---

## 🔒 Security

Security-hardening measures and responsible-disclosure instructions are documented in [SECURITY.md](SECURITY.md). Short version: no secrets are committed, the Firebase database requires authentication, the in-app WebView only loads `http`/`https` (file access off, no JS bridge), external components are not exported, and cleartext traffic is disabled.

---

## 👤 Author

**Hazem Serry** — [@Hazemserry90](https://github.com/Hazemserry90)
