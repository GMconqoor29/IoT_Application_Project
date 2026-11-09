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
#include <DHT.h>
#include <Wire.h>

#include "config.h"
#include "connectivity.h"
#include "device_config.h"
#include "logger.h"
#include "scd40.h"
#include "serial_commands.h"
#include "telemetry_builder.h"

// ================= KHỞI TẠO ĐỐI TƯỢNG =================

DeviceConfig deviceConfig = DeviceConfig::defaults();
Connectivity net(deviceConfig);
SerialCommands commands(deviceConfig, net);

DHT dht(DHT_PIN, DHT_TYPE);
Scd40 scd40(SCD40_ADDR);
TelemetryBuilder telemetry(DEVICE_ID);

const String telemetryTopic = String("airsense/") + DEVICE_ID + "/telemetry";
unsigned long lastReport = 0;

// ==================== ĐỌC CẢM BIẾN ====================

DhtReading readDht() {
  DhtReading reading;
  reading.temperatureC = dht.readTemperature();
  reading.humidityPct  = dht.readHumidity();
  reading.valid = !isnan(reading.temperatureC) && !isnan(reading.humidityPct) &&
                  reading.temperatureC >= -40 && reading.temperatureC <= 125 &&
                  reading.humidityPct >= 0 && reading.humidityPct <= 100;

  if (reading.valid) {
    logPrintf("[DHT22] Temp: %.1f C | Hum: %.1f %%\n", reading.temperatureC, reading.humidityPct);
  } else {
    logPrintf("[DHT22] Khong doc duoc du lieu (kiem tra GPIO %d)\n", DHT_PIN);
  }
  return reading;
}

// Đọc SCD40; nếu DHT22 lỗi (lỏng dây...) thì dùng Temp & Hum của SCD40 thay thế.
void readScd40WithFallback(DhtReading &reading) {
  if (!scd40.isConnected() && !scd40.begin()) {
    logPrintf("[SCD40] Chua ket noi (SDA->GPIO%d, SCL->GPIO%d)\n", I2C_SDA_PIN, I2C_SCL_PIN);
    return;
  }

  if (!scd40.isDataReady()) {
    logPrintf("[SCD40] Dang chuan bi du lieu do...\n");
    return;
  }

  Scd40Reading scd;
  if (!scd40.readMeasurement(scd)) {
    logPrintf("[SCD40] Loi doc du lieu I2C / CRC!\n");
    return;
  }

  logPrintf("[SCD40] True CO2: %u ppm | Temp: %.1f C | Hum: %.1f %%\n",
            static_cast<unsigned>(scd.co2Ppm), scd.temperatureC, scd.humidityPct);

  if (!reading.valid && !isnan(scd.temperatureC) && !isnan(scd.humidityPct)) {
    reading.temperatureC = scd.temperatureC;
    reading.humidityPct  = scd.humidityPct;
    reading.valid = true;
    logPrintf("[FALLBACK] Su dung Temp & Hum tu SCD40 cho Telemetry\n");
  }
}

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

  DhtReading reading = readDht();
  readScd40WithFallback(reading);

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

  dht.begin();

  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  if (scd40.begin()) {
    logPrintf("[OK] Tim thay cam bien SCD40 (0x%02X)! Cho chu ky do dau tien...\n", SCD40_ADDR);
  } else {
    logPrintf("[WARN] Khong tim thay SCD40 tai 0x%02X! Kiem tra day SDA(%d), SCL(%d)\n",
              SCD40_ADDR, I2C_SDA_PIN, I2C_SCL_PIN);
  }

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
