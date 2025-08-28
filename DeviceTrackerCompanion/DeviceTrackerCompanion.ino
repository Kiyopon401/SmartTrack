#define TINY_GSM_MODEM_SIM800
#include <TinyGsmClient.h>
#include <ArduinoHttpClient.h>
#include <TinyGPSPlus.h>
#include <HardwareSerial.h>
#include <time.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>

// =========================
// ====== CONFIG AREA ======
// =========================
// WiFi configuration (primary internet connection)
#define WIFI_SSID        "AI4666666646"
#define WIFI_PASSWORD    "12346GU46"
#define WIFI_TIMEOUT_MS  20000  // 20 seconds timeout

// Feature flags and SIM identity overrides
// - USE_SIM800L: enable cellular/GPRS fallback and modem features
// - READ_SIM_ON_WIFI: allow touching the modem to read SIM identity even when on WiFi
// - SIM_*_OVERRIDE: optional hardcoded identity values; if any is non-empty, modem reads are skipped
#define USE_SIM800L               false
#define READ_SIM_ON_WIFI          false
#define SIM_MSISDN_OVERRIDE       "+639497085461"           // e.g. "+1234567890"
#define SIM_ICCID_OVERRIDE        ""           // e.g. "8901XXXXXXXXXXXXXXX"
#define IMEI_OVERRIDE             "860389058785474"   

// Cellular APN configuration (fallback internet connection)
#define GSM_APN          "internet"
#define GSM_USER         ""
#define GSM_PASS         ""

// Firebase RTDB REST host (no protocol, no trailing slash)
// Example: "your-project-id-default-rtdb.asia-southeast1.firebasedatabase.app"
#define DATABASE_HOST    "smarttrackbackup-d19e8-default-rtdb.asia-southeast1.firebasedatabase.app"

// Optional database secret or auth token. If empty, no auth query is appended.
// Prefer using a scoped custom token or rules restricted to 
//  /devices/DEVICE_ID and /vehicles/DEVICE_ID paths for this device.
#define DATABASE_AUTH    ""  // e.g., "YOUR_DB_SECRET_OR_ID_TOKEN"

// Device identity (unique in your RTDB "devices")
#define DEVICE_ID        "tracker_01"

// GPS serial pins for GPS6MV2 (adjust to match your GPS module)
#define GPS_RX_PIN       15   // GPS TX -> ESP32 RX (GPIO15) - Serial2
#define GPS_TX_PIN       2    // GPS RX -> ESP32 TX (GPIO2) - Serial2 - Note: GPIO2 has built-in LED
#define GPS_BAUD         9600

// WARNING: GPIO 2 has built-in LED and can cause GPS communication issues
// If GPS doesn't work, change GPS_TX_PIN to 18 or 19

// SIM800L UART on ESP32 (adjust pins/Serial port to your wiring)
#define MODEM_RX_PIN     16   // SIM800 TX -> ESP32 RX (GPIO16) - Serial1
#define MODEM_TX_PIN     17   // SIM800 RX -> ESP32 TX (GPIO17) - Serial1
#define MODEM_BAUD       9600

// IO pins
#define RELAY_PIN        4
#define BUZZER_PIN       5

// Timings
static const unsigned long TRACK_INTERVAL_MS = 10UL * 1000UL;     // 10s continuous tracking interval
static const unsigned long HEARTBEAT_INTERVAL_MS = 10UL * 1000UL; // heartbeat frequency
static const unsigned long CMD_POLL_INTERVAL_MS = 3UL * 1000UL;   // poll commands every 3s

// =========================
// ====== GLOBALS ==========
// =========================
// GPS on Serial2 (GPIO 15, 2), SIM800L on Serial1 (GPIO 16, 17)
HardwareSerial GPSSerial(2);  // GPS6MV2: RX=GPIO15, TX=GPIO2
HardwareSerial ModemSerial(1); // SIM800L: RX=GPIO16, TX=GPIO17
TinyGPSPlus gps;
TinyGsm modem(ModemSerial);

