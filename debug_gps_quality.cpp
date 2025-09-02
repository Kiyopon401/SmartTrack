// Add this debugging function to your code
void debugGPSQuality() {
  Serial.println("=== DETAILED GPS DEBUG ===");
  
  // Get fresh GPS data
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  // Check each condition individually
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
  }
  
  Serial.println("========================");
}

// Force location publish without any quality checks
void publishLocationForced() {
  if (!netReady) {
    Serial.println("Network not ready");
    return;
  }
  
  // Get fresh GPS data
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
  
  if (!gps.location.isValid()) {
    Serial.println("GPS location not valid");
    return;
  }
  
  double lat = gps.location.lat();
  double lng = gps.location.lng();
  
  Serial.printf("FORCED PUBLISH - GPS: Sats=%u, HDOP=%.2f, Age=%lu ms\n", 
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
  Serial.printf("Publishing to: %s\n", (pathVehicleRootFor(targetVid) + "/location.json").c_str());
  Serial.printf("JSON: %s\n", json.c_str());
  
  if (httpPutJson(pathVehicleRootFor(targetVid) + "/location.json", json)) {
    Serial.println("FORCED PUBLISH SUCCESS!");
    Serial.printf("Published: lat=%.6f, lng=%.6f\n", lat, lng);
    lastPublishedLat = lat;
    lastPublishedLng = lng;
    hasValidLocation = true;
  } else {
    Serial.println("FORCED PUBLISH FAILED!");
  }
}