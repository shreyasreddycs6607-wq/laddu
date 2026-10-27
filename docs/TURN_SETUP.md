# TURN setup

STUN alone connects most phone pairs. When it cannot (symmetric NAT, some carrier-grade NAT, campus Wi-Fi blocking UDP), a **TURN relay** is required. Without TURN, such pairs will not connect — Laddu says "Could not connect" instead of pretending.

## Option A — self-hosted coturn (recommended, free)
1. A small VPS with a public IP (any 1 vCPU / 512 MB works). Open **UDP/TCP 3478**, **TCP 5349** (TLS), and UDP **49152–65535**.
2. Install and configure `/etc/turnserver.conf`:
   ```
   listening-port=3478
   tls-listening-port=5349
   fingerprint
   use-auth-secret
   static-auth-secret=<LONG RANDOM SECRET>
   realm=laddu
   total-quota=20
   no-multicast-peers
   no-cli
   # cert=/etc/letsencrypt/live/turn.example.com/fullchain.pem
   # pkey=/etc/letsencrypt/live/turn.example.com/privkey.pem
   ```
3. Give the secret and URLs to the Cloud Function:
   ```bash
   firebase functions:secrets:set TURN_SECRET          # paste the same static-auth-secret
   # functions/.env (git-ignored) or deploy param:
   TURN_URLS=turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp
   firebase deploy --only functions
   ```
The function `getTurnCredentials` returns `username = <expiry>:<uid>`, `credential = base64(HMAC-SHA1(secret, username))`, valid 6 h. The app caches them and refreshes automatically. **The secret never leaves the server.**

## Option B — hosted TURN (Twilio, Metered, Cloudflare…)
Mint credentials server-side inside `getTurnCredentials` using the provider's REST API (store the API key as a Functions secret) and return `{urls, username, credential}`.

## Option C — development only
Put static values in `local.properties` (git-ignored; baked into *your* debug APK — never ship it):
```
laddu.turn.url=turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp
laddu.turn.username=dev
laddu.turn.credential=dev-password
```

## Test it
Camera on home Wi-Fi, viewer on mobile data, Settings → Network → **Always use relay** ON → Live. If video appears, TURN works end-to-end. (Turn the switch off again to save relay bandwidth.)

## Home Proxmox server (Option A on your own hardware) — script written, NOT yet run
1. In the Proxmox web UI open the node's **Shell** and create a small container (check names with `pveam available --section system | grep ubuntu`; adjust storage, bridge, IP and gateway to your network):
   ```bash
   pveam update && pveam download local <ubuntu-template-from-the-list>
   pct create 200 local:vztmpl/<that-template> --hostname turn --memory 512 --cores 1 \
     --rootfs local-lvm:4 --unprivileged 1 --onboot 1 \
     --net0 name=eth0,bridge=vmbr0,ip=192.168.1.68/24,gw=192.168.1.1
   pct start 200 && pct enter 200
   ```
2. Inside the container: `apt-get update && apt-get install -y curl`, fetch `scripts/turn/setup_coturn.sh` from this repo, then
   `bash setup_coturn.sh <your-public-IP-or-DDNS-name>`. It prints the three `laddu.turn.*` lines once.
3. Router: forward UDP+TCP 3478 and UDP 49152-49252 to the container. Your ISP must give you a real public IP (no CGNAT); check that the
   router's WAN address equals what a "what is my IP" site shows. Home IPs change, so a DDNS name is better than a raw IP.
4. Put the printed lines in `local.properties`, rebuild the debug APK, then do the **Test it** steps above.
Security notes: the script blocks relaying into private networks (except the container itself) and caps allocations; the static password is
dev-only and lives in the debug APK. Production should use `getTurnCredentials` (needs Blaze). Not verified against real traffic.
