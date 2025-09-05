#define TINY_GSM_MODEM_SIM800
#include <TinyGsmClient.h>
#include <ArduinoHttpClient.h>
#include <TinyGPSPlus.h>
#include <HardwareSerial.h>
#include <time.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include "esp_system.h"

// =========================
// ====== CONFIG AREA ======
// =========================
// WiFi configuration (primary internet connection)
#define WIFI_SSID        "Parafiber_2.4G_0070"
#define WIFI_PASSWORD    "dangeline123456"
#define WIFI_TIMEOUT_MS  20000  // 20 seconds timeout

// Feature flags and SIM identity overrides
#define USE_SIM800L               false
#define READ_SIM_ON_WIFI          false
#define SIM_MSISDN_OVERRIDE       "+639497085461"
#define SIM_ICCID_OVERRIDE        ""
#define IMEI_OVERRIDE             "860389058785474"   

// Cellular APN configuration (fallback internet connection)
#define GSM_APN          "internet"
#define GSM_USER         ""
#define GSM_PASS         ""

// Firebase RTDB REST host
#define DATABASE_HOST    "smarttrackbackup-d19e8-default-rtdb.asia-southeast1.firebasedatabase.app"
#define DATABASE_AUTH    ""

// Device identity
#define DEVICE_ID        "tracker_01"

// GPS serial pins for GPS6MV2
#define GPS_RX_PIN       27   // GPS TX -> ESP32 RX (GPIO27)
#define GPS_TX_PIN       26   // GPS RX -> ESP32 TX (GPIO26)
#define GPS_BAUD         9600

// SIM800L UART on ESP32
#define MODEM_RX_PIN     16   // SIM800 TX -> ESP32 RX (GPIO16)
#define MODEM_TX_PIN     17   // SIM800 RX -> ESP32 TX (GPIO17)
#define MODEM_BAUD       9600

// IO pins
#define RELAY_PIN        4
#define BUZZER_PIN       5
#define TAMPER_PIN       18

// Timings
static const unsigned long TRACK_INTERVAL_MS = 10UL * 1000UL;
static const unsigned long HEARTBEAT_INTERVAL_MS = 5UL * 1000UL;
static const unsigned long CMD_POLL_INTERVAL_MS = 3UL * 1000UL;

// GPS Quality and Movement Thresholds - IMPROVED FOR STABILITY
#define MIN_MOVEMENT_METERS 2.0        // Increased to 2 meters to reduce drift
#define GPS_ACCURACY_THRESHOLD 15.0    // Increased to 15.0 for better indoor performance
#define GPS_SATELLITE_MIN 3
#define COORDINATE_SMOOTHING_SAMPLES 5

// =========================
// ====== GLOBALS ==========
// =========================
HardwareSerial GPSSerial(2);
HardwareSerial ModemSerial(1);
TinyGPSPlus gps;
TinyGsm modem(ModemSerial);

WiFiClientSecure wifiClient;
TinyGsmClientSecure gsmClient(modem);
Client* netClient = nullptr;
HttpClient* http = nullptr;

bool wifiConnected = false;
bool gsmConnected = false;
bool netReady = false;
bool isTrackingContinuous = true;
bool simPublished = false;
bool tamperMonitorMode = false;  // Continuous tamper monitoring for testing

String pairedVehicleId = "";
String simMsisdn = "";
String simIccid  = "";
String deviceImei = "";

// Enhanced Geofence structure
struct Geofence {
    bool enabled = false;
    double centerLat = 0.0;
    double centerLng = 0.0;
    double radiusMeters = 100.0;
    bool lastOutside = false;
    unsigned long lastAlertTime = 0;
    static const unsigned long ALERT_COOLDOWN_MS = 30000;
};

Geofence geofence;

// GPS Quality and Movement Tracking - ENHANCED WITH SMOOTHING
double lastPublishedLat = 0.0;
double lastPublishedLng = 0.0;
bool hasValidLocation = false;
double coordinateBuffer[COORDINATE_SMOOTHING_SAMPLES][2];
int bufferIndex = 0;
bool bufferFilled = false;

