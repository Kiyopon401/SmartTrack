// Add this to your onCommandReceived function for testing:

  } else if (cmd == "FORCE_LOCATION") {
    Serial.println(F("Force location publish requested"));
    publishLocationOnceForced();
  } else if (cmd == "GPS_STATUS") {
    Serial.println(F("=== GPS STATUS ==="));
    Serial.printf("Valid: %s\n", gps.location.isValid() ? "YES" : "NO");
    Serial.printf("Satellites: %u\n", gps.satellites.value());
    Serial.printf("HDOP: %.2f\n", gps.hdop.value());
    Serial.printf("Age: %lu ms\n", gps.location.age());
    if (gps.location.isValid()) {
      Serial.printf("Lat: %.6f, Lng: %.6f\n", gps.location.lat(), gps.location.lng());
    }
    Serial.println(F("================"));
  } else if (cmd == "TEST_QUALITY") {
    double lat, lng;
    bool result = currentLocation(lat, lng);
    Serial.printf("Quality check result: %s\n", result ? "PASSED" : "FAILED");
    if (result) {
      Serial.printf("Location: %.6f, %.6f\n", lat, lng);
    }