#!/usr/bin/env bash
# Installs coturn (TURN relay) on a fresh Ubuntu/Debian VM or LXC container. Run as root, inside the VM.
#   bash setup_coturn.sh <public-ip-or-ddns-name>
# Static dev login (works without Firebase Blaze). The generated password is printed ONCE and never stored in the repo.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run as root."; exit 1; }
PUBLIC="${1:?usage: setup_coturn.sh <public-ip-or-ddns-name>}"
PRIVATE_IP="$(hostname -I | awk '{print $1}')"

# a DNS name must be resolved to an IP for external-ip
if [[ "$PUBLIC" =~ ^[0-9.]+$ ]]; then PUBLIC_IP="$PUBLIC"; else PUBLIC_IP="$(getent hosts "$PUBLIC" | awk '{print $1; exit}')"; fi
[ -n "$PUBLIC_IP" ] || { echo "Could not resolve $PUBLIC"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq coturn openssl
sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn

PASS="$(openssl rand -hex 16)"
cat > /etc/turnserver.conf <<EOF
listening-port=3478
fingerprint
lt-cred-mech
realm=laddu
user=laddu:${PASS}
external-ip=${PUBLIC_IP}/${PRIVATE_IP}
# keep the forwarded UDP range small
min-port=49152
max-port=49252
total-quota=20
no-multicast-peers
no-cli
# a relay must not be a way into the home network
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
allowed-peer-ip=${PRIVATE_IP}
log-file=/var/log/turnserver.log
simple-log
EOF
chmod 640 /etc/turnserver.conf
systemctl enable coturn >/dev/null 2>&1
systemctl restart coturn
sleep 2
systemctl is-active coturn

cat <<EOF

coturn is running on ${PRIVATE_IP} (advertising ${PUBLIC_IP}).
Add these 3 lines to laddu's local.properties on your PC (git-ignored, debug builds only), then rebuild:

laddu.turn.url=turn:${PUBLIC}:3478?transport=udp,turn:${PUBLIC}:3478?transport=tcp
laddu.turn.username=laddu
laddu.turn.credential=${PASS}

Router: forward UDP+TCP 3478 and UDP 49152-49252 to ${PRIVATE_IP}.
EOF
