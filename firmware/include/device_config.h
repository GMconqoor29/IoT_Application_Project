#pragma once

#include <Arduino.h>

// Cấu hình Wi-Fi & MQTT đang dùng lúc chạy.
struct DeviceConfig {
  String wifiSsid;
  String wifiPass;
  String mqttHost;
  uint16_t mqttPort;
  String mqttUser;
  String mqttPass;

  // Giá trị mặc định lấy từ include/config.h.
  static DeviceConfig defaults();

  // Lưu vào Flash NVS (namespace "airsense") và cập nhật cấu hình hiện tại.
  void saveWifi(const String &ssid, const String &pass);
  void saveMqtt(const String &host, uint16_t port, const String &user, const String &pass);

  // Xoá cấu hình đã lưu trong NVS và quay về mặc định trong config.h.
  void resetToDefaults();
};
