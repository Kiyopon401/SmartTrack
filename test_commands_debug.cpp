// Add these commands to your onCommandReceived() function:

  } else if (cmd == "DEBUG_GPS") {
    Serial.println("Manual GPS debug requested");
    debugGPSQuality();
  } else if (cmd == "FORCE_PUBLISH") {
    Serial.println("Force location publish requested");
    publishLocationForced();
  } else if (cmd == "TEST_QUALITY_DETAILED") {
    Serial.println("Testing GPS quality check in detail...");
    debugGPSQuality();
    
    // Now test the actual currentLocation function
    double lat, lng;
    bool result = currentLocation(lat, lng);
    Serial.printf("currentLocation() result: %s\n", result ? "SUCCESS" : "FAILED");
    if (result) {
      Serial.printf("Returned coordinates: %.6f, %.6f\n", lat, lng);
    }
  }