// Network clients for WiFi and Cellular
WiFiClientSecure wifiClient;
TinyGsmClientSecure gsmClient(modem);
Client* netClient = nullptr;  // Will point to active client
HttpClient* http = nullptr;   // Will be initialized with active client

// Network status
bool wifiConnected = false;
bool gsmConnected = false;
bool netReady = false;
bool isTrackingContinuous = false;
bool simPublished = false;

String pairedVehicleId = "";  // loaded via PAIR command
String simMsisdn = "";         // SIM phone number (if available)
String simIccid  = "";         // SIM ICCID
String deviceImei = "";        // Modem IMEI

struct Geofence {
  bool enabled = false;
  double centerLat = 0.0;
  double centerLng = 0.0;
  double radiusMeters = 0.0;
  bool lastOutside = false;  // to detect transitions
} geofence;

unsigned long lastTrackPublishMs = 0;
unsigned long lastHeartbeatMs = 0;
unsigned long lastCmdPollMs = 0;
unsigned long lastSimPublishMs = 0;

// =========================
// ====== HELPERS ==========
// =========================
static double degreesToRadians(double deg) { return deg * 3.14159265358979323846 / 180.0; }

static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
  // Haversine
  const double R = 6371000.0; // meters
  double dLat = degreesToRadians(lat2 - lat1);
  double dLon = degreesToRadians(lon2 - lon1);
  double a = sin(dLat / 2) * sin(dLat / 2) +
             cos(degreesToRadians(lat1)) * cos(degreesToRadians(lat2)) *
             sin(dLon / 2) * sin(dLon / 2);
  double c = 2 * atan2(sqrt(a), sqrt(1 - a));
  return R * c;
}

static bool hasSimOverride() {
  return strlen(SIM_MSISDN_OVERRIDE) > 0 || strlen(SIM_ICCID_OVERRIDE) > 0 || strlen(IMEI_OVERRIDE) > 0;
}

String qAuth() {
  if (String(DATABASE_AUTH).length() == 0) return String("");
  return String("?auth=") + DATABASE_AUTH;
}

String pathVehicleRoot() { return String("/vehicles/") + DEVICE_ID; }
String pathDeviceRoot()  { return String("/devices/") + DEVICE_ID; }
String pathCommands()    { return String("/commands/") + DEVICE_ID; }

void toneBuzzer(unsigned long ms) {
  digitalWrite(BUZZER_PIN, HIGH);
  delay(ms);
  digitalWrite(BUZZER_PIN, LOW);
}

void setImmobilized(bool enable) {
  digitalWrite(RELAY_PIN, enable ? HIGH : LOW);
}

bool httpPutJson(const String &pathJson, const String &json) {
  if (!http) return false;
  String fullPath = pathJson + qAuth();
  Serial.printf("HTTP PUT https://%s%s\n", (const char*)DATABASE_HOST, fullPath.c_str());
  http->beginRequest();
  http->put(fullPath.c_str());
  http->sendHeader("Connection", "close");
  http->sendHeader("Content-Type", "application/json");
  http->sendHeader("Content-Length", json.length());
  http->beginBody();
  http->print(json);
  http->endRequest();
  // Try to read a quick response, but don't block long (avoid WDT resets)
  unsigned long t0 = millis();
  int status = -1;
  while (millis() - t0 < 1200) {
    if (netClient && netClient->available()) {
      status = http->responseStatusCode();
      break;
    }
    delay(10);
  }
  if (status == -1) {
    Serial.println(F("-> No immediate response (fire-and-forget)."));
    http->stop();
    return true; // assume success; Firebase typically processes the PUT
  } else {
    String body = http->responseBody();
    Serial.printf("-> Status: %d, Body: %s\n", status, body.c_str());
    http->stop();
    return status >= 200 && status < 300;
  }
}

