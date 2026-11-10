# Meshron: P2P messenger architecture

## 1. Networking choice

| Option | Works on Android? | NAT traversal | Notes |
|---|---|---|---|
| **Hyperswarm / HyperDHT via Bare Kit** (chosen) | Yes. Holepunch ships **Bare Kit**, a small JS runtime made for embedding in iOS/Android apps (Keet mobile runs on it). Node.js itself is *not* used. | UDP hole punching (UDX), with blind relays as fallback | Same stack as Keet. Hypercore/Autobase are available for sync. Needs Node + npm on the dev machine only, to bundle JS with `bare-pack`. |
| go-libp2p via gomobile | Yes (Berty uses it) | AutoNAT, DCUtR hole punching, circuit relay v2 | Mature, but needs Go + NDK toolchains and has a heavier binary. No built-in log sync like Hypercore. |
| jvm-libp2p | Partly | Weak hole punching support | Not production-ready on Android. |
| Tor onion services (Briar style) | Yes | Always works, since Tor carries the traffic | Slow, high battery use, no direct connections. |

**Decision:** Kotlin/Compose app, plus a **Bare Kit worklet** running Hyperswarm in JS. They talk over Bare Kit's IPC channel using a small message protocol. Pear Runtime is the desktop app shell and isn't used on Android. We use its building blocks (Bare, Hyperswarm, Hypercore) directly.

### Honest limitations
- "No server" means no *message* server. Hyperswarm still needs **DHT bootstrap nodes** to find the network. By default these are public nodes run by Holepunch, and we can run our own.
- Some carrier NATs (symmetric / CGNAT on both sides) can't be hole-punched. Those connections go through a **relay** that sees only encrypted bytes. The UI must show "Direct" or "Relayed".
- Android kills background sockets. Messages arrive only while the app (or a foreground service) is running. There's no push without a server. Offline delivery works by peers syncing when both are online again (Phase 6).

## 2. Identity and crypto
- Identity = **Ed25519 key pair**, generated from a 32-byte random seed (`SecureRandom`).
- The ID shown to users is the public key in **z-base-32** (52 chars), the same format Holepunch tools use.
- Private seed at rest is encrypted with **AES-256-GCM**. The AES key lives in **Android Keystore** and can't be exported. The seed is never written in plaintext.
- In Phase 2 the seed is decrypted in memory and passed to the worklet once at start. Hyperswarm derives the *same* key pair from it (`crypto.keyPair(seed)`). The Noise handshake then authenticates peers by this key.
- Libraries: Bouncy Castle lightweight API (Ed25519), plus platform Keystore and JCA. No custom crypto.

## 3. Code layout (packages in `:app`, split into Gradle modules later)

```
com.xalvxnlab.meshron  (code package; Play app ID is com.meshron.myapp)
├── crypto/       Keystore wrapper, Ed25519, z-base-32
├── identity/     IdentityRepository (create / load / rename)
├── data/         local storage (DataStore now, Room from Phase 3)
├── network/      (Phase 2) Bare worklet bridge, swarm, connection state
├── sync/         (Phase 4) message log replication
├── files/        (Phase 6) file transfer
└── ui/           Compose screens, theme, navigation
```

## 4. Networking as built (Phase 2)
- `bare-kit-kotlin` (JitPack, includes `libbare-kit.so`) runs `assets/app.bundle`, which is built from `src/main/js/app.js` by `bare-pack`.
- Native addons (`udx-native`, `sodium-native`…) are copied into `src/main/jniLibs` by `bare-link`. They need `libc++_shared.so` (NDK r27c), which is kept in `src/main/cxxruntime`.
- Gradle tasks `linkAddons` and `packWorklet` run before every build. They need Node.js and `npm install` in the project root.
- Each phone runs `swarm.listen()` on its identity key pair, so **the user's ID is their DHT address**. Adding a contact calls `swarm.joinPeer(theirKey)`, which keeps redialling. When an unknown key connects to us, it becomes a *request*.
- Kotlin ↔ JS: newline-delimited JSON over `Bare.IPC` (see the top of app.js).
- Peers swap a `hello` (name) over the Noise-encrypted stream. Message framing becomes protomux in Phase 3.
- No `relayThrough` is configured, so every connection is direct. If both NATs randomize ports, a connection can fail. Adding a blind relay is a later task.
- Tested: two emulators on one PC connect through the public DHT in about 3 seconds and reconnect after an app restart. **Not yet tested:** two different networks (e.g. a phone on mobile data).