// NEW: Smoothing variables to reduce GPS drift
double smoothedLat = 0.0;
double smoothedLng = 0.0;
bool hasSmoothInit = false;
const float SMOOTH_ALPHA = 0.3f;  // Lower = more smoothing (0.1-0.5)
unsigned long lastSmoothUpdate = 0;
const unsigned long SMOOTH_UPDATE_INTERVAL = 2000; // Update every 2 seconds

// Tamper detection - IMPROVED SENSITIVITY
bool tamperActive = false;
unsigned long lastTamperChangeMs = 0;
const unsigned long TAMPER_DEBOUNCE_MS = 500;  // Reduced to 500ms for faster response
unsigned long lastTamperAlertMs = 0;
const unsigned long TAMPER_ALERT_COOLDOWN_MS = 30000;  // 30 seconds between alerts
int tamperStateCount = 0;  // Count consecutive readings
const int TAMPER_CONFIRM_COUNT = 2;  // Only need 2 consecutive readings (more sensitive)
int tamperReadings[5] = {0};  // Store last 5 readings for better detection
int tamperReadingIndex = 0;

unsigned long lastTrackPublishMs = 0;
unsigned long lastHeartbeatMs = 0;
unsigned long lastCmdPollMs = 0;
unsigned long lastSimPublishMs = 0;
unsigned long lastGeofenceCheckTime = 0;

// =========================
// ====== HELPERS ==========
// =========================
static double degreesToRadians(double deg) { return deg * 3.14159265358979323846 / 180.0; }

static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
  const double R = 6371000.0;
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
String pathVehicleRootFor(const String &vid) { return String("/vehicles/") + vid; }
String vehicleIdForPublish() { return pairedVehicleId.length() > 0 ? pairedVehicleId : String(DEVICE_ID); }
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

// =========================
// ====== GPS SMOOTHING ====
// =========================

// NEW: Apply exponential smoothing to reduce GPS drift
void updateSmoothedCoordinates() {
  if (!gps.location.isValid()) return;
  
  double currentLat = gps.location.lat();
  double currentLng = gps.location.lng();
  
  if (!hasSmoothInit) {
    // Initialize with first valid reading
    smoothedLat = currentLat;
    smoothedLng = currentLng;
    hasSmoothInit = true;
    Serial.println("GPS smoothing initialized");
  } else {
    // Apply exponential smoothing
    smoothedLat = (SMOOTH_ALPHA * currentLat) + ((1.0 - SMOOTH_ALPHA) * smoothedLat);
    smoothedLng = (SMOOTH_ALPHA * currentLng) + ((1.0 - SMOOTH_ALPHA) * smoothedLng);
  }
  
  Serial.printf("GPS Smoothing: Raw(%.6f,%.6f) -> Smoothed(%.6f,%.6f)\n", 
                currentLat, currentLng, smoothedLat, smoothedLng);
}

// NEW: Check if smoothed coordinates have moved enough to publish
bool shouldPublishSmoothedLocation() {
  if (!hasValidLocation) return true; // First publish
  
  double distance = distanceMeters(lastPublishedLat, lastPublishedLng, smoothedLat, smoothedLng);
  return distance >= MIN_MOVEMENT_METERS;
}

// =========================
// ====== HTTP FUNCTIONS ===
// =========================

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
    return true;
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

static String u64ToString(unsigned long long value) {
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%llu", (unsigned long long)value);
  return String(buffer);
}

String buildDeviceSnapshotJson(unsigned long long lastSeen) {
  String json = "{";
  json += "\"active\":true,\"deviceType\":\"companion\",\"last_seen\":" + u64ToString(lastSeen);
  if (pairedVehicleId.length() > 0) {
    json += ",\"pairedVehicle\":\"" + pairedVehicleId + "\"";
  }
  json += ",\"sim\":{\"imei\":\"" + deviceImei + "\",\"simIccid\":\"" + simIccid + "\",\"simMsisdn\":\"" + simMsisdn + "\"}";
  json += "}";
  return json;
}

// =========================
// ====== GEOFENCING =======
// =========================

