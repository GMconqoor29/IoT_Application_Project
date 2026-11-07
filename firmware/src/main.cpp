/*
 * AirSense ESP32-S3 Firmware
 * Cảm biến: DHT22 + SCD40 (MQ135 tạm thời comment)
 * Kết nối:
 *   - Cách 1: Tách file include/config.h + WiFiMulti (quét nhiều mạng Wi-Fi dự phòng)
 *   - Cách 2: Đổi Wi-Fi & MQTT trực tiếp qua Serial Monitor và lưu vào Flash NVS (Preferences)
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiMulti.h>
#include <PubSubClient.h>
#include <Preferences.h>
#include <Wire.h>
#include <DHT.h>
#include "config.h"
#include "telemetry_builder.h"

// ================= KHỞI TẠO ĐỐI TƯỢNG =================

WiFiMulti wifiMulti;
WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);
Preferences prefs;

DHT dht(DHT_PIN, DHT_TYPE);
TelemetryBuilder telemetry(DEVICE_ID);

// Biến cấu hình động (được nạp từ Flash NVS hoặc lấy mặc định từ config.h)
String wifiSsid = DEFAULT_WIFI_SSID;
String wifiPass = DEFAULT_WIFI_PASS;
String mqttHost = DEFAULT_MQTT_HOST;
uint16_t mqttPort = DEFAULT_MQTT_PORT;
String mqttUser = DEFAULT_MQTT_USER;
String mqttPass = DEFAULT_MQTT_PASS;

bool scd40Connected = false;
unsigned long lastReport = 0;
unsigned long lastMqttAttempt = 0;
unsigned long lastWifiAttempt = 0;

// In ra đồng thời cả cổng USB (Serial / HWCDC) và cổng UART (Serial0 / CH343)
void logPrintf(const char *format, ...) {
  char buf[384];
  va_list args;
  va_start(args, format);
  vsnprintf(buf, sizeof(buf), format, args);
  va_end(args);

  Serial.print(buf);
  Serial0.print(buf);
}

// ==================== QUẢN LÝ CẤU HÌNH NVS (FLASH) & WIFIMULTI ====================

void registerWiFiNetworks() {
  // Đăng ký mạng Wi-Fi đang lưu trong NVS (hoặc mặc định)
  if (wifiSsid.length() > 0) {
    wifiMulti.addAP(wifiSsid.c_str(), wifiPass.c_str());
  }
  // Đăng ký thêm mạng mặc định trong config.h (nếu khác với mạng đã lưu trong NVS)
  if (strlen(DEFAULT_WIFI_SSID) > 0 && wifiSsid != DEFAULT_WIFI_SSID) {
    wifiMulti.addAP(DEFAULT_WIFI_SSID, DEFAULT_WIFI_PASS);
  }
  // Đăng ký các mạng dự phòng trong config.h (VD: Hotspot điện thoại, Wi-Fi trường)
  if (strlen(BACKUP_WIFI_1_SSID) > 0) {
    wifiMulti.addAP(BACKUP_WIFI_1_SSID, BACKUP_WIFI_1_PASS);
  }
  if (strlen(BACKUP_WIFI_2_SSID) > 0) {
    wifiMulti.addAP(BACKUP_WIFI_2_SSID, BACKUP_WIFI_2_PASS);
  }
}

void loadConfig() {
  // Luôn ưu tiên lấy trực tiếp cấu hình bạn chỉnh sửa thủ công trong include/config.h
  wifiSsid = DEFAULT_WIFI_SSID;
  wifiPass = DEFAULT_WIFI_PASS;
  mqttHost = DEFAULT_MQTT_HOST;
  mqttPort = DEFAULT_MQTT_PORT;
  mqttUser = DEFAULT_MQTT_USER;
  mqttPass = DEFAULT_MQTT_PASS;

  registerWiFiNetworks();
}

void saveWifiConfig(const String &ssid, const String &pass) {
  prefs.begin("airsense", false);
  prefs.putString("wifi_ssid", ssid);
  prefs.putString("wifi_pass", pass);
  prefs.end();
  wifiSsid = ssid;
  wifiPass = pass;
  wifiMulti.addAP(wifiSsid.c_str(), wifiPass.c_str());
}

void saveMqttConfig(const String &host, uint16_t port, const String &user, const String &pass) {
  prefs.begin("airsense", false);
  prefs.putString("mqtt_host", host);
  prefs.putUShort("mqtt_port", port);
  prefs.putString("mqtt_user", user);
  prefs.putString("mqtt_pass", pass);
  prefs.end();
  mqttHost = host;
  mqttPort = port;
  mqttUser = user;
  mqttPass = pass;
}

void resetConfigToDefault() {
  prefs.begin("airsense", false);
  prefs.clear();
  prefs.end();
  loadConfig();
}

void printConfigStatus() {
  logPrintf("\n========== CAU HINH HIEN TAI ==========\n");
  logPrintf("Device ID : %s\n", DEVICE_ID);
  logPrintf("Wi-Fi NVS : %s (%s)\n",
            wifiSsid.c_str(),
            WiFi.status() == WL_CONNECTED ? "CONNECTED" : "DISCONNECTED");
  if (WiFi.status() == WL_CONNECTED) {
    logPrintf("Active AP : %s | IP: %s\n",
              WiFi.SSID().c_str(),
              WiFi.localIP().toString().c_str());
  }
  logPrintf("MQTT Host : %s:%u (%s)\n",
            mqttHost.c_str(),
            mqttPort,
            mqttClient.connected() ? "CONNECTED" : "DISCONNECTED");
  logPrintf("MQTT Auth : %s\n", mqttUser.length() > 0 ? mqttUser.c_str() : "(anonymous)");
  logPrintf("---------------------------------------\n");
  logPrintf("Lenh doi cau hinh qua Serial Monitor:\n");
  logPrintf("  wifi <SSID> <PASSWORD>\n");
  logPrintf("  mqtt <IP_HOST> [PORT] [USER] [PASS]\n");
  logPrintf("  status  (xem trang thai hien tai)\n");
  logPrintf("  reset   (khoi phuc mac dinh trong config.h)\n");
  logPrintf("=======================================\n");
}

// ==================== HÀM XỬ LÝ MQ135 (TẠM THỜI TẮT) ====================
/*
// Tính hệ số bù nhiệt độ và độ ẩm cho MQ135
float getCorrectionFactor(float temperature, float humidity) {
  return 0.00035f * temperature * temperature - 0.02718f * temperature + 1.39538f - (humidity - 33.0f) * 0.0018f;
}

// Chuyển đổi giá trị ADC (12-bit: 0..4095) của MQ135 sang nồng độ CO2 ước lượng (ppm)
float calculateMQ135CO2PPM(int adcRaw, float temperature, float humidity) {
  if (adcRaw <= 0 || adcRaw >= 4095) {
    return 0.0f;
  }
  // Tính điện trở cảm biến Rs (kOhm)
  float rs = ((4095.0f / (float)adcRaw) - 1.0f) * MQ135_RLOAD;

  // Bù nhiệt độ & độ ẩm nếu có dữ liệu hợp lệ
  if (!isnan(temperature) && !isnan(humidity)) {
    rs /= getCorrectionFactor(temperature, humidity);
  }

  // Công thức đặc tuyến: PPM = PARA * (Rs / R0)^(-PARB)
  return MQ135_PARA * powf((rs / MQ135_RZERO), -MQ135_PARB);
}
*/

