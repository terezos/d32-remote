#!/bin/zsh
# Μαζεύει πληροφορίες για το γιατί σταμάτησε το D32 Remote (crash / kill).
cd "$(dirname "$0")/.."
TV_IP=${TV_IP:-192.168.1.6}
PKG=gr.george.d32remote
ADB=$HOME/Library/Android/sdk/platform-tools/adb; [ -x "$ADB" ] || ADB=$(command -v adb)
OUT=diagnose.log
pick() { "$ADB" devices | awk -v ip="$TV_IP" '$2=="device" && ($1 ~ ip || $1 ~ /^adb-.*_adb-tls-connect/){print $1; exit}'; }
dev=$(pick)
if [ -z "$dev" ]; then
  ep=$("$ADB" mdns services 2>/dev/null | awk -v ip="$TV_IP" '/_adb-tls-connect/ && $0 ~ ip {print $NF; exit}')
  [ -n "$ep" ] && "$ADB" connect "$ep" >/dev/null; dev=$(pick)
fi
if [ -z "$dev" ]; then print -n "Θύρα Wireless debugging (IP address & Port): "; read port; "$ADB" connect "$TV_IP:$port"; dev="$TV_IP:$port"; fi
A() { "$ADB" -s "$dev" "$@"; }
{
  echo "== process";      A shell pidof $PKG || echo "NOT RUNNING"
  echo "== exit-info";    A shell dumpsys activity exit-info $PKG | head -60
  echo "== a11y";         A shell dumpsys accessibility | grep -E "Bound services|Enabled services"
  echo "== service";      A shell dumpsys activity services $PKG | grep -E "ServiceRecord|isForeground|startRequested" | head
  echo "== crash buffer"; A logcat -b crash -d | tail -80
  echo "== app/system log (last)"; A logcat -d | grep -iE "d32remote|AndroidRuntime|lowmemorykiller|ActivityManager: (Killing|Process).*d32" | tail -60
} > $OUT 2>&1
echo "✓ γράφτηκε το $OUT"
