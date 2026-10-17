#!/bin/zsh
# D32 Remote — build (release) + εγκατάσταση + ρύθμιση στην Google TV μέσω adb.
#   TV_IP=192.168.1.6 ./scripts/install.command
cd "$(dirname "$0")/.."
TV_IP=${TV_IP:-192.168.1.6}
PKG=gr.george.d32remote
SVC="$PKG/$PKG.VolumeKeyService"

say() { print -P "\n%F{green}==> $1%f"; }
die() { print -P "\n%F{red}✗ $1%f"; exit 1; }

# ---- adb
ADB=$HOME/Library/Android/sdk/platform-tools/adb
[ -x "$ADB" ] || ADB=$(command -v adb) || die "Δεν βρέθηκε adb (εγκατάστησε Android Studio ή platform-tools)"

# ---- σύνδεση στην TV
pick() { "$ADB" devices | awk -v ip="$TV_IP" '$2=="device" && ($1 ~ ip || $1 ~ /^adb-.*_adb-tls-connect/){print $1; exit}'; }
dev=$(pick)
if [ -z "$dev" ]; then
  ep=$("$ADB" mdns services 2>/dev/null | awk -v ip="$TV_IP" '/_adb-tls-connect/ && $0 ~ ip {print $NF; exit}')
  [ -n "$ep" ] && "$ADB" connect "$ep" >/dev/null
  dev=$(pick)
fi
if [ -z "$dev" ]; then
  print -n "Θύρα από την οθόνη Wireless debugging της TV (IP address & Port): "; read port
  "$ADB" connect "$TV_IP:$port"; dev="$TV_IP:$port"
fi
A() { "$ADB" -s "$dev" "$@"; }
A get-state >/dev/null 2>&1 || die "Δεν συνδέθηκε στην TV (πάτα Allow στην TV αν ρωτήσει)"
say "TV: $dev ($(A shell getprop ro.product.model | tr -d '\r'), Android $(A shell getprop ro.build.version.release | tr -d '\r'))"

# ---- build
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
[ -d "$JBR" ] && export JAVA_HOME="$JBR"
if [ -x ./gradlew ]; then GRADLE=./gradlew
else
  GRADLE=$(ls -d $HOME/.gradle/wrapper/dists/gradle-8.*/*/gradle-8.*/bin/gradle 2>/dev/null | sort -V | tail -1)
  [ -z "$GRADLE" ] && GRADLE=$(command -v gradle)
  [ -z "$GRADLE" ] && die "Δεν βρέθηκε Gradle. Άνοιξε το project μία φορά στο Android Studio και ξανατρέξε."
fi
say "Build"
"$GRADLE" --no-daemon -q assembleRelease || die "Build failed"
APK=app/build/outputs/apk/release/app-release.apk

# ---- install & setup
say "Εγκατάσταση"
A install -r $APK || die "Install failed"
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do A shell pm grant $PKG android.permission.$p 2>/dev/null; done
A shell am start -n $PKG/.MainActivity >/dev/null; sleep 2; A shell input keyevent KEYCODE_HOME

bound() { A shell dumpsys accessibility | grep "Bound services" | grep -q "D32"; }
sleep 2
if ! bound; then
  A shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
  A shell settings put secure enabled_accessibility_services null; sleep 1
  A shell settings put secure enabled_accessibility_services "$SVC"
  A shell settings put secure accessibility_enabled 1; sleep 3
fi
if bound; then say "Accessibility ✓"
else
  print "Στην TV: Settings → System → Accessibility → D32 Remote → OFF και ON. Μετά Enter."; read
fi

say "Έτοιμο. Δοκίμασε τα κουμπιά έντασης του τηλεχειριστηρίου."
