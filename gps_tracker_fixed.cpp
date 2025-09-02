// Fixed GPS quality check function
bool currentLocation(double &lat, double &lng) {
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  // Get current GPS values
  bool isValid = gps.location.isValid();
  uint32_t satellites = gps.satellites.value();
  float hdop = gps.hdop.value();
  
  // Debug output
  Serial.printf("GPS Quality Check: Valid=%s, Sats=%u, HDOP=%.2f (threshold=%.1f)\n", 
                isValid ? "YES" : "NO", satellites, hdop, GPS_ACCURACY_THRESHOLD);
  
  // Simple GPS quality check - your GPS is working fine
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

// Alternative: Force location publish without quality check for testing
void publishLocationOnceForced() {
  if (!netReady) return;
  
  double lat, lng;
  
  // Get latest GPS data
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  if (!gps.location.isValid()) {
    Serial.println(F("GPS location not valid"));
    return;
  }
  
  lat = gps.location.lat();
  lng = gps.location.lng();
  
  // Log GPS quality info
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
  Serial.printf("Publishing to path: %s\n", (pathVehicleRootFor(targetVid) + "/location.json").c_str());
  
  if (httpPutJson(pathVehicleRootFor(targetVid) + "/location.json", json)) {
    Serial.println(F("Location published successfully!"));
    Serial.printf("Published: lat=%.6f, lng=%.6f\n", lat, lng);
    // Update last published coordinates
    lastPublishedLat = lat;
    lastPublishedLng = lng;
    hasValidLocation = true;
  } else {
    Serial.println(F("Location publish failed"));
  }
}