bool httpGet(const String &pathJson, String &outBody) {
  if (!http) return false;
  String fullPath = pathJson + qAuth();
  Serial.printf("HTTP GET https://%s%s\n", (const char*)DATABASE_HOST, fullPath.c_str());
  http->beginRequest();
  http->get(fullPath.c_str());
  http->sendHeader("Connection", "close");
  http->endRequest();
  int status = http->responseStatusCode();
  outBody = http->responseBody();
  Serial.printf("-> Status: %d, Body: %s\n", status, outBody.c_str());
  http->stop();
  return status >= 200 && status < 300;
}

bool httpDelete(const String &pathJson) {
  if (!http) return false;
  String fullPath = pathJson + qAuth();
  Serial.printf("HTTP DELETE https://%s%s\n", (const char*)DATABASE_HOST, fullPath.c_str());
  http->beginRequest();
  http->del(fullPath.c_str());
  http->sendHeader("Connection", "close");
  http->endRequest();
  int status = http->responseStatusCode();
  String body = http->responseBody();
  Serial.printf("-> Status: %d, Body: %s\n", status, body.c_str());
  http->stop();
  return status >= 200 && status < 300;
}

void publishHeartbeat() {
  if (!netReady) return;
  time_t nowEpoch = time(nullptr);
  long lastSeen = (nowEpoch > 100000) ? (long)nowEpoch * 1000L : (long)millis();

  String json = String("{\"active\":true,\"deviceType\":\"companion\",\"last_seen\":") + lastSeen + "}";
  httpPutJson(pathDeviceRoot() + ".json", json);
}

void publishSimIdentity() {
  if (!netReady) return;
  Serial.println(F("Publishing SIM identity to Firebase:"));
  Serial.print(F("  Phone Number: ")); Serial.println(simMsisdn);
  Serial.print(F("  ICCID: ")); Serial.println(simIccid);
  Serial.print(F("  IMEI: ")); Serial.println(deviceImei);
  String json = String("{") +
                "\"simMsisdn\":\"" + simMsisdn + "\"," +
                "\"simIccid\":\"" + simIccid + "\"," +
                "\"imei\":\"" + deviceImei + "\"" +
                "}";
  Serial.print(F("  JSON: ")); Serial.println(json);
  if (httpPutJson(pathDeviceRoot() + "/sim.json", json)) {
    Serial.println(F("SIM identity published successfully"));
    simPublished = true;
  } else {
    Serial.println(F("Failed to publish SIM identity"));
    simPublished = false;
  }
}

bool currentLocation(double &lat, double &lng) {
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  if (gps.location.isValid()) {
    lat = gps.location.lat();
    lng = gps.location.lng();
    return true;
  }
  return false;
}

void publishLocationOnce() {
  if (!netReady) return;
  double lat, lng;
  if (!currentLocation(lat, lng)) {
    Serial.println(F("GPS location not ready"));
    return;
  }
  time_t nowEpoch = time(nullptr);
  long ts = (nowEpoch > 100000) ? (long)nowEpoch * 1000L : (long)millis();

  String json = String("{\"latitude\":") + String(lat, 6) + 
                ",\"longitude\":" + String(lng, 6) +
                ",\"timestamp\":" + ts + "}";
  if (httpPutJson(pathVehicleRoot() + "/location.json", json)) {
    Serial.println(F("Location published"));
  } else {
    Serial.println(F("Location publish failed"));
  }
}

void updateGeofenceStatus(double lat, double lng) {
  if (!netReady || !geofence.enabled) return;
  double d = distanceMeters(lat, lng, geofence.centerLat, geofence.centerLng);
  bool isOutside = d > geofence.radiusMeters;
  String status = isOutside ? "outside" : "inside";
  if (isOutside != geofence.lastOutside) {
    geofence.lastOutside = isOutside;
    httpPutJson(pathVehicleRoot() + "/geofence/status.json", String("\"") + status + "\"");
    Serial.printf("Geofence status updated: %s (%.2fm)\n", status.c_str(), d);
    if (isOutside) toneBuzzer(150);
  }
}

