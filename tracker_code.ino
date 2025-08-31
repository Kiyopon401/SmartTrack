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
#define GPS_RX_PIN       27   // GPS TX -> ESP32 RX (GPIO27) - safe pin
#define GPS_TX_PIN       26   // GPS RX -> ESP32 TX (GPIO26) - safe pin
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
static const unsigned long HEARTBEAT_INTERVAL_MS = 5UL * 1000UL; // heartbeat frequency
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
bool isTrackingContinuous = true;
bool simPublished = false;

String pairedVehicleId = "";  // Vehicle ID paired with this tracker

// Geofence structure for on-device geofencing
struct Geofence {
    bool enabled = false;
    double centerLat = 0.0;
    double centerLng = 0.0;
    double radiusMeters = 100.0;  // Default 100 meters
    bool lastOutside = false;     // Track previous state
    unsigned long lastAlertTime = 0;
    static const unsigned long ALERT_COOLDOWN_MS = 30000; // 30 seconds between alerts
} geofence;

// Timing variables
unsigned long lastTrackTime = 0;
unsigned long lastHeartbeatTime = 0;
unsigned long lastCmdPollTime = 0;
unsigned long lastGeofenceCheckTime = 0;

// =========================
// ====== FUNCTIONS ========
// =========================

// Calculate distance between two GPS coordinates in meters
double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
    const double R = 6371000; // Earth's radius in meters
    double dLat = radians(lat2 - lat1);
    double dLng = radians(lng2 - lng1);
    double a = sin(dLat/2) * sin(dLat/2) +
               cos(radians(lat1)) * cos(radians(lat2)) *
               sin(dLng/2) * sin(dLng/2);
    double c = 2 * atan2(sqrt(a), sqrt(1-a));
    return R * c;
}

// Check geofence status and send alerts if needed
void checkGeofenceStatus() {
    if (!geofence.enabled || !gps.location.isValid()) {
        return;
    }

    double distance = distanceMeters(
        geofence.centerLat, geofence.centerLng,
        gps.location.lat(), gps.location.lng()
    );

    bool isOutside = distance > geofence.radiusMeters;
    unsigned long now = millis();

    // Only send alert if state changed and cooldown has passed
    if (isOutside != geofence.lastOutside && 
        (now - geofence.lastAlertTime) > Geofence::ALERT_COOLDOWN_MS) {
        
        String alertMsg;
        if (isOutside) {
            alertMsg = "GEOFENCE_ALERT:OUTSIDE:" + String(distance, 0) + "m";
            // Optional: Activate buzzer for immediate feedback
            toneBuzzer(1500);
        } else {
            alertMsg = "GEOFENCE_ALERT:INSIDE:" + String(distance, 0) + "m";
        }

        sendAlertToApp(alertMsg);
        geofence.lastAlertTime = now;
        geofence.lastOutside = isOutside;
    }
}

