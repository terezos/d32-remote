# D32 Remote: τεκμηρίωση

Εσωτερική τεκμηρίωση του project: τι είναι, γιατί υπάρχει, πώς δουλεύει και πώς το συντηρούμε.

---

## 1. Τι είναι

Μια εφαρμογή Android TV που τρέχει στην **TCL 65P8K** (Google TV, Android 14). Κάνει **τα κουμπιά έντασης του τηλεχειριστηρίου της TV να ρυθμίζουν την ένταση του ηχείου Edifier D32**.

Το ηχείο παίρνει ήχο από την TV με καλώδιο. Η εντολή έντασης πηγαίνει σε αυτό ασύρματα, μέσω **Bluetooth LE**.

```
                  ήχος (σταθερή ένταση)
TV ── οπτικό ──► DAC ── AUX 3.5mm ──► Edifier D32
 │                                        ▲
 │  Vol+/Vol− από τηλεχειριστήριο         │ BLE: "ένταση = 7"
 └────► D32 Remote (εφαρμογή στην TV) ────┘
```

## 2. Γιατί υπάρχει

| Πρόβλημα | Αιτία |
|---|---|
| Το D32 δεν έχει οπτική είσοδο ούτε HDMI ARC | Υλικό. Έχει μόνο Bluetooth, Wi-Fi/AirPlay, USB-C, AUX. |
| Με Bluetooth υπάρχει καθυστέρηση ήχου (lip-sync) | Η TCL απενεργοποιεί τη ρύθμιση audio delay σε Bluetooth. |
| Με οπτικό → DAC → AUX, το τηλεχειριστήριο δεν αλλάζει την ένταση | Η οπτική έξοδος είναι σταθερής έντασης. Η TCL δείχνει "Audio output through optical fibers does not support volume adjustment". |
| Η έξοδος USB προς το D32 δεν υποστηρίζεται | Η TCL δεν έχει έξοδο ήχου σε USB. |
| AirPlay / Apple Home / Siri δεν ελέγχουν την ένταση | Ελέγχουν μόνο όταν υπάρχει ενεργό AirPlay stream, όχι σε AUX. |

Η ενσύρματη σύνδεση (οπτικό → DAC → AUX) δίνει **ήχο χωρίς καθυστέρηση**. Η εφαρμογή λύνει το πρόβλημα της **έντασης**.

## 3. Πώς δουλεύει

### 3.1 Η TV δεν δίνει τα πλήκτρα έντασης σε εφαρμογές
Η TCL κρατάει τα Vol+/Vol− στο σύστημα (`TclVolumeControllerImpl`). Ούτε μια Accessibility υπηρεσία δεν τα βλέπει. Το επιβεβαιώσαμε με logcat: περνάει το BACK, όχι τα Vol.

### 3.2 Αλλά η TV αλλάζει την εσωτερική της ένταση
Ακόμα και σε οπτική έξοδο, όταν πατάς Vol+:
- η τιμή `STREAM_MUSIC` αλλάζει (0–100, π.χ. 27 → 32)
- στέλνεται το broadcast `android.media.VOLUME_CHANGED_ACTION`
- η πραγματική έξοδος S/PDIF **μένει στα 0 dB** (`volume_spdif_db=0`), άρα ο ήχος δεν επηρεάζεται

Η εφαρμογή ακούει αυτό το broadcast:
- τιμή ανέβηκε → **D32 +1 βήμα**
- τιμή κατέβηκε → **D32 −1 βήμα**
- Mute → σίγαση / επαναφορά στο D32
- όταν η εσωτερική τιμή φτάσει κοντά στο 0 ή στο 100, την ξαναφέρνει στο **50**, για να μην «κολλήσει» στα όρια

