#pragma once

#include <Arduino.h>

// Runtime Wi-Fi and MQTT configuration.
struct DeviceConfig {
  String wifiSsid;
  String wifiPass;
  String mqttHost;
  uint16_t mqttPort;
  String mqttUser;
  String mqttPass;

  // Build configuration using compile-time defaults.
  static DeviceConfig defaults();

  // Load saved NVS settings over defaults.
  static DeviceConfig load();

  // Save runtime settings to NVS.
  void saveWifi(const String &ssid, const String &pass);
  void saveMqtt(const String &host, uint16_t port, const String &user, const String &pass);

  // Clear NVS settings and restore defaults.
  void resetToDefaults();
};