## 5. Phases
1. Project, UI foundation, identity ✅
2. Bare Kit + Hyperswarm, connect two devices, diagnostics screen ✅
3. Encrypted 1:1 messages ✅. How it works:
   - Transport encryption: Hyperswarm's Noise XX handshake plus libsodium secretstream, keyed by both identities, so only the two devices can read the stream.
   - Every message is also **signed** (Ed25519) over `["meshron-msg-v1", id, ts, from, to, body]` and checked in the worklet before Kotlin sees it. That makes stored messages verifiable later (useful for sync) and binds each message to one conversation.
   - Delivery is at-least-once: saved as *Waiting* → *Sent* (written to the stream) → *Delivered* (peer stored it and acked). Anything not delivered is resent on every reconnect, and the receiver de-duplicates by (peer, id).
   - Storage: Room `messages` table in app-private storage, encrypted with SQLCipher since Phase 7.
   - Only contacts (added or pending request) can write to your disk. Unknown peers are dropped.
4. Local persistence and sync ✅. How it works:
   - **No central database.** Each 1:1 chat is two append-only **Hypercore logs**, one written by each side (an "outbox"), stored with Corestore/RocksDB in `files/corestore`.
   - Sending appends a signed entry to my outbox for that peer, which works offline. When the two devices connect, `store.replicate(conn)` syncs both logs over the same Noise stream, so anything written while either side was offline arrives automatically. **The log is the queue.**
   - Peers swap outbox keys in a `hello` on a Protomux channel (`meshron/dm/1`) that sits next to replication.
   - Logs are **block-encrypted**. The key is `BLAKE2b("meshron/dm-enc/v1" ‖ identity secret ‖ peer key)` and is shared only with that peer, so a future relay or seeder can store a log without reading it (Phase 6).
   - Each entry keeps its Ed25519 identity signature, checked before it's shown. The Hypercore signature ties entries to the log, and the identity signature ties them to the person.
   - The receiver acks `{ length }` after saving to Room, and the sender marks every entry below that as Delivered. Room stores each message's `seq`, so after a restart reading resumes where it stopped and no duplicates appear.
   - Room v1→v2 migration adds the `seq` column. Messages from the Phase 3 protocol that hadn't been delivered are re-appended to the log on startup.
   - The Corestore primary key (which derives the outbox signing keys) is stored by Corestore itself without Keystore wrapping; see Phase 7 for why that is acceptable.
   - Tested on two emulators: live messages; two messages written while the receiver was offline, followed by a **sender restart**, then both reconnecting (delivered, no duplicates); a receiver restart (no duplicates); replies in both directions.
5. Group conversations ✅. How it works:
   - **Why not Autobase?** Autobase merges several writers into one ordered view, which pays off for shared state such as documents. Chat only needs "show everyone's messages in time order", so each member writing their own log and merging by timestamp is simpler and has no single indexer. Autobase stays an option if groups later need shared state (e.g. edits or reactions).
   - A group has a random 32-byte **secret** and a member list chosen by its creator. From the secret come the logs' block-encryption key and a **Hyperswarm topic**. Members join that topic, so they connect to each other even if they aren't contacts.
   - Every member appends to their own log (`group/<id>`). Entries are signed over `["meshron-gmsg-v1", group, id, ts, from, body, kind]`.
   - Members **gossip signed announcements** (member → log key, signed by that member) in a `ghello`. A member can therefore fetch another member's log from anyone who has a copy, including while its author is offline. Corestore replicates any log both sides have open. Announcements are stored in Room (`group_logs`, v4) so this keeps working after restarts.
   - **Invites** are signed `kind: "invite"` entries in the 1:1 logs, so they reach people who are offline. They're accepted only from people you've added as contacts.
   - "Delivered to k of n" counts members who have acknowledged directly. A member who only got your message through someone else isn't counted until you connect to them.
   - Tested with Tester and Bob (emulators) plus **Dana**, a desktop test peer (`tools/test-peer.js`, the same worklet running under Node.js):
     - create the group and send invites;
     - the joined message reaches all members;
     - ping and pong;
     - Bob writes, then goes offline, and Dana (who was offline) gets the message **from Tester's copy**;
     - Bob comes back and catches up.
   - Limitations: members can't be added or removed after creation yet.
