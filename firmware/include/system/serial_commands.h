#pragma once

#include <Arduino.h>

#include "connectivity/connectivity.h"
#include "system/device_config.h"

// Đổi cấu hình Wi-Fi / MQTT trực tiếp qua Serial Monitor (USB hoặc UART):
//   wifi <SSID> <PASSWORD>
//   mqtt <HOST> [PORT] [USER] [PASS]
//   status | help | ?
//   reset
class SerialCommands {
public:
  SerialCommands(DeviceConfig &config, Connectivity &net);

  // Đọc và xử lý các dòng lệnh đang chờ trên Serial và Serial0.
  void poll();
  void printStatus();

private:
  DeviceConfig &config;
  Connectivity &net;

  void pollStream(Stream &stream);
  void handleLine(String line);
  void handleWifi(String args);
  void handleMqtt(String args);
  void handleReset();
};
