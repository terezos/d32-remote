#!/bin/zsh
# Αφαιρεί το D32 Remote από την TV.   TV_IP=192.168.1.6 ./scripts/uninstall.command
TV_IP=${TV_IP:-192.168.1.6}
ADB=$HOME/Library/Android/sdk/platform-tools/adb; [ -x "$ADB" ] || ADB=$(command -v adb)
dev=$("$ADB" devices | awk -v ip="$TV_IP" '$2=="device" && ($1 ~ ip || $1 ~ /^adb-.*_adb-tls-connect/){print $1; exit}')
[ -z "$dev" ] && { echo "Η TV δεν είναι συνδεδεμένη στο adb"; exit 1; }
"$ADB" -s "$dev" uninstall gr.george.d32remote && echo "✓ Αφαιρέθηκε"
