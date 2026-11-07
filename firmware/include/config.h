#pragma once

#include <Arduino.h>
#include <DHT.h>

// ================= ĐỊNH DANH THIẾT BỊ =================
#define DEVICE_ID           "AIRSENSE-001"
#define DEFAULT_BOOT_ID     "b7f3a901"  // Khớp với boot_id đã provision trong backend/provision/simulator.sql

// ================= CẤU HÌNH CHÂN CẢM BIẾN =================
#define DHT_PIN             4
#define DHT_TYPE            DHT22

// [MQ135] Tạm thời tắt chân analog của MQ135
// #define MQ135_PIN        5   // Analog output (AO) nối vào GPIO 5 (ADC1)
// #define MQ135_RLOAD      10.0f
// #define MQ135_RZERO      64.80f
// #define MQ135_PARA       116.6020682f
// #define MQ135_PARB       2.769034857f

// Chân I2C cho cảm biến SCD40 trên ESP32-S3
#define I2C_SDA_PIN         8
#define I2C_SCL_PIN         9
#define SCD40_ADDR          0x62

// ================= CẤU HÌNH MẶC ĐỊNH WI-FI (CÁCH 1: WIFIMULTI) =================
// Mạng Wi-Fi chính (mặc định)
#define DEFAULT_WIFI_SSID   "Minh"
#define DEFAULT_WIFI_PASS   "minhminhminh"

// Mạng Wi-Fi dự phòng (VD: Hotspot điện thoại / Wi-Fi phòng Lab khi đi demo)
// Để trống "" nếu chưa dùng; nếu điền tên mạng, WiFiMulti sẽ tự động quét & kết nối
#define BACKUP_WIFI_1_SSID  ""
#define BACKUP_WIFI_1_PASS  ""

#define BACKUP_WIFI_2_SSID  ""
#define BACKUP_WIFI_2_PASS  ""

// ================= CẤU HÌNH MẶC ĐỊNH MQTT BROKER =================
// Ngoài việc sửa ở đây, bạn có thể đổi trực tiếp qua Serial Monitor (Cách 2 - lưu Flash NVS):
//   wifi <SSID> <PASSWORD>
//   mqtt <HOST> [PORT] [USER] [PASSWORD]

#define DEFAULT_MQTT_HOST   "192.168.1.94"
#define DEFAULT_MQTT_PORT   1883
#define DEFAULT_MQTT_USER   ""          // Để trống nếu Mosquitto chạy allow_anonymous true
#define DEFAULT_MQTT_PASS   ""

// Chu kỳ đọc và gửi bản tin Telemetry (giây)
#define REPORT_INTERVAL_SEC 10
