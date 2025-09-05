// REAL-TIME GPS TRACKING FIXES
// Replace the existing tracking logic with this real-time version

// =========================
// ====== REAL-TIME CONFIG ======
// =========================

// REAL-TIME TRACKING INTERVALS
static const unsigned long REALTIME_TRACK_INTERVAL_MS = 2UL * 1000UL;  // 2 seconds for real-time
static const unsigned long NORMAL_TRACK_INTERVAL_MS = 5UL * 1000UL;    // 5 seconds for normal
static const unsigned long STATIONARY_TRACK_INTERVAL_MS = 30UL * 1000UL; // 30 seconds when stationary

// MOVEMENT DETECTION FOR REAL-TIME
#define REALTIME_MOVEMENT_METERS 1.0      // 1 meter for real-time tracking
#define NORMAL_MOVEMENT_METERS 3.0        // 3 meters for normal tracking
#define STATIONARY_MOVEMENT_METERS 5.0    // 5 meters when stationary

// REAL-TIME STATE TRACKING
enum TrackingMode {
  TRACKING_REALTIME,    // Device is actively moving
  TRACKING_NORMAL,      // Device is moving normally
  TRACKING_STATIONARY   // Device is stationary
};

TrackingMode currentTrackingMode = TRACKING_NORMAL;
unsigned long lastMovementTime = 0;
unsigned long lastRealtimePublish = 0;
bool isRealtimeMode = false;

// =========================
// ====== REAL-TIME MOVEMENT DETECTION ======
// =========================

// IMPROVED: Real-time movement detection
bool isDeviceMovingRealtime() {
    static double lastLat = 0.0;
    static double lastLng = 0.0;
    static unsigned long lastCheck = 0;
    static int movementCount = 0;
    static int stationaryCount = 0;
    
    unsigned long now = millis();
    
    // Check every 2 seconds for real-time detection
    if (now - lastCheck < 2000) {
        return movementCount >= 2; // Need 2+ consecutive movement readings
    }
    
    lastCheck = now;
    
    if (lastLat != 0.0 && lastLng != 0.0) {
        double distance = distanceMeters(lastLat, lastLng, smoothedLat, smoothedLng);
        
        if (distance >= REALTIME_MOVEMENT_METERS) { // 1+ meters = moving
            movementCount++;
            stationaryCount = 0;
            lastMovementTime = now;
            Serial.printf("REALTIME: %.2fm movement, count: %d\n", distance, movementCount);
        } else {
            stationaryCount++;
            movementCount = 0;
            Serial.printf("REALTIME: %.2fm movement, stationary count: %d\n", distance, stationaryCount);
        }
        
        // Update last position
        lastLat = smoothedLat;
        lastLng = smoothedLng;
    } else {
        // Initialize position
        lastLat = smoothedLat;
        lastLng = smoothedLng;
        return false;
    }
    
    return movementCount >= 2; // Moving if 2+ consecutive readings show movement
}

// IMPROVED: Determine tracking mode based on movement
TrackingMode determineTrackingMode() {
    bool isMoving = isDeviceMovingRealtime();
    unsigned long now = millis();
    
    if (isMoving) {
        // If moving, check if it's been moving for a while (real-time mode)
        if (now - lastMovementTime < 10000) { // Moving for less than 10 seconds
            return TRACKING_REALTIME;
        } else {
            return TRACKING_NORMAL;
        }
    } else {
        // Not moving - check if it's been stationary for a while
        if (now - lastMovementTime > 60000) { // Stationary for more than 1 minute
            return TRACKING_STATIONARY;
        } else {
            return TRACKING_NORMAL; // Still in normal mode
        }
    }
}

// =========================
// ====== REAL-TIME PUBLISHING ======
// =========================

