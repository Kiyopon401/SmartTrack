// IMPROVED STATIONARY DETECTION FOR GPS TRACKER
// Replace the existing isDeviceStationary() function with this improved version

// =========================
// ====== STATIONARY DETECTION ====
// =========================

// IMPROVED: Stationary detection with better movement tracking
bool isDeviceStationary() {
    static double lastLat = 0.0;
    static double lastLng = 0.0;
    static unsigned long lastCheck = 0;
    static int stationaryCount = 0;
    static int movementCount = 0;
    static bool hasInitialPosition = false;
    
    unsigned long now = millis();
    
    // Check every 10 seconds for more responsive detection (was 60 seconds)
    if (now - lastCheck < 10000) {
        return stationaryCount >= 5; // Need 5+ consecutive stationary checks (50+ seconds)
    }
    
    lastCheck = now;
    
    // Initialize position on first valid reading
    if (!hasInitialPosition && smoothedLat != 0.0 && smoothedLng != 0.0) {
        lastLat = smoothedLat;
        lastLng = smoothedLng;
        hasInitialPosition = true;
        Serial.println("Stationary detection initialized");
        return false; // Not stationary on first reading
    }
    
    if (hasInitialPosition) {
        double distance = distanceMeters(lastLat, lastLng, smoothedLat, smoothedLng);
        
        // More sensitive movement detection - 2 meters instead of 5
        if (distance < 2.0) { // Less than 2 meters = stationary
            stationaryCount++;
            movementCount = 0; // Reset movement counter
            Serial.printf("Stationary check: %.2fm movement, stationary count: %d\n", distance, stationaryCount);
        } else {
            movementCount++;
            stationaryCount = 0; // Reset stationary counter
            Serial.printf("Device moving: %.2fm movement, movement count: %d\n", distance, movementCount);
        }
        
        // Update last position for next check
        lastLat = smoothedLat;
        lastLng = smoothedLng;
    }
    
    // Consider stationary if we have 5+ consecutive stationary readings (50+ seconds)
    // Consider moving if we have 2+ consecutive movement readings (20+ seconds)
    bool isStationary = stationaryCount >= 5;
    
    if (isStationary && movementCount > 0) {
        // If we detected movement recently, don't consider stationary yet
        return false;
    }
    
    return isStationary;
}

// ADDITIONAL IMPROVEMENTS TO ADD:

// 1. Reduce MIN_MOVEMENT_METERS for more sensitive tracking
#define MIN_MOVEMENT_METERS 3.0  // Changed from 10.0 to 3.0

// 2. Add movement validation in publishSmoothedLocation()
void publishSmoothedLocation() {
  if (!netReady) return;
  
  // Update smoothed coordinates
  updateSmoothedCoordinates();
  
  // Check if device is stationary
  bool stationary = isDeviceStationary();
  
  // Only publish if:
  // 1. First publish (hasValidLocation = false)
  // 2. Device is moving AND moved enough distance
  // 3. Device has been stationary for 5+ minutes (reduced from 10)
  static unsigned long lastStationaryPublish = 0;
  unsigned long now = millis();
  
  bool shouldPublish = false;
  
  if (!hasValidLocation) {
    shouldPublish = true;
    Serial.println("First location publish");
  } else if (!stationary && shouldPublishSmoothedLocation()) {
    shouldPublish = true;
    Serial.println("Device moving - publishing location");
  } else if (stationary && (now - lastStationaryPublish) > 300000) { // 5 minutes (was 10)
    shouldPublish = true;
    lastStationaryPublish = now;
    Serial.println("Device stationary - heartbeat publish");
  }
  
  if (!shouldPublish) {
    Serial.println("Skipping publish - device stationary and recent publish");
    return;
  }
  
  // Use smoothed coordinates for publishing
  double lat = smoothedLat;
  double lng = smoothedLng;
  
  Serial.printf("Publishing location: lat=%.6f, lng=%.6f, stationary=%s\n", 
                lat, lng, stationary ? "YES" : "NO");
  
  time_t nowEpoch = time(nullptr);
  long ts = (nowEpoch > 100000) ? (long)nowEpoch * 1000L : (long)millis();

  String json = String("{\"latitude\":") + String(lat, 6) + 
                ",\"longitude\":" + String(lng, 6) +
                ",\"timestamp\":" + ts + 
                ",\"accuracy\":" + String(gps.hdop.value(), 2) +
                ",\"satellites\":" + String(gps.satellites.value()) +
                ",\"smoothed\":true" +
                ",\"stationary\":" + (stationary ? "true" : "false") +
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