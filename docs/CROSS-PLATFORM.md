# Meshron for iPhone and Windows (shared app)

Status: in progress. Android stays the native Kotlin app; iPhone and Windows share one app.

## Layout

```
cross/
  core/                 runs in the background engine (Bare on iPhone, Node in Electron)
    host.js             everything the Android app's Kotlin services do: identity, contacts,
                        messages, groups, read marks, files, settings, network state, calls
                        signalling. Same formats as Android, so all three talk to each other.
    store.js            small JSON-file storage (atomic writes)
    zbase32.js          IDs and recovery keys (same alphabet as Android)
    boot-node.js        Electron/Node entry: Bare shim + host, talks to the window over IPC
    boot-bare.js        iPhone entry: host inside the Bare Kit worklet, talks to Swift over Bare.IPC
  ui/                   the screens (plain HTML/CSS/JS, no build step), gradient design
    index.html, styles.css, app.js
    bridge.js           window.meshron.call(method, args) / .on(event, fn), per platform
  desktop/              Electron shell (Windows): main.js, preload.js, package.json
  ios/                  Swift shell: WKWebView + BareKit worklet, XcodeGen project.yml
.github/workflows/ios.yml   builds the unsigned .ipa on GitHub's macOS runners
```

## Networking core

`app/src/main/js/app.js` is shared unchanged in behaviour. It talks JSON lines over an IPC
object. It now takes `global.MESHRON_IPC` when set (host runs it in-process), otherwise
`Bare.IPC` (Android). Commands/events are listed at the top of app.js and handled on Android
by `NetworkService.kt`; host.js mirrors that file.

## Compatibility rules (must match Android)

- Identity: 32-byte Ed25519 seed; public key = crypto_sign_seed_keypair(seed). ID and
  recovery key are z-base-32 of public key / seed. Share link `meshron://id/<id>?name=<urlenc>`.
- Messages: 1:1 rows keyed (peer, id); group conversation "g:<groupId>", row id
  "<sender hex first 16>:<wireId>". Status 0 pending, 1 sent, 2 delivered, 3 received.
  Kinds text / invite / file. Invite JSON {id,name,secret,creator,members}.
- Files: body JSON {name,mime,size,sha256,blobs,blob}; wire conversation "dm:<peer>" or "g:<id>";
  max 25 MB; verify SHA-256 before showing.
- Resume cursors: max(seq)+1 per sender, merged with read marks.
- Contacts: ADDED / REQUEST / BLOCKED, max 30 pending requests, requests only from internet.

## Platform notes

- iPhone: no Bluetooth/Wi-Fi Direct link to Android (Apple uses its own system). Internet and
  relays work. iOS suspends apps in the background and there's no push server, so messages
  arrive while the app is open or shortly after. Seed kept in the iOS Keychain.
- Windows: seed wrapped with Electron safeStorage (DPAPI). Runs in the tray to stay online.
- Sideloaded iPhone builds (free Apple ID) must be reinstalled every 7 days.

## Build

- Windows: `cd cross/desktop && npm install && npm run dist` → installer in `cross/desktop/dist`.
- iPhone: push, then run the "Build iPhone .ipa" workflow; download the artifact; install
  with Sideloadly.
