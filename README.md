# PanelVerse

PanelVerse is an Android app for browsing manga and comics, keeping a reading list, and picking up where you left off. The **Ink & Screentone** interface brings together a searchable cover grid, series details, an in-app web reader, and an offline library for your own files.

## Download and install on Android

Open this link on a phone or tablet running **Android 7 or newer**. [Download PanelVerse 1.9.1 for Android (.apk)](https://github.com/Hazemserry90/panelverse/releases/download/v1.9.1/PanelVerse-1.9.1-release.apk)

1. When the download finishes, tap the file in your notifications or find it under **Files → Downloads**.
2. If Android asks for permission to install from your browser or Files app, tap **Settings** and turn on **Allow from this source** for that app. Go back and tap **Install**, then **Open**. You can turn the permission off after installing. [Android installation help](https://support.google.com/pixelphone/answer/7391672)

For other versions, see [GitHub Releases](https://github.com/Hazemserry90/panelverse/releases).

## iPhone and iPad

**iPhone and iPad testing is pending.** The invitation link will appear here when the beta opens. You’ll need **iOS 16 or iPadOS 16 or newer**.

When you have an invitation:

1. Install **TestFlight** from the App Store.
2. Open the PanelVerse invitation link on your iPhone or iPad and tap **Install** in TestFlight.
3. Open **PanelVerse**, search for a title, and tap **Start reading**.

## Try PanelVerse on Android

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

### Android

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

### iPhone and iPad on a Mac

1. Open `ios/PanelVerse.xcodeproj` in Xcode and let it resolve ZIPFoundation.
2. Select the **PanelVerse** scheme and an iPhone or iPad simulator, then press **Run**.
3. For a physical device, connect it and choose your Apple development team in **Signing & Capabilities**. Select the device as the run destination and press **Run**. Change the bundle identifier if your team needs a unique one.

### iPhone and iPad from Windows with Codemagic

#### Free testing on your own iPhone or iPad

1. Add the PanelVerse repository to a personal Codemagic account. Its free plan includes 500 macOS M2 build minutes per month. [Codemagic pricing](https://docs.codemagic.io/billing/pricing/)
2. Start a build on the branch containing this workflow and choose **PanelVerse iOS personal device**. When it succeeds, download **PanelVerse-unsigned.ipa** under **Artifacts**.
3. Install Sideloadly on your Windows PC, connect and unlock your device, and tap **Trust** if prompted. Select the IPA and your device in Sideloadly, then click **Start** and follow the prompts to sign it with your free Apple Account. [Sideloadly download and setup](https://sideloadly.io/)
4. If prompted on iOS 16, enable **Settings → Privacy & Security → Developer Mode**, restart, and confirm. If iOS asks you to trust the developer, go to **Settings → General → VPN & Device Management** and trust your Apple Account under **Developer App**. Open PanelVerse. [Apple Developer Mode guide](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)

Free personal signing expires after seven days. Use the same Apple Account to refresh the install from Windows. [Apple developer account limits](https://developer.apple.com/help/account/basics/about-your-developer-account)

#### Test iPhone sign-in and sign-up

1. In the Firebase project used by Android, register an iOS app with bundle ID `com.hazemserry.panelverse`. Download its `GoogleService-Info.plist` and confirm **Authentication → Sign-in method → Email/Password** is enabled. [Firebase setup for iOS](https://firebase.google.com/docs/ios/setup)
2. From the folder where you downloaded the file, run this in PowerShell to copy its Base64 value to your clipboard.

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\GoogleService-Info.plist).Path)) | Set-Clipboard
   ```

3. In Codemagic, open the PanelVerse app’s **Environment variables**. Create a secret variable named `PANELVERSE_FIREBASE_IOS_PLIST` in the group `panelverse_firebase_ios`, then paste the clipboard value. Run **PanelVerse iOS personal device with accounts**.
4. Install the resulting IPA with the same sideloading account as before and keep the same app bundle identifier in Sideloadly. Tap the person icon on **Home** to sign in or create an account.

For a local Xcode build, put the same plist in `ios/PanelVerse`. Account sign-in uses Firebase email and password. Favorites, reading lists, and offline downloads are stored on the device.

#### Simulator and TestFlight workflows

1. Connect the PanelVerse GitHub repository in Codemagic and select the branch with `codemagic.yaml`.
2. Run **PanelVerse iOS simulator** to compile the app and launch it on a hosted iPhone simulator. The build provides a screenshot and simulator app under **Artifacts**. [Codemagic iOS build guide](https://docs.codemagic.io/yaml-quick-start/building-a-native-ios-app/)
3. To install on an iPhone through TestFlight, use an Apple Developer Program membership and create the app in App Store Connect with bundle ID `com.hazemserry.panelverse`. In Codemagic, add an App Store Connect integration named **PanelVerse App Store Connect** and generate or import an Apple Distribution certificate and App Store provisioning profile for that bundle ID. [Codemagic signing guide](https://docs.codemagic.io/yaml-code-signing/signing-ios/)
4. Run **PanelVerse iOS TestFlight**. When Apple finishes processing the upload, add testers from the **TestFlight** tab in App Store Connect. Testers install using the TestFlight invitation. [Apple TestFlight](https://developer.apple.com/testflight/)

## Content and security

The online reader opens supported chapters from MangaKatana and Read Comics Online. Availability, chapter layout, and advertising can vary by provider. Cover art belongs to its respective rights holders.

For reporting security issues, see [SECURITY.md](SECURITY.md).

Maintained by [Hazem Serry](https://github.com/Hazemserry90).
