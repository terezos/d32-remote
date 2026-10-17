/*
 * Edifier D32 -> Apple HomeKit + HTTP volume bridge (ESP32)
 *
 * Επιβεβαιωμένο πρωτόκολλο (live test σε D32):
 *   service 48098601-1a48-11e9-ab14-d663bd873d93
 *   notify  48090001-...  (speaker -> host)
 *   write   48090002-...  (host -> speaker, write-without-response)
 *   frame:  AA EC <CMD> <LEN_HI> <LEN_LO> <PAYLOAD> <SUM & 0xFF>
 *   init:   enable notify, μετά D8 (αλλιώς οι εντολές αγνοούνται)
 *   set vol: 0x67 payload <vol>     get vol: 0x66 -> reply BB EC 66 00 02 <max> <vol>
 *   max volume D32 = 16
 *
 * HomeKit: εμφανίζεται ως "λάμπα" -> Brightness 0-100% = ένταση 0-16, Off = ένταση 0.
 * HTTP (port 8080):  /vol/up  /vol/down  /vol/set?v=0..16  /mute  /status
 *
 * Libraries: HomeSpan, NimBLE-Arduino (2.x)
 * Board: ESP32 Dev Module (ή S3/C3 — ΟΧΙ S2).  Partition: "Huge APP (3MB No OTA)"
 */

#include <HomeSpan.h>
#include <NimBLEDevice.h>
#include <WebServer.h>

// ---------------- D32 protocol ----------------
static NimBLEUUID SVC_UUID   ("48098601-1a48-11e9-ab14-d663bd873d93");
static NimBLEUUID NOTIFY_UUID("48090001-1a48-11e9-ab14-d663bd873d93");
static NimBLEUUID WRITE_UUID ("48090002-1a48-11e9-ab14-d663bd873d93");
static const uint16_t ADV_SVC16  = 0x8600;   // advertised 16-bit service του D32
static const uint16_t EDIFIER_MFR = 0x07E0;

static uint8_t maxVol = 16;                  // ενημερώνεται από την απάντηση 0x66

// ---------------- state ----------------
static NimBLEClient*               client  = nullptr;
static NimBLERemoteCharacteristic* wChr    = nullptr;
static NimBLEAddress               d32Addr;
static bool                        haveAddr = false;

static volatile int  speakerVol   = -1;      // τελευταία ένταση που ανέφερε το ηχείο
static volatile bool volChanged   = false;   // flag για sync προς HomeKit
static uint8_t       lastNonZero  = 6;       // για unmute

WebServer http(8080);

// ---------------- packet helpers ----------------
static void sendCmd(uint8_t cmd, const uint8_t* payload = nullptr, uint16_t n = 0) {
  if (!wChr) return;
  uint8_t buf[32];
  buf[0] = 0xAA; buf[1] = 0xEC; buf[2] = cmd;
  buf[3] = (n >> 8) & 0xFF; buf[4] = n & 0xFF;
  for (uint16_t i = 0; i < n; i++) buf[5 + i] = payload[i];
  uint16_t len = 5 + n, sum = 0;
  for (uint16_t i = 0; i < len; i++) sum += buf[i];
  buf[len] = sum & 0xFF;
  wChr->writeValue(buf, len + 1, false);
}

static void onNotify(NimBLERemoteCharacteristic*, uint8_t* d, size_t len, bool) {
  if (len < 6 || d[0] != 0xBB || d[1] != 0xEC) return;
  uint8_t  cmd = d[2];
  uint16_t n   = (d[3] << 8) | d[4];
  // 0x66 = volume status (και push όταν πατάς τα κουμπιά του ηχείου), 0x67 = ack του set
  if ((cmd == 0x66 || cmd == 0x67) && n >= 2 && len >= 7) {
    maxVol     = d[5] ? d[5] : maxVol;
    speakerVol = d[6];
    if (d[6] > 0) lastNonZero = d[6];
    volChanged = true;
    Serial.printf("[D32] volume %d/%d\n", d[6], maxVol);
  }
}

class ClientCB : public NimBLEClientCallbacks {
  void onDisconnect(NimBLEClient*, int reason) override {
    Serial.printf("[BLE] disconnected (%d)\n", reason);
    wChr = nullptr;
  }
};

// ---------------- connection ----------------
static bool findD32() {
  NimBLEScan* scan = NimBLEDevice::getScan();
  scan->setActiveScan(true);
  NimBLEScanResults res = scan->getResults(6000, false);
  for (int i = 0; i < res.getCount(); i++) {
    const NimBLEAdvertisedDevice* d = res.getDevice(i);
    bool svc = d->isAdvertisingService(NimBLEUUID(ADV_SVC16));
    bool mfr = false;
    if (d->haveManufacturerData()) {
      std::string m = d->getManufacturerData();
      mfr = m.size() >= 2 && ((uint8_t)m[0] | ((uint8_t)m[1] << 8)) == EDIFIER_MFR;
    }
    if (svc && mfr) {
      d32Addr  = d->getAddress();
      haveAddr = true;
      Serial.printf("[BLE] D32 at %s\n", d32Addr.toString().c_str());
      return true;
    }
  }
  return false;
}

