#pragma once

#include "Arduino.h"
#include <utility>
#include <vector>

enum wl_status_t {
  WL_IDLE_STATUS = 0,
  WL_NO_SSID_AVAIL = 1,
  WL_CONNECTED = 3,
  WL_CONNECT_FAILED = 4,
  WL_DISCONNECTED = 6
};

enum wifi_mode_t {
  WIFI_OFF = 0,
  WIFI_STA = 1
};

enum wifi_power_t {
  WIFI_POWER_8_5dBm = 34
};

class IPAddress {
public:
  String toString() const { return String("192.168.1.150"); }
};

class WiFiClass {
public:
  void mode(wifi_mode_t m) { currentMode = m; }
  void setTxPower(wifi_power_t p) { currentTxPower = p; }

  void begin(const char *ssid, const char *pass) {
    lastSsid = ssid ? ssid : "";
    lastPass = pass ? pass : "";
    if (shouldConnectOnBegin) {
      currentStatus = WL_CONNECTED;
    }
  }

  void disconnect(bool /*wifioff*/ = false) {
    currentStatus = WL_DISCONNECTED;
  }

  wl_status_t status() const { return currentStatus; }
  String SSID() const { return lastSsid; }
  IPAddress localIP() const { return IPAddress(); }

  void resetMock() {
    currentMode = WIFI_OFF;
    currentStatus = WL_CONNECTED;
    shouldConnectOnBegin = true;
    lastSsid = "Minh";
    lastPass = "";
  }

  wifi_mode_t currentMode = WIFI_OFF;
  wifi_power_t currentTxPower = WIFI_POWER_8_5dBm;
  wl_status_t currentStatus = WL_CONNECTED;
  bool shouldConnectOnBegin = true;
  String lastSsid;
  String lastPass;
};

extern WiFiClass WiFi;

class Client {
public:
  virtual ~Client() = default;
  virtual bool isSecureClient() const { return false; }
};

class WiFiClient : public Client {
public:
  bool isSecureClient() const override { return false; }
};