// ==================== HÀM XỬ LÝ SCD40 (I2C) ====================

uint8_t scd40CalcCRC(uint8_t data0, uint8_t data1) {
  uint8_t crc = 0xFF;
  uint8_t buf[2] = {data0, data1};
  for (int i = 0; i < 2; i++) {
    crc ^= buf[i];
    for (uint8_t bit = 8; bit > 0; --bit) {
      if (crc & 0x80) {
        crc = (crc << 1) ^ 0x31;
      } else {
        crc = (crc << 1);
      }
    }
  }
  return crc;
}

bool scd40SendCommand(uint16_t cmd) {
  Wire.beginTransmission(SCD40_ADDR);
  Wire.write((uint8_t)(cmd >> 8));
  Wire.write((uint8_t)(cmd & 0xFF));
  return (Wire.endTransmission() == 0);
}

bool scd40Init() {
  if (!scd40SendCommand(0x3F86)) {
    return false;
  }
  delay(500);

  if (!scd40SendCommand(0x21B1)) {
    return false;
  }
  return true;
}

bool scd40IsDataReady() {
  if (!scd40SendCommand(0xE4B8)) {
    return false;
  }
  delay(1);
  if (Wire.requestFrom(SCD40_ADDR, (uint8_t)3) != 3) {
    return false;
  }
  uint8_t msb = Wire.read();
  uint8_t lsb = Wire.read();
  uint8_t crc = Wire.read();
  if (scd40CalcCRC(msb, lsb) != crc) {
    return false;
  }
  uint16_t status = ((uint16_t)msb << 8) | lsb;
  return ((status & 0x07FF) != 0);
}

