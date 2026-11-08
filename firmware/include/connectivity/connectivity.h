#pragma once

#include <Arduino.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WiFiMulti.h>

#include "system/device_config.h"

// Quản lý kết nối Wi-Fi (WiFiMulti + mạng dự phòng) và MQTT broker,
// tự động kết nối lại trong loop().
class Connectivity {
public:
  explicit Connectivity(DeviceConfig &config);

  // Đăng ký danh sách Wi-Fi, kết nối Wi-Fi rồi kết nối MQTT lần đầu.
  void begin();
  // Gọi liên tục trong loop(): duy trì Wi-Fi, MQTT và xử lý gói MQTT.
  void loop();

  bool wifiConnected() const;
  bool mqttConnected();
  bool publish(const String &topic, const String &payload);

  // Thêm một mạng vào danh sách WiFiMulti.
  void addAccessPoint(const String &ssid, const String &pass);
  // Ngắt rồi kết nối lại Wi-Fi + MQTT (sau khi đổi cấu hình).
  void reconnectAll();
  // Ngắt rồi kết nối lại MQTT ngay lập tức (sau khi đổi broker).
  void reconnectMqttNow();

private:
  DeviceConfig &config;
  WiFiMulti wifiMulti;
  WiFiClientSecure wifiClient;
  PubSubClient mqttClient;

  unsigned long lastWifiAttempt = 0;
  unsigned long lastMqttAttempt = 0;
  bool timeSynced = false;

  void registerAccessPoints();
  void connectWiFi();
  void ensureWiFiConnected();
  void ensureMqttConnected();
  bool synchronizeTime();
};