// IMPROVED: Real-time location publishing
void publishRealtimeLocation() {
    if (!netReady) return;
    
    // Update smoothed coordinates
    updateSmoothedCoordinates();
    
    // Determine current tracking mode
    TrackingMode mode = determineTrackingMode();
    currentTrackingMode = mode;
    
    // Check if we should publish based on mode
    bool shouldPublish = false;
    unsigned long now = millis();
    
    switch (mode) {
        case TRACKING_REALTIME:
            // Publish every 2 seconds when in real-time mode
            if (now - lastRealtimePublish >= REALTIME_TRACK_INTERVAL_MS) {
                shouldPublish = true;
                lastRealtimePublish = now;
                Serial.println("REALTIME MODE: Publishing location");
            }
            break;
            
        case TRACKING_NORMAL:
            // Publish every 5 seconds in normal mode
            if (now - lastRealtimePublish >= NORMAL_TRACK_INTERVAL_MS) {
                shouldPublish = true;
                lastRealtimePublish = now;
                Serial.println("NORMAL MODE: Publishing location");
            }
            break;
            
        case TRACKING_STATIONARY:
            // Publish every 30 seconds when stationary
            if (now - lastRealtimePublish >= STATIONARY_TRACK_INTERVAL_MS) {
                shouldPublish = true;
                lastRealtimePublish = now;
                Serial.println("STATIONARY MODE: Publishing location");
            }
            break;
    }
    
    if (!shouldPublish) {
        return;
    }
    
    // Use smoothed coordinates for publishing
    double lat = smoothedLat;
    double lng = smoothedLng;
    
    Serial.printf("Publishing location: lat=%.6f, lng=%.6f, mode=%d\n", 
                  lat, lng, mode);
    
    time_t nowEpoch = time(nullptr);
    long ts = (nowEpoch > 100000) ? (long)nowEpoch * 1000L : (long)millis();

    String json = String("{\"latitude\":") + String(lat, 6) + 
                  ",\"longitude\":" + String(lng, 6) +
                  ",\"timestamp\":" + ts + 
                  ",\"accuracy\":" + String(gps.hdop.value(), 2) +
                  ",\"satellites\":" + String(gps.satellites.value()) +
                  ",\"smoothed\":true" +
                  ",\"tracking_mode\":" + String(mode) +
                  ",\"realtime\":" + (mode == TRACKING_REALTIME ? "true" : "false") +
                  "}";
                  
    const String targetVid = vehicleIdForPublish();
    
    if (httpPutJson(pathVehicleRootFor(targetVid) + "/location.json", json)) {
        Serial.println(F("Location published successfully!"));
        // Update last published coordinates
        lastPublishedLat = lat;
        lastPublishedLng = lng;
        hasValidLocation = true;
    } else {
        Serial.println(F("Location publish failed"));
    }
}

// =========================
// ====== COMMAND UPDATES ======
// =========================

// Add these new commands to your onCommandReceived() function:

// Add this case to your command handler:
/*
  } else if (cmd == "REALTIME_ON") {
    isRealtimeMode = true;
    Serial.println("Real-time tracking enabled");
  } else if (cmd == "REALTIME_OFF") {
    isRealtimeMode = false;
    Serial.println("Real-time tracking disabled");
  } else if (cmd == "TRACKING_STATUS") {
    Serial.printf("Tracking Mode: %d (0=Realtime, 1=Normal, 2=Stationary)\n", currentTrackingMode);
    Serial.printf("Last Movement: %lu ms ago\n", millis() - lastMovementTime);
    Serial.printf("Last Publish: %lu ms ago\n", millis() - lastRealtimePublish);
  } else if (cmd == "FORCE_REALTIME") {
    currentTrackingMode = TRACKING_REALTIME;
    lastMovementTime = millis();
    Serial.println("Forced real-time mode");
  }
*/

// =========================
// ====== MAIN LOOP UPDATE ======
// =========================

// Replace your main loop tracking section with:
/*
  // Real-time location tracking
  if (isTrackingContinuous && netReady) {
    publishRealtimeLocation();
  }
*/