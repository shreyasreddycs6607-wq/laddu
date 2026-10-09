# Laddu Raspberry Pi camera agent

A tiny service that turns a **Raspberry Pi B+ + USB webcam** into a network camera. It only captures and serves frames;
all AI stays on the phone / PC / cloud, because a Pi B+ (700 MHz single core, 512 MB) cannot run detection models.

**Verification status:** the frame splitter, token auth and HTTP endpoints are unit-tested (`python test_agent.py`,
9 tests) and the MJPEG stream was smoke-tested on a Windows PC with a generated test image. **It has NOT been run on a
real Pi B+ or with a real webcam yet.** Laddu's Android app does not consume this stream yet (see "What is not built").

## Hardware reality check (Pi B+ V1.2)

* No Wi-Fi: connect it with **Ethernet** (or a USB Wi-Fi dongle). The micro-USB port is power only.
* Use **Raspberry Pi OS Lite (32-bit)** (the B+ is ARMv6). Flash it with Raspberry Pi Imager and enable SSH there.
* The webcam must support **MJPEG** (most UVC webcams do): `v4l2-ctl --list-formats-ext` should list `MJPG`.
  Power: a bus-powered webcam on the B+ needs a good 2 A supply; if the Pi reboots, use a powered USB hub.
* Keep it light: 640x480 at 2 fps is a sensible start. Higher rates may overload the single core.

## Install (on the Pi, over SSH)

```bash
sudo apt update && sudo apt install -y ffmpeg python3 v4l-utils
sudo mkdir -p /opt/laddu-pi /etc/laddu-pi
sudo cp laddu_pi_agent.py /opt/laddu-pi/
sudo cp config.example.json /etc/laddu-pi/config.json
sudo nano /etc/laddu-pi/config.json          # set a long random "token" (the agent refuses to start with a weak one)
sudo cp laddu-pi-agent.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now laddu-pi-agent
journalctl -u laddu-pi-agent -f              # logs
```

Check it from your PC (replace the address and token):

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://PI_ADDRESS:8080/health
curl -H "Authorization: Bearer YOUR_TOKEN" -o snap.jpg http://PI_ADDRESS:8080/snapshot.jpg
```

`/health` reports `camera_ok`, frame age, CPU load, memory, temperature and the last capture error.

## Behaviour

* **Reconnects** automatically: if the webcam is unplugged or stops delivering frames, capture restarts with back-off (2 s up to 30 s).
* **No disk writes** (frames live in memory only; logs go to journald) to spare the SD card.
* `motion_hint` in `/health` is only a cheap size-change hint, not detection.
* Starts on boot via systemd, no monitor or keyboard needed.

## Security

* Every request needs the token; comparison is constant-time and the token is never logged.
* The server speaks **plain HTTP**. Use it only on a trusted home network, or put it behind a VPN (e.g. Tailscale/WireGuard)
  or a TLS reverse proxy. Do not expose port 8080 to the internet.

## Troubleshooting

| Symptom | Check |
|---|---|
| `camera_ok: false`, error "not found" | `ls /dev/video*`; replug the webcam; try another USB port |
| error "ffmpeg is not installed" | `sudo apt install ffmpeg` |
| Pi reboots when the webcam starts | power supply / powered USB hub |
| Choppy or CPU at 100 % | lower `fps` or `size` in the config |
| "Refusing to start" | set a token of 12+ characters in `/etc/laddu-pi/config.json` |

## What is not built (honest)

* The Laddu Android app cannot yet view this stream or run its hazard detector on it. A PC-side bridge that pulls
  `/snapshot.jpg` and runs detection is the natural next step; the app's WebRTC pipeline is unchanged.
* No TLS, no per-device pairing: it is a simple token-protected LAN service.
