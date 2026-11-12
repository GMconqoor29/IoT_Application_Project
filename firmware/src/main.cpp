/*
 * AirSense ESP32-S3 Firmware
 * Cảm biến: DHT22 + SCD40
 * Kết nối:
 *   - Cách 1: Tách file include/config.h + WiFiMulti (quét nhiều mạng Wi-Fi dự phòng)
 *   - Cách 2: Đổi Wi-Fi & MQTT trực tiếp qua Serial Monitor và lưu vào Flash NVS (Preferences)
 *
 * Cấu trúc:
 *   config.h           Chân cảm biến, Wi-Fi/MQTT mặc định, chu kỳ gửi
 *   device_config      Cấu hình Wi-Fi/MQTT lúc chạy + lưu NVS
 *   connectivity       Wi-Fi (WiFiMulti) + MQTT, tự kết nối lại
 *   serial_commands    Lệnh wifi / mqtt / status / reset qua Serial
 *   scd40              Driver I2C cho SCD40
 *   telemetry_builder  Đóng gói JSON Telemetry chuẩn AirSense
 *   logger             In log ra cả USB (Serial) và UART (Serial0)
 */

#include <Arduino.h>

#include "config.h"
#include "connectivity/connectivity.h"
#include "system/device_config.h"
#include "system/logger.h"
#include "system/serial_commands.h"
#include "telemetry/telemetry_builder.h"
#include "sensors/environment_sensor.h"

// ================= KHỞI TẠO ĐỐI TƯỢNG =================

DeviceConfig deviceConfig = DeviceConfig::load();
Connectivity net(deviceConfig);
SerialCommands commands(deviceConfig, net);

EnvironmentSensor environmentSensor;
TelemetryBuilder telemetry(DEVICE_ID);

const String telemetryTopic = String("airsense/") + DEVICE_ID + "/telemetry";
unsigned long lastReport = 0;

// ==================== GỬI TELEMETRY ====================

void publishTelemetry(const String &payload) {
  if (!net.mqttConnected()) {
    logPrintf("[MQTT] Mat ket noi broker; khong the publish tin nhan.\n");
    return;
  }

  if (net.publish(telemetryTopic, payload)) {
    logPrintf("[MQTT] Publish THANH CONG toi topic: %s\n", telemetryTopic.c_str());
  } else {
    logPrintf("[MQTT] Publish THAT BAI!\n");
  }
}

void reportCycle() {
  logPrintf("------------------------------------------\n");

  EnvironmentReading reading = environmentSensor.read();

  const String payload = telemetry.buildJson(reading, millis(), REPORT_INTERVAL_SEC);
  logPrintf("[TELEMETRY] Payload JSON:\n%s\n", payload.c_str());

  publishTelemetry(payload);
  logPrintf("Status: OK\n");
}

// ==================== SETUP & LOOP ====================

void setup() {
  Serial.begin(115200);
  Serial0.begin(115200);
  delay(1500);

  logPrintf("\n==========================================\n");
  logPrintf("            AirSense ESP32-S3\n");
  logPrintf("        DHT22 + SCD40 + Wi-Fi & MQTT\n");
  logPrintf("     (Config.h + WiFiMulti + NVS Flash)\n");
  logPrintf("==========================================\n");

  environmentSensor.begin();

  telemetry.begin();
  commands.printStatus();
  net.begin();
}

void loop() {
  commands.poll();
  net.loop();

  if (millis() - lastReport >= REPORT_INTERVAL_SEC * 1000UL) {
    lastReport = millis();
    reportCycle();
  }

  delay(10);
}