### 3.3 Η μπάρα έντασης στην οθόνη
Η TCL δείχνει το μήνυμα "optical fibers…" σε ένα toast του system UI. Η εφαρμογή ζωγραφίζει τη δική της μπάρα **ακριβώς από πάνω** σε παράθυρο `TYPE_ACCESSIBILITY_OVERLAY`. Είναι το μόνο επίπεδο που μπαίνει πάνω από τα toasts του συστήματος. Το κανονικό `APPLICATION_OVERLAY` το δοκιμάσαμε και έμενε από κάτω.

- Το παράθυρο φτιάχνεται μία φορά και μένει αόρατο, ώστε να εμφανίζεται αμέσως.
- Εμφανίζεται επίσης μόλις το Accessibility δει toast από το `com.android.systemui`, ώστε να προλαβαίνει το μήνυμα.
- Μένει ~3,7 δευτ. και μετά σβήνει με fade.
- Η κάθετη μπάρα της TCL δεξιά **δεν καλύπτεται** (επιλογή).

### 3.4 Η επικοινωνία με το ηχείο
Πλήρες πρωτόκολλο: [PROTOCOL.md](PROTOCOL.md). Σε συντομία:
- service `48098601-1a48-11e9-ab14-d663bd873d93`, write `48090002-…`, notify `48090001-…`
- πρώτα ενεργοποίηση notify και εντολή `D8`, αλλιώς οι εντολές αγνοούνται
- `AA EC 67 00 01 <vol> <checksum>`: ορισμός έντασης **0–16**
- το ηχείο στέλνει `0x66` όταν αλλάζει η ένταση, ακόμα και από τα δικά του κουμπιά

Το D32 δέχεται **έναν BLE client τη φορά**. Όσο τρέχει η εφαρμογή, το **EDIFIER ConneX** στο κινητό δεν θα συνδέεται.

## 4. Δομή κώδικα

```
app/src/main/java/gr/george/d32remote/
  D32.kt               BLE client: scan, σύνδεση, ουρά εντολών, ένταση, παύση (enabled)
  TvVolumeWatcher.kt   ακούει VOLUME_CHANGED / MUTE_CHANGED της TV → D32 ±1
  VolumeOverlay.kt     η μπάρα έντασης (custom View σε accessibility overlay)
  VolumeKeyService.kt  Accessibility service: κρατάει το overlay, ξαναξεκινά την υπηρεσία
  D32Service.kt        foreground service που κρατάει όλα τα παραπάνω ζωντανά
  BootReceiver.kt      εκκίνηση με το άναμμα της TV
  MainActivity.kt      οθόνη κατάστασης και κουμπί Παύσης
app/src/main/res/xml/accessibility.xml   ρύθμιση Accessibility
scripts/install.command    build release + εγκατάσταση + ρύθμιση μέσω adb
scripts/uninstall.command  αφαίρεση
scripts/diagnose.command   logs αν σταματήσει (crash / kill)
tools/d32_probe.py         εργαλείο BLE για Mac (bleak)
esp32/                     εναλλακτική: γέφυρα ESP32 → Apple HomeKit (δεν χρησιμοποιείται)
```

Χωρίς εξωτερικές βιβλιοθήκες, μόνο Android SDK.

## 5. Εγκατάσταση από την αρχή

**Υλικό / καλώδια:** οπτικό Toslink από την TV → DAC (π.χ. Vention BDFB0) → AUX 3.5 mm → D32. Το D32 σε **AUX (πράσινο LED)**.

**Ρυθμίσεις TV:**
- Settings → Display & Sound → Audio output → **Optical**
- Digital audio out → **PCM**

**Εφαρμογή:**
1. Στην TV: Settings → System → About → 7 πατήματα στο *Android TV OS build* → Developer options → **Wireless debugging ON**.
2. Mac (μία φορά): `adb pair 192.168.1.6:<θύρα>` με τον κωδικό της TV.
3. `./scripts/install.command`. Αν ζητήσει, Accessibility → D32 Remote → OFF/ON.
4. Developer options → OFF.

## 6. Χρήση