bool scd40ReadMeasurement(uint16_t &co2, float &temp, float &hum) {
  if (!scd40SendCommand(0xEC05)) {
    return false;
  }
  delay(1);
  if (Wire.requestFrom(SCD40_ADDR, (uint8_t)9) != 9) {
    return false;
  }

  uint8_t buf[9];
  for (int i = 0; i < 9; i++) {
    buf[i] = Wire.read();
  }

  if (scd40CalcCRC(buf[0], buf[1]) != buf[2] ||
      scd40CalcCRC(buf[3], buf[4]) != buf[5] ||
      scd40CalcCRC(buf[6], buf[7]) != buf[8]) {
    return false;
  }

  co2 = ((uint16_t)buf[0] << 8) | buf[1];
  uint16_t rawTemp = ((uint16_t)buf[3] << 8) | buf[4];
  uint16_t rawHum  = ((uint16_t)buf[6] << 8) | buf[7];

  temp = -45.0f + 175.0f * ((float)rawTemp / 65535.0f);
  hum  = 100.0f * ((float)rawHum / 65535.0f);
  return true;
}

// ==================== KẾT NỐI WI-FI & MQTT ====================

void connectWiFi() {
  lastWifiAttempt = millis();
  logPrintf("\n[WiFi] Dang quet & ket noi Wi-Fi (Uu tien: %s) ...\n", wifiSsid.c_str());

  WiFi.mode(WIFI_STA);

  // Thử kết nối trực tiếp vào mạng ưu tiên trong NVS trước, sau đó dùng WiFiMulti quét các mạng dự phòng
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 10000) {
    delay(500);
    Serial.print(".");
    Serial0.print(".");
  }

  if (WiFi.status() != WL_CONNECTED) {
    logPrintf("\n[WiFi] Chuyen sang quet danh sach WiFiMulti...\n");
    wifiMulti.run(8000);
  }

  if (WiFi.status() == WL_CONNECTED) {
    logPrintf("\n[WiFi] Ket noi thanh cong toi '%s'! IP ESP32: %s\n",
              WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
    configTime(0, 0, "pool.ntp.org", "time.google.com");
  } else {
    logPrintf("\n[WiFi] Ket noi THAT BAI (status=%d). Go lenh 'wifi <SSID> <PASS>' de doi mang.\n",
              WiFi.status());
  }
}

