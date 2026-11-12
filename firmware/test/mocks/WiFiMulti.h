#pragma once

#include "WiFi.h"
#include <utility>
#include <vector>

class WiFiMulti {
public:
  bool addAP(const char *ssid, const char *passphrase = nullptr) {
    registeredAps.emplace_back(ssid ? ssid : "", passphrase ? passphrase : "");
    return true;
  }

  uint8_t run(uint32_t /*connectTimeout*/ = 5000) {
    runCallCount++;
    if (shouldConnectOnRun && !registeredAps.empty()) {
      WiFi.currentStatus = WL_CONNECTED;
      WiFi.lastSsid = String(registeredAps.front().first);
    }
    return WiFi.status();
  }

  static void resetMock() {
    registeredAps.clear();
    runCallCount = 0;
    shouldConnectOnRun = true;
  }

  static std::vector<std::pair<std::string, std::string>> registeredAps;
  static int runCallCount;
  static bool shouldConnectOnRun;
};
