# Meshron on the Microsoft Store: everything to enter in Partner Center

Partner Center: https://partner.microsoft.com/dashboard → Apps and games → New product.

## Before you start: two things to fix

1. **The privacy policy page is out of date.** https://xalvxn-dev.github.io/meshron/PRIVACY.html still says Meshron has
   "no accounts" and sends nothing anywhere. Today's app has Meshron accounts, AI features (text goes to Groq and Google
   Gemini), moderation, reels, live and SOS. Microsoft checks that the policy matches the app. Update `PRIVACY.md`
   (the in-app text in `cross/ui/legal-text.js` is already accurate) before you submit.
2. **Pick the package type.** Partner Center asks this when you create the product:
   - **MSIX (recommended, free).** The Store signs MSIX packages for you. electron-builder can build one (`appx`
     target) once Partner Center gives you the identity values below.
   - **EXE/MSI.** The `.exe` must be signed with a code-signing certificate from a trusted authority (self-signed is
     refused). `Meshron-1.6.5-Windows-Setup.exe` is **not signed** today, so this route means buying a certificate.

## 1. Create the product

| Field | Enter |
|---|---|
| Product type | MSIX or PWA app (recommended) — or "EXE or MSI app" |
| Reserve a name | `Meshron` (backup: `Meshron Messenger`) |

## 2. Product identity (MSIX only: Product management → Product identity)

Partner Center shows these. Copy them into `cross/desktop/package.json` → `build.appx` before building the MSIX:

| Partner Center shows | Goes into |
|---|---|
| Package/Identity/Name | `identityName` |
| Package/Identity/Publisher (`CN=…`) | `publisher` |
| Package/Properties/PublisherDisplayName | `publisherDisplayName` |

Also set `applicationId: "Meshron"`, `displayName: "Meshron"`, `backgroundColor: "#0B0D12"`.

## 3. Packages (EXE route only)

| Field | Enter |
|---|---|
| Package URL | A versioned HTTPS link that never changes, e.g. a GitHub release asset: `https://github.com/xalvxn-dev/meshron/releases/download/v1.6.5/Meshron-1.6.5-Windows-Setup.exe` |
| Architecture | x64 |
| Language | English (United States) |
| Installer parameters (silent) | `/S` (Meshron's installer is NSIS) |
| Return codes | Defaults |

(MSIX route: upload the `.msix` file here instead.)

## 4. Pricing and availability

| Field | Enter |
|---|---|
| Markets | All markets |
| Visibility | Public audience, discoverable |
| Pricing | Free |
| Free trial | No free trial |
| Release | As soon as it passes certification |

## 5. Properties

| Field | Enter |
|---|---|
| Category | Social |
| Subcategory | (none) |
| Privacy policy | Yes, the app accesses, collects or transmits personal information |
| Privacy policy URL | `https://xalvxn-dev.github.io/meshron/PRIVACY.html` (after updating it) |
| Website | `https://github.com/xalvxn-dev/meshron` |
| Support contact info | `baxxkid@gmail.com` |
| Game settings | Not a game |
| Display mode | (leave default) |
| Product declarations | Tick "This app allows users to make purchases…" **No** · "depends on non-Microsoft drivers" **No** · "accessibility guidelines" leave unticked unless tested · "can be installed on alternate drives" **Yes** (MSIX) |
| System requirements | Windows 10 version 1809 or later, x64. Recommended: camera and microphone (calls), 4 GB RAM |

## 6. Age ratings (IARC questionnaire)

| Question | Answer |
|---|---|
| App category | Social networking / communication |
| Users can interact or exchange content (chat, voice, video) | **Yes** |
| Users can share their physical location | **Yes** (SOS shares location with chosen contacts) |
| Unrestricted internet access (browser) | **No** |
| Digital purchases | **No** |
| Violence, fear, sexuality, gambling, drugs, crude humour, bad language in the app's own content | **No** to each |
| User-generated content that isn't moderated | It **is** moderated (AI checks and reporting); 18+ groups are age-gated |

Expect a rating of about 12+/Teen because of user interaction and shared content.

## 7. Store listing (English)

**Product name:** Meshron

**Short description (≤ 100 characters):**
Private chats, calls and moments with nobody in the middle. Works phone to phone and on your PC.

**Description:**
Meshron is a messenger built for privacy. Messages and calls go straight between you and your friends, end-to-end encrypted, with nobody in the middle.

CHAT AND CALL
• End-to-end encrypted chats, voice calls and video calls
• Groups, photos, files and voice messages
• Messages translate themselves into your language

SHARE
• Moments that disappear, reels and going live from your camera, screen or a game
• Meshron AI: ask, plan and translate, or tag @meshronai in a group

STAY SAFE
• Emergency SOS sends your message and location to the people you choose
• Lock the app, use anonymous mode, block and report
• AI moderation and 18+ groups that are age-gated

WORKS WITHOUT INTERNET (on phones)
• Same Wi-Fi, Bluetooth and Wi-Fi Direct; friends' phones can carry your locked messages

Made by xalvxnLab.

**What's new in this version:** A cleaner, more polished look; an Activity tab for likes, comments, follows and lives.

**Product features (one per line, up to 20):**
End-to-end encrypted chats and calls
Voice and video calls
Group chats, photos, files and voice messages
Automatic translation
Moments, reels and live
Meshron AI helper
Emergency SOS with location
App lock and anonymous mode
Works on Windows and Android with the same account

**Screenshots:** Desktop, at least 1 (up to 10), PNG, 1366×768 or larger. Use the before/after shots in
`Downloads\Meshron 1.6.5\screenshots` (desktop ones), placeholder people only.

**Store logos:** 1:1 app icon 300×300 (and 150×150, 71×71), from `store/icon-512.png`.

**Search terms (up to 7):** messenger, private chat, encrypted, video call, chat app, secure messaging, Meshron

**Copyright and trademark info:** © 2026 xalvxnLab

**Additional license terms:** leave empty (or link the in-app Terms if you publish them)

**Developed by:** xalvxnLab

## 8. Submission options → Notes for certification

```
Meshron needs a free Meshron account: tap "Create an account" on first launch and pick any username and password.
Calls and video use the camera and microphone only when the user starts a call or goes live.
AI features send only the text the user chooses to the AI service; this is described in the privacy policy.
```
