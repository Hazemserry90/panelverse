# PanelVerse

PanelVerse is an Android app for browsing manga and comics, keeping a reading list, and picking up where you left off. The **Ink & Screentone** interface brings together a searchable cover grid, series details, an in-app web reader, and an offline library for your own files.

## Features

- **Home and Discover** — Browse Manga, Marvel, DC, Invincible, and Comics. Search by title or find recommendations based on your interests and reading history.
- **Series and Saved** — Open a title to see its cover and reading provider. Save favourites to your Firebase account or share a series link.
- **Web reader** — Open provider chapters inside the app. The reader saves your last loaded chapter and uses the visible page image or scroll position to resume. Restoration depends on the provider’s layout.
- **Ad filtering** — Use **Reader options → Block ads** to block known ad hosts and hide supported overlays. Provider ads can change, so some may still appear.
- **Offline library** — Import a local CBZ with JPG, PNG, or WebP pages. PanelVerse keeps a copy on the device and remembers the last page for each book.
- **Appearance and reminders** — Switch between paper and ink themes and control local reading reminders in Settings.

## Install on Android

If a signed APK has been published, download it from [GitHub Releases](https://github.com/Hazemserry90/panelverse/releases). Open the APK on your phone and follow Android’s install prompt. To install over an existing PanelVerse build, the APK must use the same signing certificate. Android 7.0 (API 24) or later is required.

## Read offline

1. Put a CBZ file you’re allowed to use on your phone.
2. In PanelVerse, open **Offline → Import CBZ** and select the file.
3. Open the imported title from the library. **Previous** and **Next** turn pages. Reopening it resumes at the last page you read.

The offline library supports imported files up to 250 MB. Provider chapters are streamed in the web reader. Importing CBZ files is the current offline workflow.

## Build from source

You’ll need Android Studio or the Android command-line tools, JDK 17 or 21, Android SDK Platform 34, and Build-Tools 34.0.0. The project uses Kotlin, AndroidX, Material 3, Gradle 8.9, and Firebase.

1. Clone the repo.

   ~~~sh
   git clone https://github.com/Hazemserry90/panelverse.git
   cd panelverse
   ~~~

2. Open the project in Android Studio so it can configure the SDK, or set the `ANDROID_HOME` environment variable to your SDK folder.
3. In your Firebase project, register the Android application ID `com.example.testing`, enable Email/Password sign-in, and configure Realtime Database rules that restrict users to their own records. Put the downloaded Firebase client config in `app/google-services.json`. Point `FirebaseHelper.kt` at your own Realtime Database instance when building against a different project.
4. Build a debug APK.

   ~~~powershell
   .\gradlew.bat :app:assembleDebug
   ~~~

   On macOS or Linux, run `./gradlew :app:assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Release builds use your local `keystore.properties`, or an external file selected by `PANELVERSE_RELEASE_SIGNING_PROPERTIES`. Run `:app:assembleRelease` to build the release variant. Keep keystores, signing passwords, machine configuration, and Firebase client configuration out of version control.

## Content and security

The online reader opens supported chapters from MangaKatana and Read Comics Online. Availability, chapter layout, and advertising can vary by provider. Cover art belongs to its respective rights holders.

For reporting security issues, see [SECURITY.md](SECURITY.md).

Maintained by [Hazem Serry](https://github.com/Hazemserry90).
