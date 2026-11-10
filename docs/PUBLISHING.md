# Publishing Meshron

Everything in the app is ready. These steps need **you**: your signing key, your accounts and your contact details. Nobody else should create or hold them.

## 1. Contact email ✅

Set to baxxkid@gmail.com in docs/PRIVACY.md, the in-app privacy text and store/listing.md.

## 2. Create your signing key (once, keep it forever)

Run this in the project folder (Android Studio's Java has `keytool`):

```bash
"/c/Program Files/Android/Android Studio/jbr/bin/keytool" -genkeypair -v -keystore meshron-upload.jks -alias meshron -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and your name. **You** type them; nobody else should see them. The project already has `keystore.properties` pointing at this key; the password is asked for at build time, never saved.

Both files are in `.gitignore`. **Back up `meshron-upload.jks` and the password somewhere safe.** If you lose them, you can't update the direct-download APK. On Google Play, you can ask Google to reset an upload key.

## 3. Build

In the project folder, run:

```powershell
.\build-release.ps1
```

It asks for your key password (not stored anywhere) and builds:
- `app/build/outputs/bundle/release/app-release.aab` (for Google Play)
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk` (most phones), `-armeabi-v7a-` (older phones), `-x86_64-` (emulators)

Before each new release, raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.

## 4. Google Play

1. **Developer account:** create one at https://play.google.com/console ($25 once, ID check).
2. **New app:** Create app → "Meshron", app, free. Accept Play App Signing (recommended).
3. **Privacy policy:** host `docs/PRIVACY.md` at a public URL. For example, a GitHub repository with Pages turned on, or any website you control. Paste the URL in App content → Privacy policy.
4. **Store listing:** use the text in `store/listing.md` and the images in `store/`.
5. **App content answers:**
   - **Ads:** No.
   - **App access:** all functions available without login.
   - **Content rating:** Communication app. Users can talk to each other: answer *Yes* to "users can interact/exchange content", *No* to everything else (no violence, gambling, etc.).
   - **Target audience:** 13+ (not for children).
   - **Data safety:**
     - Collected: *No data collected*. Shared: *No data shared*.
     - Data is encrypted in transit: *Yes*.
     - Users can request deletion: *Yes* (delete chats in the app, or uninstall).
     - Messages go phone to phone and never reach the developer, so they count as neither collected nor shared.
   - **Foreground service permissions:**
     - `specialUse`: "Stay connected" keeps a peer-to-peer connection open so messages arrive with no push server. It only runs when the user turns it on, and shows a notification.
     - `microphone` and `camera`: keep an ongoing voice or video call running when the user leaves the app.
     - Google may ask for a short video of each. Record turning on Stay connected, and a call continuing in the background.
   - **Location permission:** Android requires it to scan for Bluetooth / Wi-Fi Direct devices. Meshron never reads the location. It's only requested in the foreground.
6. **Testing first:** upload the `.aab` to **Internal testing** and install it from Play on two phones.
   - New personal developer accounts must also run a **closed test with at least 12 testers for 14 days** before Production is unlocked.
7. **Production:** once allowed, promote the release to Production and submit for review (usually a few days).

## 5. Direct download (APK)

1. Upload the three signed APKs from step 3 to a GitHub Release, or to your website.
2. Next to them, publish each file's SHA-256 so people can check the download:

   ```bash
   sha256sum app/build/outputs/apk/release/*.apk
   ```

3. Tell users: download the **arm64-v8a** APK, open it, and allow "Install unknown apps" for their browser or file manager when Android asks.
4. Updates must be signed with the same key (step 2), or Android refuses to install them over the old version.