void clearCommand() {
  httpPutJson(pathCommands() + ".json", "\"\""); // write empty string
}

void readSimInfo();
void troubleshootModem();

void onCommandReceived(const String &cmdRaw) {
  String cmd = cmdRaw;
  cmd.trim();
  if (cmd.length() == 0 || cmd == "\"\"") return;
  if (cmd.startsWith("\"") && cmd.endsWith("\"")) {
    cmd.remove(0, 1);
    cmd.remove(cmd.length() - 1, 1);
  }

  Serial.printf("Command received: %s\n", cmd.c_str());

  if (cmd == "TRACK") {
    publishLocationOnce();
  } else if (cmd == "START_TRACKING") {
    isTrackingContinuous = true;
    Serial.println(F("Continuous tracking enabled"));
  } else if (cmd == "STOP") {
    isTrackingContinuous = false;
    Serial.println(F("Tracking stopped"));
  } else if (cmd == "IMMOBILIZE") {
    setImmobilized(true);
    Serial.println(F("Immobilizer engaged"));
  } else if (cmd == "UNLOCK") {
    setImmobilized(false);
    Serial.println(F("Immobilizer disengaged"));
  } else if (cmd.startsWith("PAIR:")) {
    pairedVehicleId = cmd.substring(5);
    pairedVehicleId.trim();
    Serial.printf("Paired vehicle id: %s\n", pairedVehicleId.c_str());
    String json = String("{\"pairedVehicleId\":\"") + pairedVehicleId + "\"}";
    httpPutJson(pathDeviceRoot() + ".json", json);
  } else if (cmd.startsWith("GEOFENCE_SET:")) {
    String args = cmd.substring(String("GEOFENCE_SET:").length());
    args.trim();
    int c1 = args.indexOf(',');
    int c2 = args.indexOf(',', c1 + 1);
    if (c1 > 0 && c2 > c1) {
      String sLat = args.substring(0, c1);
      String sLng = args.substring(c1 + 1, c2);
      String sRad = args.substring(c2 + 1);
      geofence.centerLat = sLat.toDouble();
      geofence.centerLng = sLng.toDouble();
      geofence.radiusMeters = sRad.toDouble();
      geofence.enabled = true;
      geofence.lastOutside = false;
      Serial.printf("Geofence set: lat=%.6f lng=%.6f r=%.2fm\n", geofence.centerLat, geofence.centerLng, geofence.radiusMeters);
      String json = String("{") +
                    "\"center\":{\"lat\":" + String(geofence.centerLat, 6) + ",\"lng\":" + String(geofence.centerLng, 6) + "}," +
                    "\"radius\":" + String(geofence.radiusMeters, 0) + "," +
                    "\"status\":\"inside\"" +
                    "}";
      httpPutJson(pathVehicleRoot() + "/geofence.json", json);
    } else {
      Serial.println(F("Invalid GEOFENCE_SET format"));
    }
  } else if (cmd == "GEOFENCE_CLEAR") {
    geofence.enabled = false;
    geofence.lastOutside = false;
    Serial.println(F("Geofence cleared"));
    httpDelete(pathVehicleRoot() + "/geofence.json");
  } else if (cmd.startsWith("GEOFENCE_RADIUS:")) {
    String sRad = cmd.substring(String("GEOFENCE_RADIUS:").length());
    sRad.trim();
    if (geofence.enabled) {
      geofence.radiusMeters = sRad.toDouble();
      Serial.printf("Geofence radius updated: %.2fm\n", geofence.radiusMeters);
    }
  } else if (cmd == "READ_SIM") {
    Serial.println(F("Manual SIM info read requested"));
    readSimInfo();
    publishSimIdentity();
  } else if (cmd == "TROUBLESHOOT") {
    Serial.println(F("Manual troubleshooting requested"));
    troubleshootModem();
    readSimInfo();
  } else if (cmd == "TEST_AT") {
    if (!USE_SIM800L) {
      Serial.println(F("TEST_AT skipped: USE_SIM800L=false"));
    } else {
      Serial.println(F("Manual AT test requested"));
      ModemSerial.begin(MODEM_BAUD, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
      delay(1000);
      Serial.println(F("Sending AT command..."));
      modem.sendAT("AT");
      String response = "";
      int result = modem.waitResponse(3000L, response);
      Serial.print(F("AT Response: ")); Serial.println(response);
      Serial.print(F("AT Result: ")); Serial.println(result);
    }
  } else {
    Serial.println(F("Unknown command"));
  }

  clearCommand();
}

void pollCommandsIfDue() {
  const unsigned long nowMs = millis();
  if (nowMs - lastCmdPollMs < CMD_POLL_INTERVAL_MS) return;
  lastCmdPollMs = nowMs;

  String body;
  if (httpGet(pathCommands() + ".json", body)) {
    if (body.length() > 0 && body != "null" && body != "\"\"") {
      onCommandReceived(body);
    }
  } else {
    Serial.println(F("Command poll failed"));
  }
}

void setupTime() {
  configTime(0, 0, "pool.ntp.org", "time.nist.gov");
}

// WiFi connection function
bool connectWiFi() {
  if (wifiConnected) return true;
  Serial.print(F("Connecting to WiFi: "));
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long startTime = millis();
  while (WiFi.status() != WL_CONNECTED && (millis() - startTime) < WIFI_TIMEOUT_MS) {
    delay(500);
    Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    wifiConnected = true;
    Serial.println();
    Serial.print(F("WiFi connected! IP: "));
    Serial.println(WiFi.localIP());
    wifiClient.setInsecure();
    wifiClient.setTimeout(15000);
    netClient = &wifiClient;
    http = new HttpClient(*netClient, DATABASE_HOST, 443);
    http->setHttpResponseTimeout(8000);
    return true;
  } else {
    Serial.println();
    Serial.println(F("WiFi connection failed"));
    return false;
  }
}

// Function to read SIM information
void readSimInfo() {
  if (hasSimOverride()) {
    simMsisdn = String(SIM_MSISDN_OVERRIDE);
    simIccid  = String(SIM_ICCID_OVERRIDE);
    deviceImei = String(IMEI_OVERRIDE);
    Serial.println(F("Using hardcoded SIM identity overrides"));
    return;
  }
  if (!USE_SIM800L) {
    Serial.println(F("SIM read skipped: USE_SIM800L=false"));
    return;
  }
  if (wifiConnected && !READ_SIM_ON_WIFI) {
    Serial.println(F("SIM read skipped on WiFi (READ_SIM_ON_WIFI=false)"));
    return;
  }

  Serial.println(F("Reading SIM information..."));
  Serial.println(F("Initializing modem for SIM reading..."));
  ModemSerial.begin(MODEM_BAUD, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
  delay(1000);

  Serial.println(F("Testing AT communication with different baud rates..."));
  int baudRates[] = {9600, 115200, 57600, 38400, 19200};
  bool atOk = false;
  for (int i = 0; i < 5; i++) {
    Serial.print(F("Trying baud rate: ")); Serial.println(baudRates[i]);
    ModemSerial.updateBaudRate(baudRates[i]);
    delay(500);
    modem.sendAT("AT");
    if (modem.waitResponse(2000L) == 1) {
      Serial.print(F("AT communication OK at baud rate: ")); Serial.println(baudRates[i]);
      atOk = true;
      break;
    }
  }
  if (!atOk) {
    Serial.println(F("AT communication failed at all baud rates"));
    Serial.println(F("Check wiring: TX->GPIO16, RX->GPIO17, power, and SIM card"));
    return;
  }

  Serial.println(F("Restarting modem..."));
  modem.restart();
  delay(3000);
  modem.sendAT("AT");
  if (modem.waitResponse(2000L) != 1) {
    Serial.println(F("Modem restart failed"));
    return;
  }
  Serial.println(F("Modem restart successful"));

  Serial.println(F("Waiting for SIM to be ready..."));
  delay(2000);

  modem.sendAT("+CPIN?");
  String simStatus = "";
  if (modem.waitResponse(3000L, simStatus) == 1) {
    Serial.print(F("SIM Status: ")); Serial.println(simStatus);
  } else {
    Serial.println(F("Could not check SIM status"));
  }

  Serial.println(F("Getting IMEI..."));
  deviceImei = modem.getIMEI();
  Serial.print(F("IMEI: ")); Serial.println(deviceImei);

  Serial.println(F("Getting ICCID..."));
  simIccid = modem.getSimCCID();
  Serial.print(F("ICCID: ")); Serial.println(simIccid);

  simMsisdn = "";
  Serial.println(F("Trying AT+CNUM..."));
  modem.sendAT("+CNUM");
  if (modem.waitResponse(3000L, simMsisdn) == 1) {
    Serial.print(F("AT+CNUM response: ")); Serial.println(simMsisdn);
    int p1 = simMsisdn.indexOf('"');
    if (p1 >= 0) {
      int p2 = simMsisdn.indexOf('"', p1 + 1);
      if (p2 > p1) {
        int p3 = simMsisdn.indexOf('"', p2 + 1);
        int p4 = simMsisdn.indexOf('"', p3 + 1);
        if (p3 >= 0 && p4 > p3) {
          String msisdn = simMsisdn.substring(p3 + 1, p4);
          msisdn.trim();
          if (msisdn.length() > 0 && msisdn != "" && msisdn != "\"\"") {
            simMsisdn = msisdn;
            Serial.print(F("Phone number found: ")); Serial.println(simMsisdn);
            return;
          }
        }
      }
    }
  }

  Serial.println(F("Trying AT+CPBR..."));
  modem.sendAT("+CPBR=1");
  if (modem.waitResponse(3000L, simMsisdn) == 1) {
    Serial.print(F("AT+CPBR response: ")); Serial.println(simMsisdn);
    int p1 = simMsisdn.indexOf('"');
    if (p1 >= 0) {
      int p2 = simMsisdn.indexOf('"', p1 + 1);
      if (p2 > p1) {
        String msisdn = simMsisdn.substring(p1 + 1, p2);
        msisdn.trim();
        if (msisdn.length() > 0 && msisdn != "" && msisdn != "\"\"") {
          simMsisdn = msisdn;
          Serial.print(F("Phone number found: ")); Serial.println(simMsisdn);
          return;
        }
      }
    }
  }

  Serial.println(F("Trying AT+COPS..."));
  modem.sendAT("+COPS?");
  if (modem.waitResponse(3000L, simMsisdn) == 1) {
    Serial.print(F("AT+COPS response: ")); Serial.println(simMsisdn);
  }

  Serial.println(F("Trying AT+CLIR..."));
  modem.sendAT("+CLIR?");
  if (modem.waitResponse(3000L, simMsisdn) == 1) {
    Serial.print(F("AT+CLIR response: ")); Serial.println(simMsisdn);
  }

  if (simMsisdn == "" || simMsisdn == "\"\"") {
    simMsisdn = "";
    Serial.println(F("Could not retrieve phone number"));
  }
  Serial.println(F("SIM information reading complete"));
}

// Cellular connection function
bool connectCellular() {
  if (!USE_SIM800L) return false;
  if (gsmConnected) return true;
  Serial.println(F("Bringing up cellular modem..."));
  ModemSerial.begin(MODEM_BAUD, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
  delay(600);
  modem.restart();
  Serial.print(F("Connecting to APN: "));
  Serial.println(GSM_APN);
  if (!modem.gprsConnect(GSM_APN, GSM_USER, GSM_PASS)) {
    Serial.println(F("GPRS connect failed"));
    return false;
  }
  gsmConnected = true;
  Serial.println(F("GPRS connected and TLS client ready"));
  netClient = &gsmClient;
  http = new HttpClient(*netClient, DATABASE_HOST, 443);
  http->setHttpResponseTimeout(8000);
  return true;
}

void ensureNetwork() {
  if (netReady) return;
  if (connectWiFi()) {
    netReady = true;
    Serial.println(F("Network ready via WiFi"));
    if (READ_SIM_ON_WIFI || hasSimOverride()) {
      readSimInfo();
    }
    return;
  }
  if (USE_SIM800L && connectCellular()) {
    netReady = true;
    Serial.println(F("Network ready via Cellular"));
    readSimInfo();
    return;
  }
  Serial.println(F("Both WiFi and Cellular failed"));
}

// Check network status and reconnect if needed
void checkNetworkStatus() {
  if (!netReady) return;
  if (wifiConnected && WiFi.status() != WL_CONNECTED) {
    Serial.println(F("WiFi connection lost, trying to reconnect..."));
    wifiConnected = false;
    netReady = false;
    ensureNetwork();
  } else if (gsmConnected && USE_SIM800L && !modem.isGprsConnected()) {
    Serial.println(F("Cellular connection lost, trying to reconnect..."));
    gsmConnected = false;
    netReady = false;
    ensureNetwork();
  }
}

// Hardware troubleshooting function
void troubleshootModem() {
  Serial.println(F("=== MODEM TROUBLESHOOTING ==="));
  Serial.println(F("1. Check power: SIM800L needs 3.7V-4.2V (not 3.3V)"));
  Serial.println(F("2. Check wiring: TX->GPIO16, RX->GPIO17"));
  Serial.println(F("3. Check SIM card: Inserted properly, not locked"));
  Serial.println(F("4. Check antenna: Connected to SIM800L"));
  Serial.println(F("5. Check power LED: Should be steady on"));
  Serial.println(F("6. Check network LED: Should blink every 3 seconds"));
  Serial.println(F("7. Try power cycling: Unplug/replug power"));
  Serial.println(F("================================="));
}

void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(RELAY_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  setImmobilized(false);
  digitalWrite(BUZZER_PIN, LOW);
  GPSSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  setupTime();
  ensureNetwork();
  delay(1000);
  if (netReady) {
    // Probe Firebase connectivity: get current device node
    String probe;
    bool ok = httpGet(pathDeviceRoot() + ".json", probe);
    Serial.printf("Connectivity probe %s.\n", ok ? "OK" : "FAILED");
    if (!ok) {
      Serial.println(F("If unauthorized, set DATABASE_AUTH or relax RTDB rules for testing."));
    }
    publishHeartbeat();
    publishSimIdentity();
  }
}

void loop() {
  ensureNetwork();
  checkNetworkStatus();
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  const unsigned long nowMs = millis();
  if (nowMs - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeatMs = nowMs;
    publishHeartbeat();
  }
  pollCommandsIfDue();
  if (isTrackingContinuous && (nowMs - lastTrackPublishMs >= TRACK_INTERVAL_MS)) {
    lastTrackPublishMs = nowMs;
    publishLocationOnce();
  }
  if (gps.location.isUpdated()) {
    double lat = gps.location.lat();
    double lng = gps.location.lng();
    updateGeofenceStatus(lat, lng);
  }
  // Retry SIM publish every 15s until it succeeds
  if (netReady && !simPublished && (nowMs - lastSimPublishMs >= 15000)) {
    lastSimPublishMs = nowMs;
    publishSimIdentity();
  }
  delay(20);
}

// END OF FILE