6. File sharing and offline sync ✅. How it works:
   - **Files:** each sender has a "files" Hypercore per chat (`files/dm/<peer>` or `files/group/<id>`). It's stored with **Hyperblobs** in 64 KB blocks and block-encrypted with the same key as that sender's chat log, so only chat members can decrypt it.
   - The file message (`kind: "file"`, signed like any other) carries the name, MIME type, size, **SHA-256**, blob-log key and blob position. The receiver downloads it automatically, checks size and SHA-256 against the signed message, and only then shows it. A tampered file is rejected.
   - In groups, any member that already has the blocks can serve them (Corestore attaches stored logs on request). Downloads that are waiting are retried whenever any peer connects.
   - Limit: 25 MB per file. Kotlin and the worklet exchange file data as base64 chunks over IPC.
   - Local copies live in `files/chatfiles/{sent,received}`, and other apps can open them only through a FileProvider limited to that folder.
   - **Offline sync:** the log is the queue (Phase 4), and groups relay through any member (Phase 5). New: Android network callbacks tell the worklet when the network changes or comes back. It then suspends and resumes Hyperswarm so sockets rebind and peers reconnect (tested: about 2 s).
   - **Distributed persistence, summed up:** no device or server holds "the" database. Every participant keeps a full signed copy of the logs they're part of, and any copy can serve the others.
   - Tested with Bob (emulator) and Dana (desktop test peer):
     - a photo in a group;
     - a 300 KB, five-block file from Dana, downloaded and hash-checked on the phone;
     - a photo in a 1:1 chat;
     - Wi-Fi and data off then on, followed by reconnection.
   - Not done: a dedicated always-on "blind seeder" for 1:1 chats (both people still need to be online at the same time, or share a group with someone who is). No thumbnails or progress for outgoing uploads beyond a spinner. A removed member would need a new key (re-keying), which isn't built.
7. Hardening ✅. What changed:
   - **Message database encrypted at rest** with SQLCipher 4.20 (AES-256). Its passphrase is 32 random bytes, stored only wrapped by a separate Android Keystore AES-GCM key (`files/db.key`, alias `meshron.db.wrap.v1`). This covers message bodies, group secrets, file references and group log keys. An existing plaintext database is copied into an encrypted one with `sqlcipher_export`. The copy is checked (same schema version and row count in every table) before the plaintext file is deleted. `DatabaseEncryption.kt` has the details.
   - **Contact list encrypted** with its own Keystore key (`meshron.contacts.wrap.v1`). An existing list is re-saved encrypted on first start.
   - **Spam limits.** Kotlin tells the worklet who is trusted (`trust` command):
     - added contacts: everything is downloaded;
     - pending requests: at most 20 log entries until accepted, then the rest;
     - anyone else: their log isn't opened at all.
     - At most 30 pending requests are kept. Further strangers are disconnected.
     - Files from pending requests aren't downloaded until you accept them. Group files still download as before.
   - **Optional background mode** ("Stay connected" in Profile). A foreground service (type `specialUse`) keeps the process, and so the worklet, alive while the app is in the background. Local notifications show new messages; on a locked screen they show "New message" only. There's no push server, so with the setting off, messages arrive only while the app is open. Android may still pause networking in deep sleep (Doze); this hasn't been measured.
   - **Release build fixed and slimmed down:**
     - The template's partial R8 setting (`packageScope`) made release builds crash at start (`IllegalAccessError`), so the whole app is now shrunk.
     - Native libraries are stripped of debug symbols when `meshron.ndkPath` in `local.properties` points to any NDK. This machine uses Unity's r27c NDK.
     - Release builds are split per CPU type (arm64-v8a, armeabi-v7a, x86_64). The arm64 APK is about 57 MB instead of one 232 MB APK. Most of what remains is `libbare-kit.so` (the JS engine, about 42 MB, already stripped).
   - **Tested** on the Medium Phone emulator (Bob) with the desktop test peers Dana and Eve (`tools/test-peer.js --spam <hex>`):
     - an existing plaintext database was upgraded in place with all chats kept, and the file header is now random bytes;
     - the contacts file holds only ciphertext;
     - stranger Eve sent 25 messages: 20 arrived as a request, and the other 5 arrived after Accept;
     - with "Stay connected" on and the app in the background, Eve's message showed up as a notification;
     - a release build signed with the debug key sent a group ping and got Dana's pong.
   - **Still open:**
     - **Corestore primary key:** Corestore (RocksDB) stores its own primary key unencrypted, and the library offers no way to keep it elsewhere. Someone with root access could forge Hypercore-level entries. These would still fail the identity signature check, because every message is signed with the Keystore-protected identity key. Log contents stay encrypted with keys derived from the identity seed or the group secret.
     - **Files:** sent and received files in `files/chatfiles` are stored as plain files so other apps can open them. Android's app sandbox protects them, but they aren't encrypted.
     - **Not yet done:** no relay, no blind seeder, no adding or removing group members, no re-keying.
     - **Package name:** renamed to `com.xalvxnlab.meshron` (app name "Meshron"). Links are `meshron://id/…`, and protocol and key labels use "meshron". This was a fresh start: old installs and links don't carry over.
     - **Untested:** two different real networks and a physical phone.