- **Vol+ / Vol− / Mute** στο τηλεχειριστήριο: ρυθμίζουν το D32.
- **Παύση:** άνοιξε το *D32 Remote* στην TV → **⏸ Παύση**. Με **▶ Ενεργοποίηση** ξαναρχίζει.
- **Αφαίρεση:** Settings → Apps → D32 Remote → Uninstall, ή `./scripts/uninstall.command`.
- Τα κουμπιά − / + του ίδιου του ηχείου δουλεύουν κανονικά, και η εφαρμογή ενημερώνεται.

## 7. Προβλήματα & λύσεις

| Σύμπτωμα | Τι να κάνεις |
|---|---|
| Το τηλεχειριστήριο δεν αλλάζει την ένταση | Άνοιξε το D32 Remote. Αν δείχνει *Αποσυνδέθηκε*: ηχείο αναμμένο; ConneX κλειστό; |
| Accessibility ❌ | Settings → System → Accessibility → D32 Remote → ON |
| Μετά από ενημέρωση της εφαρμογής δεν δουλεύει | Το Android αποσυνδέει το Accessibility σε κάθε επανεγκατάσταση. Κάνε OFF/ON (το `install.command` το προσπαθεί αυτόματα). |
| Φαίνεται το μήνυμα "optical fibers…" | Το Accessibility δεν είναι ενεργό (δες παραπάνω). |
| Σταμάτησε ξαφνικά | Wireless debugging ON → `./scripts/diagnose.command` → δες `diagnose.log`. **Πριν** κάνεις επανεκκίνηση της TV. |
| Το ConneX δεν συνδέεται | Αναμενόμενο. Βάλε την εφαρμογή σε Παύση, ή κλείσε την από Settings → Apps → Force stop. |

## 8. Ενημέρωση της εφαρμογής (development)

1. Αλλαγές στον κώδικα (Android Studio ή editor).
2. TV: Wireless debugging ON.
3. `./scripts/install.command`: κάνει build release και εγκατάσταση.
4. `git commit` / `git push`.

Χρήσιμες εντολές:
```
adb logcat -b crash -d                         # crashes
adb shell dumpsys accessibility | grep Bound   # είναι δεμένο το Accessibility;
adb shell dumpsys activity exit-info gr.george.d32remote   # γιατί σταμάτησε
```

## 9. Ιστορικό αποφάσεων

| Λύση | Αποτέλεσμα |
|---|---|
| Bluetooth από TV | ✗ καθυστέρηση, χωρίς ρύθμιση delay |
| AirPlay / Apple Home / Siri | ✗ ελέγχει μόνο ενεργό AirPlay stream |
| USB-C από TV | ✗ η TCL δεν βγάζει ήχο σε USB |
| HDMI ARC extractor με CEC (π.χ. Marmitek ARC13) | εναλλακτική (~80–100€), δεν δοκιμάστηκε |
| ESP32 + HomeKit | δουλεύει στη θεωρία, δεν χρειάστηκε (`esp32/`) |
| Accessibility πιάνει Vol πλήκτρα | ✗ η TCL δεν τα δίνει |
| Παρακολούθηση `VOLUME_CHANGED_ACTION` | ✓ **η τρέχουσα λύση** |
| HTTP API για iPhone | αφαιρέθηκε (πιθανό crash από πολλά requests) |
| Overlay `APPLICATION_OVERLAY` | ✗ κάτω από το toast της TCL |
| Overlay `ACCESSIBILITY_OVERLAY` | ✓ καλύπτει το μήνυμα |

## 10. Περιορισμοί

- Σχεδιασμένο και δοκιμασμένο **μόνο σε TCL 65P8K**. Η θέση της μπάρας είναι για 1080p UI της TCL.
- Βήμα έντασης D32: 16 επίπεδα. Ένα πάτημα στο τηλεχειριστήριο είναι ένα βήμα.
- Το ConneX δεν μπορεί να συνδεθεί ταυτόχρονα.
- Αν η TV αλλάξει firmware και συμπεριφορά στην ένταση, μπορεί να χρειαστεί προσαρμογή.
