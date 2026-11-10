# Running a Meshron relay

Two phones on mobile data often can't connect to each other directly: carriers block
incoming connections on both. Meshron then connects them through a **relay**.

- The relay only passes on encrypted bytes. Messages, calls and files stay end-to-end
  encrypted between the two phones; the relay can't read them.
- It sees the two phones' IP addresses. `relay-node.js` doesn't log them.
- Apps find relays by themselves (DHT topic `BLAKE2b("meshron/relay/v1")`), so you don't
  need to change or rebuild the app.
- Phones with an open connection and **Pass messages on** switched on also relay for
  others. Your relay is the always-on backup.

## Option A: a free cloud VM (recommended)

Any small Linux VM with a public IP works, for example Oracle Cloud "Always Free",
Google Cloud e2-micro (free tier) or a $4/month VPS.

```bash
# on the VM
sudo apt update && sudo apt install -y nodejs npm
mkdir meshron-relay && cd meshron-relay
npm init -y
npm install hyperdht blind-relay hypercore-crypto b4a
# copy tools/relay-node.js from this project into this folder, then:
node relay-node.js
```

It prints its public key and `announced on the Meshron relay topic`. To keep it running
after you log out and after reboots:

```bash
sudo npm install -g pm2
pm2 start relay-node.js --name meshron-relay
pm2 save && pm2 startup
```

Open the VM's firewall for **UDP** (all ports, or at least the port shown in the
"announced" line). On Oracle Cloud that's the subnet's Security List.

## Option B: an always-on PC at home

```bash
cd C:\Users\xalvx\AndroidStudioProjects\myapp
node tools/relay-node.js
```

Works while the PC is on. Behind a home router it's reachable for most phones, but a
cloud VM is more reliable.

## Checking it works

Every minute the relay prints `sessions N, pairings active M`. When two phones talk
through it, `pairings active` goes above 0.

The key is kept in `relay.seed`, so the relay keeps the same identity after restarts.