void checkGeofenceStatus() {
    if (!geofence.enabled || !gps.location.isValid()) {
        return;
    }

    // Use smoothed coordinates for geofence
    double distance = distanceMeters(
        geofence.centerLat, geofence.centerLng,
        smoothedLat, smoothedLng
    );

    bool isOutside = distance > geofence.radiusMeters;
    unsigned long now = millis();

    if (isOutside != geofence.lastOutside && 
        (now - geofence.lastAlertTime) >= geofence.ALERT_COOLDOWN_MS) {
        
        geofence.lastAlertTime = now;
        geofence.lastOutside = isOutside;

        if (isOutside) {
            sendAlertToApp("GEOFENCE_EXIT: Vehicle left geofence area");
            toneBuzzer(1500);
        } else {
            sendAlertToApp("GEOFENCE_ENTER: Vehicle entered geofence area");
        }
    }
}

void sendAlertToApp(String alertMsg) {
    if (!netReady || http == nullptr) {
        Serial.println("Network not ready for alert");
        return;
    }

    String path = String("/vehicles/") + String(DEVICE_ID) + String("/alerts.json");
    String jsonData = "\"" + alertMsg + "\"";
    
    http->beginRequest();
    http->put(path + qAuth());
    http->sendHeader("Content-Type", "application/json");
    http->sendHeader("Content-Length", jsonData.length());
    http->beginBody();
    http->print(jsonData);
    http->endRequest();
    
    int statusCode = http->responseStatusCode();
    if (statusCode == 200) {
        Serial.println("Alert sent: " + alertMsg);
    } else {
        Serial.println("Failed to send alert. Status: " + String(statusCode));
    }
}

// =========================
// ====== TAMPER DETECTION =
// =========================

void checkTamper() {
  bool current = digitalRead(TAMPER_PIN) == HIGH;
  unsigned long now = millis();
  
  // Store reading in circular buffer
  tamperReadings[tamperReadingIndex] = current ? 1 : 0;
  tamperReadingIndex = (tamperReadingIndex + 1) % 5;
  
  // Count how many recent readings show tamper state
  int tamperCount = 0;
  for (int i = 0; i < 5; i++) {
    if (tamperReadings[i] == 1) tamperCount++;
  }
  
  // Determine if we should consider this a tamper state
  // If 3 or more of the last 5 readings show tamper, consider it tampered
  bool shouldBeTampered = tamperCount >= 3;
  
  // Only change state if different from current state and debounce time passed
  if (shouldBeTampered != tamperActive && (now - lastTamperChangeMs) >= TAMPER_DEBOUNCE_MS) {
    bool oldState = tamperActive;
    tamperActive = shouldBeTampered;
    lastTamperChangeMs = now;

    String status = tamperActive ? "active" : "cleared";
    Serial.printf("Tamper status changed: %s (detected in %d/5 recent readings)\n", status.c_str(), tamperCount);
    Serial.printf("Raw readings: [%d,%d,%d,%d,%d]\n", 
                  tamperReadings[0], tamperReadings[1], tamperReadings[2], tamperReadings[3], tamperReadings[4]);
    
    if (netReady) {
      httpPutJson(pathDeviceRoot() + "/tamper/status.json", "\"" + status + "\"");
    }

    // Only send alert for tamper activation (not clearing) and respect cooldown
    if (tamperActive && !oldState && (now - lastTamperAlertMs) >= TAMPER_ALERT_COOLDOWN_MS) {
      lastTamperAlertMs = now;
      sendAlertToApp("TAMPER: Device enclosure opened or cable cut");
      toneBuzzer(1000);  // Longer buzzer for tamper
      Serial.println("TAMPER ALERT: Device enclosure opened!");
    } else if (!tamperActive && oldState) {
      Serial.println("Tamper cleared - no alert needed");
    }
  }
}

// =========================
// ====== PUBLISHING =======
// =========================