8. Offline and mesh ✅. Meshron works without internet between nearby phones, and phones carry messages for each other:
   - **Three ways to connect**, all of them into the same worklet code path (`onconnection`, Noise handshake with the identity key, Hypercore sync, mesh):
     - **Internet:** Hyperswarm, as before. It can now be switched off ("Use internet").
     - **Same Wi-Fi:** `LanTransport.kt`. Each phone announces a TCP port with mDNS (`_meshron._tcp`) under a random per-run session ID, never the user's ID. Phones on the same Wi-Fi or hotspot connect even if that network has no internet. Android 17 requires `ACCESS_LOCAL_NETWORK` for this.
     - **Bluetooth / Wi-Fi Direct:** `NearbyTransport.kt`, using Google Nearby Connections (`play-services-nearby` 19.5.1, strategy `P2P_CLUSTER`). Phones are found over Bluetooth, and the data moves to Wi-Fi Direct when possible. Nearby still requires location permission to scan, even on Android 17; the app never reads the location.
     - For same Wi-Fi and Nearby, Kotlin owns the socket or endpoint and passes raw bytes to the worklet (`link-open`, `link-data`, `link-close`). The worklet runs `@hyperswarm/secret-stream` (Noise) over them, so nearby links are authenticated and encrypted exactly like internet ones. The phone with the smaller session ID dials, so a pair makes one link.
     - One peer can be connected several ways at once. The newest connection is used, and the others take over if it closes.
   - **Chat keys are now derived by both sides:**
     - Both people compute an X25519 shared secret from their Ed25519 identity keys (libsodium `crypto_sign_ed25519_*_to_curve25519` + `crypto_scalarmult`).
     - From it come each direction's log encryption key, `BLAKE2b("meshron/dm-enc/v2" ‖ shared ‖ author)`, and a mesh tag, `BLAKE2b("meshron/mesh-dm/v1" ‖ shared ‖ author)`.
     - So two people who added each other can exchange messages without ever having been connected directly.
     - DM logs moved to `dm2/<peer>` (and file logs to `files2/dm/<peer>`). The hello no longer carries an encryption key.
   - **Mesh (store and forward):**
     - On every connection, phones swap a `mesh` message: up to 300 `[log key, tag]` records for the logs they hold.
     - If a tag matches one of my contacts (or a group member), I open that log and read it. Otherwise, with "Carry messages for others" on, I store the log (up to 100 logs, 5000 entries each) and offer it to everyone I meet later.
     - Carried logs are encrypted and the tags are opaque hashes. A carrier can't read messages and can't tell who wrote to whom, though it can see how much data is stored.
     - Every message is still checked against the author's identity signature.
     - Carried records are remembered in `files/mesh-carry.json`.
     - Group member logs are gossiped the same way (tag from the group secret).
   - Phones met nearby that aren't contacts are mesh neighbours: they don't become contact requests.
   - Contacts who are offline are re-dialled every 30 s, instead of waiting for Hyperswarm's growing backoff. Tested: a contact was found 20 s after coming online, where before it took more than 3 minutes.
   - **Tested** on two emulators: Medium Phone ("Bob") and a second AVD, `Meshron_B` ("Cara"). They share one virtual Wi-Fi and the emulator's virtual Bluetooth.
     - Same Wi-Fi only (Cara's internet and Bluetooth off): a message was delivered.
     - Bluetooth / Wi-Fi Direct only (Cara's internet and Wi-Fi off): a message was delivered.
     - **Mesh:** Cara (no internet) wrote to Dana (desktop test peer, offline at the time). Bob carried the log over Bluetooth. Dana later connected only to Bob over the internet and received Cara's signed message.
     - On the desktop (`tools/test-peer.js --offline --listen/--dial`): A → B → C with no internet at all, where A and C never met.
     - The release build (R8) runs all three transports.
   - **Not tested:** real phones and real Bluetooth range. Emulators simulate the radio. Expect roughly 10–100 m, slower than Wi-Fi, and Android may pause Bluetooth scanning in the background unless "Stay connected" is on.
   - **Limits:**
     - Files aren't carried by the mesh, only messages and invites. Files still need a direct connection to someone who has them.
     - "Delivered" only shows once the recipient's acknowledgement reaches you directly; a message relayed by the mesh shows "Sent" until then.
     - The mesh carries logs for at most 100 conversations per phone. When that is full, new ones aren't carried.
9. Voice calls ✅. 1:1 voice calls with added contacts, over whatever live connection exists: internet, same Wi-Fi, or Bluetooth / Wi-Fi Direct. No server is involved.
   - **Signalling and audio** travel inside the existing encrypted connection (Noise), on a second Protomux channel `meshron/call/1`:
     - Message 0 (JSON): `offer`, `answer`, `reject` (`declined` / `busy`), `end`, each with a random call ID.
     - Message 1 (bytes): one audio packet.
     - The worklet drops call messages from anyone who isn't an added contact, so strangers can't ring you.
     - If a contact is connected several ways, the newest connection carries the call. If it drops and another remains, audio continues on that one.
   - **Audio** (`calls/VoiceEngine.kt`):
     - Android's built-in Opus codec (Android 10+), 16 kHz mono, 24 kbit/s.
     - Two 20 ms frames per packet: 25 packets a second, about 7 KB/s, small enough for Bluetooth.
     - The mic uses the voice-call source with echo cancellation, noise suppression and gain control when the phone has them.
     - The links are reliable (no lost packets), so a slow link means late audio. The player skips ahead when more than about 240 ms is queued, to keep the delay short.
   - **Call logic** (`calls/CallManager.kt`):
     - One call at a time; a second caller gets "busy".
     - Unanswered calls ring for 45 s.
     - If the connection drops mid-call, it waits 15 s for it to come back (any way) before ending with "Connection lost".
     - The ringtone respects silent mode. The caller hears a ring-back tone.
     - During a call the screen turns off at the ear (proximity sensor). A microphone foreground service (`CallService`) keeps the call running if you switch apps.
   - **UI:**
     - A phone button in a contact's chat.
     - A full-screen call view with Accept / Decline, or Mute, Speaker and Hang up, plus the timer and the connection type.
     - Incoming calls while the app is in the background show a notification with Decline. Tapping it opens the call screen.
     - Missed calls show a notification.
   - **Tested** (audio packet counters in logcat, both directions, 0 dropped):
     - Internet: Bob called Dana (desktop test peer, which answers and echoes the audio back with `tools/test-peer.js`); 577 packets sent, 576 received.
     - Bluetooth / Wi-Fi Direct only: Bob called Cara; 887 sent, 882 received in 36 s.
     - Same Wi-Fi only: Cara called Bob; 374 / 374 after 15 s.
     - Also tested: decline, hang up from either side, mute and speaker buttons, and calling someone who isn't connected.
   - **Not tested:** real phones and real voices. The emulators' microphones send silence, so the counters show that audio flows and is decoded, not how it sounds. Echo on speakerphone, Bluetooth headsets and actual Bluetooth range still need two real phones.
   - **Limits:**
     - Calls don't go through the mesh: you need a live connection to the person.
     - Incoming calls only ring while Meshron is running: the app is open, or "Stay connected" is on.
     - There is no relay server, so over the internet some network pairs can't connect directly, and then neither messages nor calls go through live.
     - No video and no group calls.
10. UI refresh ✅. Same features, easier to use:
   - **Look:** coloured avatars (one colour per person, derived from their key), rounded shapes, and a status pill in the top bar ("Online", "Nearby only", "Connecting").
   - **Tabs:** Chats / Contacts / Me, with badges for unread messages and contact requests.
   - **Chats:** "Yesterday" and weekday times instead of only clock times. A "New chat" button opens a sheet with: add a contact, new group, or pick a contact. An empty list shows "Add a contact" and "Share my ID".
   - **Chat screen:**
     - Avatar and status in the header.
     - Day separators.
     - Ticks for sent and delivered.
     - Long-press a message to copy it.
     - Accept / Ignore inside a chat with someone who isn't a contact yet.
   - **Contacts:**
     - A "Your Meshron ID" share card at the top.
     - Online contacts listed first.
     - Remove moved into a ⋮ menu.
     - The add dialog has a paste button and checks the ID as you type.
   - **Me & settings:** profile with Share / Copy / QR; connection switches in plain words with icons; Notifications; Privacy; technical details folded away.
   - **Onboarding:** three short points (no account, no server, works without internet), then a name screen with a live avatar preview.
   - **Call screen:** big avatar and round buttons for mute, speaker and hang up.
   - Adds `material-icons-extended`. R8 removes unused icons, so the release APK grew by about 0.2 MB.
