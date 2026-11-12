#pragma once

#include "WiFi.h"

class WiFiClientSecure : public WiFiClient {
public:
  void setCACert(const char *rootCA) { caCert = rootCA; }
  bool isSecureClient() const override { return true; }

  const char *caCert = nullptr;
};