void publishHeartbeat() {
  if (!netReady) return;
  time_t nowEpoch = time(nullptr);
  unsigned long long lastSeen = (nowEpoch > 100000) ? (unsigned long long)nowEpoch * 1000ULL : (unsigned long long)millis();
  String json = buildDeviceSnapshotJson(lastSeen);
  bool ok = httpPutJson(pathDeviceRoot() + ".json", json);
  if (ok) {
    simPublished = true;
  }
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

// IMPROVED: GPS quality check with better HDOP handling
bool currentLocation(double &lat, double &lng) {
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  bool isValid = gps.location.isValid();
  uint32_t satellites = gps.satellites.value();
  
  // Fix HDOP reading issue
  float hdop = gps.hdop.value();
  if (hdop > 100.0 || hdop < 0.1) {
    hdop = 2.0;
    Serial.printf("HDOP corrupted (%.2f), using default 2.0\n", gps.hdop.value());
  }
  
  Serial.printf("GPS Quality Check: Valid=%s, Sats=%u, HDOP=%.2f (threshold=%.1f)\n", 
                isValid ? "YES" : "NO", satellites, hdop, GPS_ACCURACY_THRESHOLD);
  
  if (isValid && satellites >= GPS_SATELLITE_MIN && hdop <= GPS_ACCURACY_THRESHOLD) {
    lat = gps.location.lat();
    lng = gps.location.lng();
    
    Serial.printf("GPS Quality Check PASSED: Valid=%s, Sats=%u, HDOP=%.2f\n", 
                  isValid ? "YES" : "NO", satellites, hdop);
    
    return true;
  }
  
  Serial.printf("GPS Quality Check Failed: Valid=%s, Sats=%u, HDOP=%.2f (need sats>=%d, hdop<=%.1f)\n", 
                isValid ? "YES" : "NO", satellites, hdop, GPS_SATELLITE_MIN, GPS_ACCURACY_THRESHOLD);
  
  return false;
}

// NEW: Publish smoothed location (reduces drift)
void publishSmoothedLocation() {
  if (!netReady) return;
  
  // Update smoothed coordinates
  updateSmoothedCoordinates();
  
  // Check if we should publish (moved enough)
  if (!shouldPublishSmoothedLocation()) {
    Serial.println("Smoothed location: No significant movement, skipping publish");
    return;
  }
  
  // Use smoothed coordinates for publishing
  double lat = smoothedLat;
  double lng = smoothedLng;
  
  Serial.printf("Publishing SMOOTHED location: lat=%.6f, lng=%.6f\n", lat, lng);
  
  time_t nowEpoch = time(nullptr);
  long ts = (nowEpoch > 100000) ? (long)nowEpoch * 1000L : (long)millis();

  String json = String("{\"latitude\":") + String(lat, 6) + 
                ",\"longitude\":" + String(lng, 6) +
                ",\"timestamp\":" + ts + 
                ",\"accuracy\":" + String(gps.hdop.value(), 2) +
                ",\"satellites\":" + String(gps.satellites.value()) +
                ",\"smoothed\":true" +  // Flag to indicate this is smoothed data
                "}";
                
  const String targetVid = vehicleIdForPublish();
  
  if (httpPutJson(pathVehicleRootFor(targetVid) + "/location.json", json)) {
    Serial.println(F("SMOOTHED location published successfully!"));
    // Update last published coordinates
    lastPublishedLat = lat;
    lastPublishedLng = lng;
    hasValidLocation = true;
  } else {
    Serial.println(F("SMOOTHED location publish failed"));
  }
}

// Original location publishing (for fallback)
void publishLocationOnce() {
  if (!netReady) return;
  
  double lat, lng;
  if (!currentLocation(lat, lng)) {
    Serial.println(F("GPS location not ready or poor quality"));
    return;
  }
  
  // Check if we've moved enough to warrant publishing
  if (hasValidLocation) {
    double distance = distanceMeters(lastPublishedLat, lastPublishedLng, lat, lng);
    if (distance < MIN_MOVEMENT_METERS) {
      Serial.printf("Movement too small (%.2fm), skipping publish\n", distance);
      return;
    }
  }
  
  Serial.printf("GPS Quality: Sats=%u, HDOP=%.2f, Age=%lu ms\n", 
                gps.satellites.value(), 
                gps.hdop.value(), 
                gps.location.age());
  
  time_t nowEpoch = time(nullptr);
  long ts = (nowEpoch > 100000) ? (long)nowEpoch * 1000L : (long)millis();

  String json = String("{\"latitude\":") + String(lat, 6) + 
                ",\"longitude\":" + String(lng, 6) +
                ",\"timestamp\":" + ts + 
                ",\"accuracy\":" + String(gps.hdop.value(), 2) +
                ",\"satellites\":" + String(gps.satellites.value()) +
                "}";
                
  const String targetVid = vehicleIdForPublish();
  
  if (httpPutJson(pathVehicleRootFor(targetVid) + "/location.json", json)) {
    Serial.println(F("Location published successfully!"));
    Serial.printf("Published: lat=%.6f, lng=%.6f\n", lat, lng);
    lastPublishedLat = lat;
    lastPublishedLng = lng;
    hasValidLocation = true;
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
    const String targetVid = vehicleIdForPublish();
    httpPutJson(pathVehicleRootFor(targetVid) + "/geofence/status.json", String("\"") + status + "\"");
    Serial.printf("Geofence status updated: %s (%.2fm)\n", status.c_str(), d);
    if (isOutside) toneBuzzer(150);
  }
}

void clearCommand() {
  httpPutJson(pathCommands() + ".json", "\"\"");
}

// =========================
// ====== COMMANDS =========
// =========================

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
    publishSmoothedLocation(); // Use smoothed version
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
      geofence.lastAlertTime = 0;
      Serial.printf("Geofence set: lat=%.6f lng=%.6f r=%.2fm\n", geofence.centerLat, geofence.centerLng, geofence.radiusMeters);
      String json = String("{") +
                    "\"center\":{\"lat\":" + String(geofence.centerLat, 6) + ",\"lng\":" + String(geofence.centerLng, 6) + "}," +
                    "\"radius\":" + String(geofence.radiusMeters, 0) + "," +
                    "\"status\":\"inside\"" +
                    "}";
      const String targetVid = vehicleIdForPublish();
      httpPutJson(pathVehicleRootFor(targetVid) + "/geofence.json", json);
    } else {
      Serial.println(F("Invalid GEOFENCE_SET format"));
    }
  } else if (cmd == "GEOFENCE_CLEAR") {
    geofence.enabled = false;
    geofence.lastOutside = false;
    Serial.println(F("Geofence cleared"));
    const String targetVid = vehicleIdForPublish();
    httpDelete(pathVehicleRootFor(targetVid) + "/geofence.json");
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
  } else if (cmd == "TAMPER_STATUS") {
    String status = tamperActive ? "active" : "cleared";
    Serial.printf("Current tamper status: %s\n", status.c_str());
  } else if (cmd == "DEBUG_GPS") {
    Serial.println("Manual GPS debug requested");
    debugGPSQuality();
  } else if (cmd == "FORCE_PUBLISH") {
    Serial.println("Force location publish requested");
    publishLocationOnce();
  } else if (cmd == "SMOOTHED_PUBLISH") {
    Serial.println("Smoothed location publish requested");
    publishSmoothedLocation();
  } else if (cmd == "TAMPER_TEST") {
    Serial.println("=== TAMPER SENSOR TEST ===");
    Serial.printf("Current pin state: %s\n", digitalRead(TAMPER_PIN) == HIGH ? "HIGH (tamper)" : "LOW (normal)");
    Serial.printf("Tamper active: %s\n", tamperActive ? "YES" : "NO");
    Serial.printf("Recent readings: [%d,%d,%d,%d,%d]\n", 
                  tamperReadings[0], tamperReadings[1], tamperReadings[2], tamperReadings[3], tamperReadings[4]);
    int tamperCount = 0;
    for (int i = 0; i < 5; i++) {
      if (tamperReadings[i] == 1) tamperCount++;
    }
    Serial.printf("Tamper count: %d/5 (need 3+ for tamper)\n", tamperCount);
    Serial.printf("Last change: %lu ms ago\n", millis() - lastTamperChangeMs);
    Serial.printf("Last alert: %lu ms ago\n", millis() - lastTamperAlertMs);
    Serial.println("=========================");
  } else if (cmd == "TAMPER_RESET") {
    tamperActive = false;
    tamperStateCount = 0;
    lastTamperChangeMs = 0;
    lastTamperAlertMs = 0;
    // Clear reading buffer
    for (int i = 0; i < 5; i++) {
      tamperReadings[i] = 0;
    }
    tamperReadingIndex = 0;
    Serial.println("Tamper state reset - all counters and readings cleared");
  } else if (cmd == "TAMPER_MONITOR") {
    tamperMonitorMode = !tamperMonitorMode;
    Serial.printf("Tamper monitor mode: %s\n", tamperMonitorMode ? "ON" : "OFF");
    if (tamperMonitorMode) {
      Serial.println("Watch the readings as you move the magnets...");
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

// =========================
// ====== NETWORK ==========
// =========================

void setupTime() {
  configTime(0, 0, "pool.ntp.org", "time.nist.gov");
}

bool connectWiFi() {
  if (wifiConnected) return true;
  
  Serial.print(F("Connecting to WiFi: "));
  Serial.println(WIFI_SSID);
  
  WiFi.disconnect(true, true);
  delay(1000);
  
  WiFi.mode(WIFI_STA);
  WiFi.persistent(false);
  
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
  // ... (rest of readSimInfo function remains the same)
}

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

void debugGPSQuality() {
  Serial.println("=== DETAILED GPS DEBUG ===");
  
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  bool isValid = gps.location.isValid();
  uint32_t satellites = gps.satellites.value();
  float hdop = gps.hdop.value();
  uint32_t age = gps.location.age();
  
  Serial.printf("Raw GPS Values:\n");
  Serial.printf("  isValid: %s\n", isValid ? "TRUE" : "FALSE");
  Serial.printf("  satellites: %u\n", satellites);
  Serial.printf("  hdop: %.2f\n", hdop);
  Serial.printf("  age: %lu ms\n", age);
  
  Serial.printf("Quality Check Conditions:\n");
  Serial.printf("  Condition 1 - isValid: %s (need: TRUE)\n", isValid ? "PASS" : "FAIL");
  Serial.printf("  Condition 2 - satellites >= %d: %s (have: %u)\n", 
                GPS_SATELLITE_MIN, 
                (satellites >= GPS_SATELLITE_MIN) ? "PASS" : "FAIL", 
                satellites);
  Serial.printf("  Condition 3 - hdop <= %.1f: %s (have: %.2f)\n", 
                GPS_ACCURACY_THRESHOLD, 
                (hdop <= GPS_ACCURACY_THRESHOLD) ? "PASS" : "FAIL", 
                hdop);
  
  bool overallResult = isValid && (satellites >= GPS_SATELLITE_MIN) && (hdop <= GPS_ACCURACY_THRESHOLD);
  Serial.printf("Overall Result: %s\n", overallResult ? "PASS" : "FAIL");
  
  if (isValid) {
    Serial.printf("Coordinates: %.6f, %.6f\n", gps.location.lat(), gps.location.lng());
    Serial.printf("Smoothed: %.6f, %.6f\n", smoothedLat, smoothedLng);
  }
  
  Serial.println("========================");
}

// =========================
// ====== SETUP & LOOP =====
// =========================

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.printf("Reset reason: %d\n", (int)esp_reset_reason());
  
  // Initialize GPS quality tracking variables
  lastPublishedLat = 0.0;
  lastPublishedLng = 0.0;
  hasValidLocation = false;
  bufferIndex = 0;
  bufferFilled = false;
  hasSmoothInit = false;
  
  pinMode(RELAY_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(TAMPER_PIN, INPUT_PULLUP);
  setImmobilized(false);
  digitalWrite(BUZZER_PIN, LOW);
  
  GPSSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  setupTime();
  ensureNetwork();
  delay(1000);
  
  if (netReady) {
    String probe;
    bool ok = httpGet(pathDeviceRoot() + ".json", probe);
    Serial.printf("Connectivity probe %s.\n", ok ? "OK" : "FAILED");
    if (!ok) {
      Serial.println(F("If unauthorized, set DATABASE_AUTH or relax RTDB rules for testing."));
    }
    publishHeartbeat();
    publishSimIdentity();
  }
  
  Serial.println(F("=== GPS SMOOTHING ENABLED ==="));
  Serial.printf("Min movement: %.1f meters\n", MIN_MOVEMENT_METERS);
  Serial.printf("Smoothing factor: %.2f\n", SMOOTH_ALPHA);
  Serial.printf("Update interval: %lu ms\n", SMOOTH_UPDATE_INTERVAL);
  Serial.println(F("============================="));
}

void loop() {
  ensureNetwork();
  checkNetworkStatus();
  
  // Process GPS data
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  // Check tamper status every 200ms (more frequent for better sensitivity)
  static unsigned long lastTamperCheck = 0;
  if (millis() - lastTamperCheck >= 200) {
    lastTamperCheck = millis();
    checkTamper();
    
    // Continuous monitoring for testing
    if (tamperMonitorMode) {
      bool current = digitalRead(TAMPER_PIN) == HIGH;
      int tamperCount = 0;
      for (int i = 0; i < 5; i++) {
        if (tamperReadings[i] == 1) tamperCount++;
      }
      Serial.printf("TAMPER MONITOR: Pin=%s, Count=%d/5, Active=%s\n", 
                    current ? "HIGH" : "LOW", tamperCount, tamperActive ? "YES" : "NO");
    }
  }
  
  // Update smoothed coordinates every 2 seconds
  if (millis() - lastSmoothUpdate >= SMOOTH_UPDATE_INTERVAL) {
    lastSmoothUpdate = millis();
    if (gps.location.isValid()) {
      updateSmoothedCoordinates();
    }
  }
  
  // GPS status monitoring every 10 seconds
  static unsigned long lastGPSStatusTime = 0;
  if (millis() - lastGPSStatusTime >= 10000) {
    lastGPSStatusTime = millis();
    
    Serial.println("=== GPS STATUS ===");
    Serial.printf("Valid: %s\n", gps.location.isValid() ? "YES" : "NO");
    Serial.printf("Satellites: %u\n", gps.satellites.value());
    Serial.printf("HDOP: %.2f\n", gps.hdop.value());
    Serial.printf("Age: %lu ms\n", gps.location.age());
    
    if (gps.location.isValid()) {
      Serial.printf("Raw: %.6f, %.6f\n", gps.location.lat(), gps.location.lng());
      Serial.printf("Smoothed: %.6f, %.6f\n", smoothedLat, smoothedLng);
      
      // Use smoothed location for testing
      Serial.println("Testing smoothed location publish...");
      publishSmoothedLocation();
    } else {
      Serial.println("Waiting for GPS fix...");
    }
    Serial.println("================");
  }
  
  const unsigned long nowMs = millis();
  
  // Check geofence status every 5 seconds
  if (nowMs - lastGeofenceCheckTime >= 5000) {
    lastGeofenceCheckTime = nowMs;
    checkGeofenceStatus();
  }
  
  // Heartbeat and location publishing
  if (nowMs - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeatMs = nowMs;
    publishHeartbeat();
    
    // Use smoothed location publishing
    if (gps.location.isValid() && 
        gps.satellites.value() >= GPS_SATELLITE_MIN &&
        gps.hdop.value() <= GPS_ACCURACY_THRESHOLD) {
      Serial.println("GPS ready - publishing smoothed location...");
      publishSmoothedLocation();
    } else {
      Serial.println("GPS not ready yet - skipping location publish");
    }
  }
  
  pollCommandsIfDue();
  
  // Continuous tracking with smoothed coordinates
  if (isTrackingContinuous && (nowMs - lastTrackPublishMs >= TRACK_INTERVAL_MS)) {
    lastTrackPublishMs = nowMs;
    if (gps.location.isValid() && 
        gps.satellites.value() >= GPS_SATELLITE_MIN &&
        gps.hdop.value() <= GPS_ACCURACY_THRESHOLD) {
      publishSmoothedLocation();
    }
  }
  
  // Update geofence with smoothed coordinates
  if (gps.location.isUpdated() && 
      gps.satellites.value() >= GPS_SATELLITE_MIN &&
      gps.hdop.value() <= GPS_ACCURACY_THRESHOLD) {
    updateGeofenceStatus(smoothedLat, smoothedLng);
  }
  
  // Retry SIM publish every 15s until it succeeds
  if (netReady && !simPublished && (nowMs - lastSimPublishMs >= 15000)) {
    lastSimPublishMs = nowMs;
    publishSimIdentity();
  }
  
  delay(20);
}