void ensureWiFiConnected() {
  if (WiFi.status() == WL_CONNECTED) {
    return;
  }
  if (millis() - lastWifiAttempt < 15000) {
    return;
  }
  lastWifiAttempt = millis();
  logPrintf("[WiFi] Mat ket noi! Dang quet lai qua WiFiMulti...\n");
  if (wifiMulti.run(5000) == WL_CONNECTED) {
    logPrintf("[WiFi] Da ket noi lai toi '%s'! IP: %s\n",
              WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
  }
}

void reconnectMQTT() {
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  if (mqttClient.connected()) {
    return;
  }

  if (millis() - lastMqttAttempt < 5000) {
    return;
  }
  lastMqttAttempt = millis();

  mqttClient.setServer(mqttHost.c_str(), mqttPort);
  logPrintf("[MQTT] Dang ket noi toi broker %s:%u ... ", mqttHost.c_str(), mqttPort);

  String clientId = String("AirSense-") + DEVICE_ID;
  bool connected = false;

  if (mqttUser.length() > 0) {
    connected = mqttClient.connect(clientId.c_str(), mqttUser.c_str(), mqttPass.c_str());
  } else {
    connected = mqttClient.connect(clientId.c_str());
  }

  if (connected) {
    logPrintf("THANH CONG!\n");
  } else {
    logPrintf("THAT BAI, rc = %d (Go 'mqtt <IP> [PORT] [USER] [PASS]' neu doi broker)\n",
              mqttClient.state());
  }
}

// ==================== XỬ LÝ LỆNH ĐỔI CẤU HÌNH QUA SERIAL ====================

void processCommandLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line.equalsIgnoreCase("status") || line.equalsIgnoreCase("help") || line.equals("?")) {
    printConfigStatus();
    return;
  }

  if (line.equalsIgnoreCase("reset")) {
    resetConfigToDefault();
    logPrintf("[CONFIG] Da khoi phuc cau hinh mac dinh tu config.h!\n");
    printConfigStatus();
    mqttClient.disconnect();
    WiFi.disconnect(true);
    delay(100);
    connectWiFi();
    reconnectMQTT();
    return;
  }

  // Lệnh: wifi <SSID> <PASSWORD>
  if (line.startsWith("wifi ")) {
    String rest = line.substring(5);
    rest.trim();
    int spaceIdx = rest.indexOf(' ');
    if (spaceIdx <= 0) {
      logPrintf("[ERR] Cu phap: wifi <SSID> <PASSWORD>\n");
      return;
    }
    String newSsid = rest.substring(0, spaceIdx);
    String newPass = rest.substring(spaceIdx + 1);
    newPass.trim();

    saveWifiConfig(newSsid, newPass);
    logPrintf("[CONFIG] Da luu Wi-Fi moi vao Flash NVS: SSID='%s'\n", wifiSsid.c_str());
    mqttClient.disconnect();
    WiFi.disconnect(true);
    delay(100);
    connectWiFi();
    reconnectMQTT();
    return;
  }

  // Lệnh: mqtt <HOST> [PORT] [USER] [PASS]
  if (line.startsWith("mqtt ")) {
    String rest = line.substring(5);
    rest.trim();

    String tokens[4];
    int count = 0;
    while (rest.length() > 0 && count < 4) {
      int sp = rest.indexOf(' ');
      if (sp < 0) {
        tokens[count++] = rest;
        break;
      } else {
        tokens[count++] = rest.substring(0, sp);
        rest = rest.substring(sp + 1);
        rest.trim();
      }
    }

    if (count < 1 || tokens[0].length() == 0) {
      logPrintf("[ERR] Cu phap: mqtt <IP_HOST> [PORT] [USER] [PASS]\n");
      return;
    }

    String newHost = tokens[0];
    uint16_t newPort = (count >= 2) ? (uint16_t)tokens[1].toInt() : DEFAULT_MQTT_PORT;
    if (newPort == 0) newPort = DEFAULT_MQTT_PORT;
    String newUser = (count >= 3) ? tokens[2] : "";
    String newPass = (count >= 4) ? tokens[3] : "";

    saveMqttConfig(newHost, newPort, newUser, newPass);
    logPrintf("[CONFIG] Da luu MQTT moi vao Flash NVS: %s:%u (user=%s)\n",
              mqttHost.c_str(), mqttPort, mqttUser.length() ? mqttUser.c_str() : "anonymous");

    mqttClient.disconnect();
    lastMqttAttempt = 0;
    reconnectMQTT();
    return;
  }

  logPrintf("[WARN] Lenh khong hop le: '%s'. Go 'help' de xem huong dan.\n", line.c_str());
}

void checkSerialInput() {
  while (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    processCommandLine(line);
  }
  while (Serial0.available()) {
    String line = Serial0.readStringUntil('\n');
    processCommandLine(line);
  }
}

// ==================== SETUP & LOOP ====================

void setup() {
  Serial.begin(115200);
  Serial0.begin(115200);
  delay(1500);

  // Nạp cấu hình Wi-Fi & MQTT từ bộ nhớ Flash NVS + đăng ký danh sách WiFiMulti
  loadConfig();

  logPrintf("\n==========================================\n");
  logPrintf("            AirSense ESP32-S3\n");
  logPrintf("     DHT22 + SCD40 + Wi-Fi & MQTT\n");
  logPrintf("     (Config.h + WiFiMulti + NVS Flash)\n");
  logPrintf("           (MQ135: Disabled)\n");
  logPrintf("==========================================\n");

  // 1. Khởi tạo DHT22
  dht.begin();

  // [MQ135] Tạm thời comment khởi tạo chân analog MQ135
  // pinMode(MQ135_PIN, INPUT);
  // analogReadResolution(12); // ADC 12-bit (0 - 4095)

  // 2. Khởi tạo I2C cho SCD40
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  scd40Connected = scd40Init();
  if (scd40Connected) {
    logPrintf("[OK] Tim thay cam bien SCD40 (0x62)! Cho chu ky do dau tien...\n");
  } else {
    logPrintf("[WARN] Khong tim thay SCD40 tai 0x62! Kiem tra day SDA(%d), SCL(%d)\n",
              I2C_SDA_PIN, I2C_SCL_PIN);
  }

  // 3. Khởi tạo TelemetryBuilder
  telemetry.begin();

  // 4. Hiển thị cấu hình hiện tại & hướng dẫn đổi qua Serial
  printConfigStatus();

  // 5. Kết nối Wi-Fi
  connectWiFi();

  // 6. Cấu hình MQTT Client
  mqttClient.setServer(mqttHost.c_str(), mqttPort);
  mqttClient.setBufferSize(1024);

  // 7. Kết nối MQTT broker lần đầu
  reconnectMQTT();
}

