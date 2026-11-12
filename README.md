# PanelVerse

PanelVerse is an Android app for browsing manga and comics, keeping a reading list, and picking up where you left off. The **Ink & Screentone** interface brings together a searchable cover grid, series details, an in-app web reader, and an offline library for your own files.

## Download and install on Android

Open this link on a phone or tablet running **Android 7 or newer**. [Download PanelVerse for Android](https://github.com/Hazemserry90/panelverse/releases/latest)

1. On the release page, tap **Assets** and choose the PanelVerse file ending in **.apk**. That’s the Android installer.
2. When the download finishes, tap the file in your notifications or find it under **Files → Downloads**.
3. If Android asks for permission to install from your browser or Files app, tap **Settings** and turn on **Allow from this source** for that app. Go back and tap **Install**, then **Open**. You can turn the permission off after installing. [Android installation help](https://support.google.com/pixelphone/answer/7391672)

## iPhone and iPad

PanelVerse currently ships for Android. An iPhone or iPad download requires a separate iOS build. An iOS beta can be distributed through TestFlight once a build is available. [Apple TestFlight](https://developer.apple.com/testflight/)

## Try PanelVerse

1. Tap **Create account**, confirm your email using the link in your inbox, then return to PanelVerse and tap **Login**.
2. Search for a title like **Dragon Ball**, open its series page, and tap **Start reading**.
3. Tap the star on a title to find it later in **Library → Favorites**, or tap **Want to Read** on the series page to add it to that list.
4. To try reading without internet, tap **Download** next to a supported chapter or issue. Wait for the download to finish, then open it from **Offline**.

## Features

- **Home and Discover** — Browse Manga, Marvel, DC, Invincible, and Comics. Search by title or find recommendations based on your interests and reading history.
- **Series and Library** — Browse manga chapters and comic issues, share a series link, and manage Favorites separately from Reading, Want to Read, and Finished. Use **Want to Read** on a series page or long-press a title in your Library to move it between reading lists.
- **Reader** — Open supported chapters in the native reader with page controls, a scrubber, and right-to-left, left-to-right, or vertical reading. Pinch or double-tap to zoom, then drag to move around the page. You can also use **Reader options → Zoom in** or **Zoom out**. The web reader remains available from the series and reader controls when a provider chapter needs it.
- **Ad filtering** — Use **Reader options → Block ads** to block known ad hosts and hide supported overlays. Provider ads can change, so some may still appear.
- **Offline library** — Download supported chapters or import a local CBZ with JPG, PNG, or WebP pages. PanelVerse keeps the files on the device and remembers your page.
- **Appearance and reminders** — Switch between paper and ink themes and control local reading reminders in Settings.

## Read offline

For a supported chapter or issue, open its series and tap **Download** next to the entry. The button shows download progress. Open the finished download from **Offline**. Provider availability and supported page formats determine whether a download succeeds.

Tap **Cancel download** on a queued or active download. To remove a finished download, open **Offline**, then touch and hold the title.

If a comic’s issue list is unavailable in the app, tap **Browse issues to download**, open an issue, then choose **Reader options → Download issue**.

You can also put a CBZ you’re allowed to use on your phone and select it under **Offline → Import CBZ**. The offline library supports archives up to 250 MB.

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