// Send alert message to app via Firebase
void sendAlertToApp(String alertMsg) {
    if (!netReady || http == nullptr) {
        Serial.println("Network not ready for alert");
        return;
    }

    String path = "/vehicles/" + DEVICE_ID + "/alerts.json";
    String jsonData = "\"" + alertMsg + "\"";
    
    http->beginRequest();
    http->put(path);
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

// Process commands received from the app
void onCommandReceived(String command) {
    Serial.println("Received command: " + command);
    
    if (command.startsWith("GEOFENCE_SET:")) {
        // Format: GEOFENCE_SET:lat,lng,radius
        String params = command.substring(13);
        int firstComma = params.indexOf(',');
        int secondComma = params.indexOf(',', firstComma + 1);
        
        if (firstComma > 0 && secondComma > firstComma) {
            geofence.centerLat = params.substring(0, firstComma).toDouble();
            geofence.centerLng = params.substring(firstComma + 1, secondComma).toDouble();
            geofence.radiusMeters = params.substring(secondComma + 1).toDouble();
            geofence.enabled = true;
            geofence.lastOutside = false; // Reset state
            geofence.lastAlertTime = 0;   // Reset cooldown
            
            Serial.println("Geofence set: " + String(geofence.centerLat, 6) + 
                          ", " + String(geofence.centerLng, 6) + 
                          ", radius: " + String(geofence.radiusMeters) + "m");
        }
    }
    else if (command == "GEOFENCE_CLEAR") {
        geofence.enabled = false;
        Serial.println("Geofence cleared");
    }
    else if (command.startsWith("GEOFENCE_RADIUS:")) {
        // Format: GEOFENCE_RADIUS:radius
        String radiusStr = command.substring(16);
        geofence.radiusMeters = radiusStr.toDouble();
        Serial.println("Geofence radius updated: " + String(geofence.radiusMeters) + "m");
    }
    else if (command == "TRACK") {
        publishLocationOnce();
    }
    else if (command == "START_TRACKING") {
        isTrackingContinuous = true;
        Serial.println("Continuous tracking started");
    }
    else if (command == "STOP") {
        isTrackingContinuous = false;
        Serial.println("Tracking stopped");
    }
    else if (command == "IMMOBILIZE") {
        setImmobilized(true);
    }
    else if (command == "UNLOCK") {
        setImmobilized(false);
    }
    else if (command.startsWith("PAIR:")) {
        pairedVehicleId = command.substring(5);
        Serial.println("Paired with vehicle: " + pairedVehicleId);
    }
}

// Poll for commands from the app
void pollCommandsIfDue() {
    unsigned long now = millis();
    if (now - lastCmdPollTime >= CMD_POLL_INTERVAL_MS) {
        lastCmdPollTime = now;
        
        if (!netReady || http == nullptr) {
            return;
        }

        String path = "/commands/" + DEVICE_ID + ".json";
        http->beginRequest();
        http->get(path);
        http->endRequest();
        
        int statusCode = http->responseStatusCode();
        if (statusCode == 200) {
            String response = http->responseBody();
            if (response != "null" && response.length() > 2) {
                // Remove quotes from response
                String command = response.substring(1, response.length() - 1);
                onCommandReceived(command);
                
                // Clear the command after processing
                http->beginRequest();
                http->delete(path);
                http->endRequest();
            }
        }
    }
}

// Update geofence status (legacy function - now secondary to checkGeofenceStatus)
void updateGeofenceStatus() {
    if (!geofence.enabled || !gps.location.isValid()) {
        return;
    }

    double distance = distanceMeters(
        geofence.centerLat, geofence.centerLng,
        gps.location.lat(), gps.location.lng()
    );

    bool isOutside = distance > geofence.radiusMeters;
    
    // Update status in Firebase (for app display)
    String status = isOutside ? "outside" : "inside";
    String path = "/vehicles/" + DEVICE_ID + "/geofence_status.json";
    String jsonData = "{\"status\":\"" + status + "\",\"distance\":" + String(distance, 1) + "}";
    
    httpPutJson(path, jsonData);
}

// Helper function to make HTTP PUT requests with JSON
void httpPutJson(String path, String jsonData) {
    if (!netReady || http == nullptr) return;
    
    http->beginRequest();
    http->put(path);
    http->sendHeader("Content-Type", "application/json");
    http->sendHeader("Content-Length", jsonData.length());
    http->beginBody();
    http->print(jsonData);
    http->endRequest();
    
    int statusCode = http->responseStatusCode();
    if (statusCode != 200) {
        Serial.println("HTTP PUT failed: " + String(statusCode));
    }
}

// Helper function to make HTTP GET requests
String httpGet(String path) {
    if (!netReady || http == nullptr) return "";
    
    http->beginRequest();
    http->get(path);
    http->endRequest();
    
    int statusCode = http->responseStatusCode();
    if (statusCode == 200) {
        return http->responseBody();
    }
    return "";
}

// Helper function to make HTTP DELETE requests
void httpDelete(String path) {
    if (!netReady || http == nullptr) return;
    
    http->beginRequest();
    http->delete(path);
    http->endRequest();
    
    int statusCode = http->responseStatusCode();
    if (statusCode != 200) {
        Serial.println("HTTP DELETE failed: " + String(statusCode));
    }
}

// Publish heartbeat to Firebase
void publishHeartbeat() {
    if (!netReady || http == nullptr) return;
    
    String path = "/devices/" + DEVICE_ID + "/heartbeat.json";
    String jsonData = "{\"timestamp\":" + String(millis()) + "}";
    httpPutJson(path, jsonData);
}

// Publish SIM identity to Firebase
void publishSimIdentity() {
    if (!netReady || http == nullptr) return;
    
    String path = "/devices/" + DEVICE_ID + "/sim_info.json";
    String jsonData = "{\"msisdn\":\"" + String(SIM_MSISDN_OVERRIDE) + "\",\"iccid\":\"" + String(SIM_ICCID_OVERRIDE) + "\",\"imei\":\"" + String(IMEI_OVERRIDE) + "\"}";
    httpPutJson(path, jsonData);
}

// Publish current location to Firebase
void publishLocationOnce() {
    if (!netReady || http == nullptr || !gps.location.isValid()) return;
    
    String path = "/vehicles/" + DEVICE_ID + "/location.json";
    String jsonData = "{\"latitude\":" + String(gps.location.lat(), 6) + 
                      ",\"longitude\":" + String(gps.location.lng(), 6) + 
                      ",\"timestamp\":" + String(millis()) + 
                      ",\"altitude\":" + String(gps.altitude.meters()) + 
                      ",\"speed\":" + String(gps.speed.kmph()) + 
                      ",\"course\":" + String(gps.course.deg()) + "}";
    httpPutJson(path, jsonData);
}

// Set immobilization status
void setImmobilized(bool immobilized) {
    digitalWrite(RELAY_PIN, immobilized ? HIGH : LOW);
    
    // Publish status to Firebase
    String path = "/vehicles/" + DEVICE_ID + "/immobilized.json";
    String jsonData = "{\"status\":" + String(immobilized ? "true" : "false") + "}";
    httpPutJson(path, jsonData);
    
    Serial.println(immobilized ? "Vehicle immobilized" : "Vehicle unlocked");
}

// Activate buzzer
void toneBuzzer(int frequency) {
    tone(BUZZER_PIN, frequency, 1000); // 1 second beep
}

// Setup time synchronization
void setupTime() {
    configTime(8 * 3600, 0, "pool.ntp.org", "time.nist.gov");
    Serial.println("Waiting for NTP time sync...");
    time_t now = 0;
    int retries = 0;
    while (now < 24 * 3600 && retries < 10) {
        Serial.print(".");
        delay(500);
        now = time(nullptr);
        retries++;
    }
    Serial.println();
    if (now > 24 * 3600) {
        Serial.println("Time synchronized");
    } else {
        Serial.println("Time sync failed");
    }
}

// Connect to WiFi
bool connectWiFi() {
    if (wifiConnected) return true;
    
    Serial.print("Connecting to WiFi: ");
    Serial.println(WIFI_SSID);
    
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    
    unsigned long startTime = millis();
    while (WiFi.status() != WL_CONNECTED && (millis() - startTime) < WIFI_TIMEOUT_MS) {
        delay(500);
        Serial.print(".");
    }
    Serial.println();
    
    if (WiFi.status() == WL_CONNECTED) {
        wifiConnected = true;
        Serial.println("WiFi connected");
        Serial.print("IP address: ");
        Serial.println(WiFi.localIP());
        return true;
    } else {
        Serial.println("WiFi connection failed");
        return false;
    }
}

// Read SIM information from modem
void readSimInfo() {
    if (!USE_SIM800L) return;
    
    Serial.println("Reading SIM information...");
    
    // Read MSISDN (phone number)
    if (strlen(SIM_MSISDN_OVERRIDE) == 0) {
        modem.sendAT("+CNUM");
        if (modem.waitResponse(2000L, "+CNUM:") == 1) {
            String response = modem.stream.readString();
            Serial.println("MSISDN: " + response);
        }
    }
    
    // Read ICCID (SIM card number)
    if (strlen(SIM_ICCID_OVERRIDE) == 0) {
        modem.sendAT("+CCID");
        if (modem.waitResponse(2000L, "+CCID:") == 1) {
            String response = modem.stream.readString();
            Serial.println("ICCID: " + response);
        }
    }
    
    // Read IMEI (device ID)
    if (strlen(IMEI_OVERRIDE) == 0) {
        modem.sendAT("+CGSN");
        if (modem.waitResponse(2000L, "+CGSN:") == 1) {
            String response = modem.stream.readString();
            Serial.println("IMEI: " + response);
        }
    }
}

// Connect to cellular network
bool connectCellular() {
    if (!USE_SIM800L) return false;
    
    Serial.println("Initializing modem...");
    modem.restart();
    
    String modemInfo = modem.getModemInfo();
    Serial.print("Modem: ");
    Serial.println(modemInfo);
    
    Serial.print("Waiting for network...");
    if (!modem.waitForNetwork()) {
        Serial.println(" fail");
        return false;
    }
    Serial.println(" OK");
    
    Serial.print("Connecting to ");
    Serial.print(GSM_APN);
    if (!modem.gprsConnect(GSM_APN, GSM_USER, GSM_PASS)) {
        Serial.println(" fail");
        return false;
    }
    Serial.println(" OK");
    
    gsmConnected = true;
    return true;
}

// Ensure network connection (WiFi or Cellular)
bool ensureNetwork() {
    if (netReady) return true;
    
    // Try WiFi first
    if (connectWiFi()) {
        netClient = &wifiClient;
        http = new HttpClient(*netClient, DATABASE_HOST, 443);
        http->setHttpResponseTimeout(10000);
        netReady = true;
        Serial.println("Network ready (WiFi)");
        return true;
    }
    
    // Fallback to cellular if enabled
    if (USE_SIM800L && connectCellular()) {
        netClient = &gsmClient;
        http = new HttpClient(*netClient, DATABASE_HOST, 443);
        http->setHttpResponseTimeout(10000);
        netReady = true;
        Serial.println("Network ready (Cellular)");
        return true;
    }
    
    Serial.println("No network connection available");
    return false;
}

// Check network status and reconnect if needed
void checkNetworkStatus() {
    if (!netReady) {
        ensureNetwork();
        return;
    }
    
    bool networkOk = false;
    if (wifiConnected) {
        networkOk = (WiFi.status() == WL_CONNECTED);
    } else if (gsmConnected) {
        networkOk = modem.isGprsConnected();
    }
    
    if (!networkOk) {
        Serial.println("Network connection lost, reconnecting...");
        netReady = false;
        wifiConnected = false;
        gsmConnected = false;
        delete http;
        http = nullptr;
        ensureNetwork();
    }
}

// Troubleshoot modem if needed
void troubleshootModem() {
    if (!USE_SIM800L) return;
    
    Serial.println("Troubleshooting modem...");
    
    // Check if modem responds
    modem.sendAT("AT");
    if (modem.waitResponse(1000L) != 1) {
        Serial.println("Modem not responding, restarting...");
        modem.restart();
        delay(3000);
    }
    
    // Check signal quality
    modem.sendAT("+CSQ");
    if (modem.waitResponse(2000L, "+CSQ:") == 1) {
        String response = modem.stream.readString();
        Serial.println("Signal quality: " + response);
    }
}

// =========================
// ====== SETUP & LOOP =====
// =========================

void setup() {
    Serial.begin(115200);
    delay(1000);
    
    Serial.println("=== Vehicle Tracker Starting ===");
    Serial.println("Device ID: " + String(DEVICE_ID));
    
    // Initialize pins
    pinMode(RELAY_PIN, OUTPUT);
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);  // Start unlocked
    digitalWrite(BUZZER_PIN, LOW);
    
    // Initialize GPS
    GPSSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
    Serial.println("GPS initialized on Serial2");
    
    // Initialize modem if enabled
    if (USE_SIM800L) {
        ModemSerial.begin(MODEM_BAUD, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
        Serial.println("Modem initialized on Serial1");
    }
    
    // Setup time
    setupTime();
    
    // Connect to network
    ensureNetwork();
    
    // Read SIM info if on cellular
    if (USE_SIM800L || READ_SIM_ON_WIFI) {
        readSimInfo();
    }
    
    // Publish initial device status
    if (netReady) {
        String path = "/devices/" + DEVICE_ID + "/active.json";
        httpPutJson(path, "true");
        
        if (!simPublished) {
            publishSimIdentity();
            simPublished = true;
        }
    }
    
    Serial.println("=== Setup Complete ===");
}

void loop() {
    // Update GPS data
    while (GPSSerial.available() > 0) {
        if (gps.encode(GPSSerial.read())) {
            // GPS data updated
        }
    }
    
    // Check for GPS timeout
    if (millis() > 5000 && gps.charsProcessed() < 10) {
        Serial.println("No GPS detected. Check wiring.");
        while(true);
    }
    
    // Check network status
    checkNetworkStatus();
    
    // Poll for commands from app
    pollCommandsIfDue();
    
    // Check geofence status (every 5 seconds)
    unsigned long now = millis();
    if (now - lastGeofenceCheckTime >= 5000) {
        lastGeofenceCheckTime = now;
        checkGeofenceStatus();
    }
    
    // Publish location if tracking is enabled
    if (isTrackingContinuous && now - lastTrackTime >= TRACK_INTERVAL_MS) {
        lastTrackTime = now;
        if (gps.location.isValid()) {
            publishLocationOnce();
            updateGeofenceStatus(); // Legacy status update
        }
    }
    
    // Publish heartbeat
    if (now - lastHeartbeatTime >= HEARTBEAT_INTERVAL_MS) {
        lastHeartbeatTime = now;
        publishHeartbeat();
    }
    
    // Small delay to prevent watchdog issues
    delay(100);
}