void loop() {
  // Kiểm tra lệnh đổi Wi-Fi / MQTT trực tiếp qua Serial Monitor
  checkSerialInput();

  // Tự động duy trì kết nối Wi-Fi (WiFiMulti) & MQTT
  ensureWiFiConnected();
  reconnectMQTT();
  mqttClient.loop();

  // Chu kỳ đọc cảm biến và gửi telemetry
  if (millis() - lastReport >= REPORT_INTERVAL_SEC * 1000UL) {
    lastReport = millis();

    logPrintf("------------------------------------------\n");

    // 1. Đọc dữ liệu từ DHT22
    float dhtTemp = dht.readTemperature();
    float dhtHum  = dht.readHumidity();

    DhtReading reading;
    reading.temperatureC = dhtTemp;
    reading.humidityPct = dhtHum;
    reading.valid = !isnan(dhtTemp) && !isnan(dhtHum) &&
                    dhtTemp >= -40 && dhtTemp <= 125 &&
                    dhtHum >= 0 && dhtHum <= 100;

    if (reading.valid) {
      logPrintf("[DHT22] Temp: %.1f C | Hum: %.1f %%\n", dhtTemp, dhtHum);
    } else {
      logPrintf("[DHT22] Khong doc duoc du lieu (kiem tra GPIO %d)\n", DHT_PIN);
    }

    // 2. Đọc & hiển thị SCD40 (True CO2 + Temp + Humidity)
    if (!scd40Connected) {
      scd40Connected = scd40Init();
    }

    if (scd40Connected) {
      if (scd40IsDataReady()) {
        uint16_t scdCO2 = 0;
        float scdTemp = 0.0f;
        float scdHum  = 0.0f;

        if (scd40ReadMeasurement(scdCO2, scdTemp, scdHum)) {
          logPrintf("[SCD40] True CO2: %u ppm | Temp: %.1f C | Hum: %.1f %%\n",
                    scdCO2, scdTemp, scdHum);
          // Dự phòng: Nếu DHT22 bị lỏng dây hoặc lỗi, lấy nhiệt độ & độ ẩm của SCD40
          if (!reading.valid && !isnan(scdTemp) && !isnan(scdHum)) {
            reading.temperatureC = scdTemp;
            reading.humidityPct = scdHum;
            reading.valid = true;
            logPrintf("[FALLBACK] Su dung Temp & Hum tu SCD40 cho Telemetry\n");
          }
        } else {
          logPrintf("[SCD40] Loi doc du lieu I2C / CRC!\n");
        }
      } else {
        logPrintf("[SCD40] Dang chuan bi du lieu do...\n");
      }
    } else {
      logPrintf("[SCD40] Chua ket noi (SDA->GPIO%d, SCL->GPIO%d)\n",
                I2C_SDA_PIN, I2C_SCL_PIN);
    }

    /*
    // ==================== [MQ135] TẠM THỜI TẮT ====================
    // 3. Đọc & hiển thị MQ135 (Analog Raw + CO2 ước lượng)
    int mq135Raw = analogRead(MQ135_PIN);
    float mq135Voltage = mq135Raw * (3.3f / 4095.0f);
    float mq135CO2 = calculateMQ135CO2PPM(mq135Raw, dhtTemp, dhtHum);

    if (mq135Raw == 0) {
      logPrintf("[MQ135] Raw: 0 (0.00 V) -> Kiem tra VCC (3V3/5V) va chan AO -> GPIO %d\n", MQ135_PIN);
    } else {
      logPrintf("[MQ135] Est. CO2: %.1f ppm | Raw: %d (%.2f V)\n",
                mq135CO2, mq135Raw, mq135Voltage);
    }
    */

    // 4. Đóng gói JSON Telemetry chuẩn AirSense
    String payload = telemetry.buildJson(
      reading,
      millis(),
      REPORT_INTERVAL_SEC
    );

    logPrintf("[TELEMETRY] Payload JSON:\n%s\n", payload.c_str());

    // 5. Gửi lên MQTT Broker
    if (mqttClient.connected()) {
      String topic = String("airsense/") + DEVICE_ID + "/telemetry";
      bool published = mqttClient.publish(topic.c_str(), payload.c_str());

      if (published) {
        logPrintf("[MQTT] Publish THANH CONG toi topic: %s\n", topic.c_str());
      } else {
        logPrintf("[MQTT] Publish THAT BAI!\n");
      }
    } else {
      logPrintf("[MQTT] Mat ket noi broker; khong the publish tin nhan.\n");
    }

    logPrintf("Status: OK\n");
  }

  delay(10);
}