static bool connectD32() {
  if (client && client->isConnected() && wChr) return true;
  if (!haveAddr && !findD32()) { Serial.println("[BLE] D32 not found"); return false; }

  if (!client) {
    client = NimBLEDevice::createClient();
    client->setClientCallbacks(new ClientCB(), true);
    client->setConnectTimeout(5000);
  }
  if (!client->connect(d32Addr)) {
    Serial.println("[BLE] connect failed, will rescan");
    haveAddr = false;              // η διεύθυνση μπορεί να άλλαξε
    return false;
  }

  NimBLERemoteService* svc = client->getService(SVC_UUID);
  NimBLERemoteCharacteristic* nChr = svc ? svc->getCharacteristic(NOTIFY_UUID) : nullptr;
  wChr = svc ? svc->getCharacteristic(WRITE_UUID) : nullptr;
  if (!nChr || !wChr) { Serial.println("[BLE] GATT mismatch"); client->disconnect(); wChr = nullptr; return false; }

  nChr->subscribe(true, onNotify);   // 1. notify
  delay(100);
  sendCmd(0xD8);                     // 2. connect ritual
  delay(300);
  sendCmd(0x66);                     // 3. διάβασε τρέχουσα ένταση
  Serial.println("[BLE] connected to EDIFIER D32");
  return true;
}

static bool setVolume(int v) {
  v = constrain(v, 0, maxVol);
  if (!connectD32()) return false;
  uint8_t p = (uint8_t)v;
  sendCmd(0x67, &p, 1);
  if (v > 0) lastNonZero = v;
  return true;
}

// ---------------- HomeKit ----------------
struct D32Volume : Service::LightBulb {
  SpanCharacteristic* power;
  SpanCharacteristic* level;

  D32Volume() : Service::LightBulb() {
    power = new Characteristic::On(true);
    level = new Characteristic::Brightness(40);
    level->setRange(0, 100, 1);
  }

  boolean update() override {
    bool on  = power->getNewVal();
    int  pct = level->getNewVal();
    int  v   = on ? (int)round(pct * maxVol / 100.0) : 0;
    if (on && v == 0) v = 1;                       // on με 1% -> ελάχιστη ένταση, όχι σίγαση
    return setVolume(v);
  }

  void loop() override {                           // sync: κουμπιά ηχείου / HTTP -> Home app
    if (!volChanged) return;
    volChanged = false;
    int v = speakerVol;
    if (v < 0) return;
    int pct = (int)round(v * 100.0 / maxVol);
    if (v == 0) { if (power->getVal()) power->setVal(false); }
    else {
      if (!power->getVal()) power->setVal(true);
      if (level->getVal() != pct) level->setVal(pct);
    }
  }
};

// ---------------- HTTP ----------------
static void reply(bool ok) {
  http.send(ok ? 200 : 503, "application/json",
            String("{\"ok\":") + (ok ? "true" : "false") +
            ",\"volume\":" + speakerVol + ",\"max\":" + maxVol + "}");
}

static void setupHttp() {
  http.on("/vol/up",   [] { reply(setVolume((speakerVol < 0 ? lastNonZero : speakerVol) + 1)); });
  http.on("/vol/down", [] { reply(setVolume((speakerVol < 0 ? lastNonZero : speakerVol) - 1)); });
  http.on("/vol/set",  [] { reply(http.hasArg("v") && setVolume(http.arg("v").toInt())); });
  http.on("/mute",     [] { reply(setVolume(speakerVol > 0 ? 0 : lastNonZero)); });  // toggle
  http.on("/status",   [] { reply(client && client->isConnected()); });
  http.begin();
}

// ---------------- main ----------------
void setup() {
  Serial.begin(115200);
  NimBLEDevice::init("");

  homeSpan.setPairingCode("11122333");             // Home app: 111-22-333
  homeSpan.setWifiCallback(setupHttp);              // ξεκίνα τον HTTP server όταν συνδεθεί το Wi-Fi
  homeSpan.begin(Category::Lighting, "TV Volume");

  new SpanAccessory();
    new Service::AccessoryInformation();
      new Characteristic::Identify();
      new Characteristic::Name("TV Volume");
      new Characteristic::Manufacturer("Edifier");
      new Characteristic::Model("D32 bridge");
    new D32Volume();
}

void loop() {
  homeSpan.poll();
  http.handleClient();

  static uint32_t last = 0;                         // keep-alive / reconnect κάθε 15s
  if (millis() - last > 15000) {
    last = millis();
    if (!client || !client->isConnected()) connectD32();
  }
}
