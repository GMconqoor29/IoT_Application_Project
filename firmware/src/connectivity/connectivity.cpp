#include "connectivity/connectivity.h"

#include "config.h"
#include "system/logger.h"

namespace {
constexpr uint32_t WIFI_DIRECT_TIMEOUT_MS   = 10000;
constexpr uint32_t WIFI_MULTI_TIMEOUT_MS    = 8000;
constexpr uint32_t WIFI_RECONNECT_TIMEOUT_MS = 5000;
constexpr uint32_t WIFI_RETRY_INTERVAL_MS   = 15000;
constexpr uint32_t MQTT_RETRY_INTERVAL_MS   = 5000;
constexpr uint16_t MQTT_BUFFER_SIZE         = 1024;

const char *const NTP_SERVER_1 = "pool.ntp.org";
const char *const NTP_SERVER_2 = "time.google.com";

// Chỉ áp dụng giảm công suất phát khi WIFI_TX_POWER được định nghĩa trong config.h.
void applyTxPower() {
#ifdef WIFI_TX_POWER
  WiFi.setTxPower(WIFI_TX_POWER);
#endif
}
const char *mqttStateDescription(int state) {
  switch (state) {
    case -4: return "Connection timeout";
    case -3: return "Connection lost";
    case -2: return "TCP/TLS connection failed";
    case -1: return "Disconnected";
    case  0: return "Connected";
    case  1: return "Protocol error";
    case  2: return "Client ID rejected";
    case  3: return "Broker unavailable";
    case  4: return "Bad credentials";
    case  5: return "Unauthorized";
    default: return "Unknown MQTT state";
  }
}
}  // namespace

Connectivity::Connectivity(DeviceConfig &cfg) : config(cfg), mqttClient(wifiClient) {}

void Connectivity::begin() {
  registerAccessPoints();
  connectWiFi();

  wifiClient.setCACert(MQTT_ROOT_CA);

  mqttClient.setBufferSize(MQTT_BUFFER_SIZE);
  ensureMqttConnected();
}

void Connectivity::loop() {
  ensureWiFiConnected();
  ensureMqttConnected();
  mqttClient.loop();
}

bool Connectivity::wifiConnected() const {
  return WiFi.status() == WL_CONNECTED;
}

bool Connectivity::mqttConnected() {
  return mqttClient.connected();
}

bool Connectivity::publish(const String &topic, const String &payload) {
  return mqttClient.publish(topic.c_str(), payload.c_str());
}

void Connectivity::addAccessPoint(const String &ssid, const String &pass) {
  if (ssid.length() > 0) {
    wifiMulti.addAP(ssid.c_str(), pass.c_str());
  }
}

void Connectivity::reconnectAll() {
  mqttClient.disconnect();
  WiFi.disconnect(true);
  delay(100);
  connectWiFi();
  ensureMqttConnected();
}

void Connectivity::reconnectMqttNow() {
  mqttClient.disconnect();
  lastMqttAttempt = 0;
  ensureMqttConnected();
}

// Thứ tự ưu tiên: mạng hiện tại -> mạng mặc định trong config.h -> các mạng dự phòng.
void Connectivity::registerAccessPoints() {
  addAccessPoint(config.wifiSsid, config.wifiPass);
  if (config.wifiSsid != DEFAULT_WIFI_SSID) {
    addAccessPoint(DEFAULT_WIFI_SSID, DEFAULT_WIFI_PASS);
  }
  addAccessPoint(BACKUP_WIFI_1_SSID, BACKUP_WIFI_1_PASS);
  addAccessPoint(BACKUP_WIFI_2_SSID, BACKUP_WIFI_2_PASS);
}

void Connectivity::connectWiFi() {
  lastWifiAttempt = millis();
  logPrintf("\n[WiFi] Dang quet & ket noi Wi-Fi (Uu tien: %s) ...\n", config.wifiSsid.c_str());

  WiFi.mode(WIFI_STA);
  applyTxPower();

  // Thử kết nối trực tiếp vào mạng ưu tiên trước, sau đó dùng WiFiMulti quét các mạng dự phòng.
  WiFi.begin(config.wifiSsid.c_str(), config.wifiPass.c_str());
  applyTxPower();

  const unsigned long start = millis();
  while (!wifiConnected() && millis() - start < WIFI_DIRECT_TIMEOUT_MS) {
    delay(500);
    logPrintf(".");
  }

  if (!wifiConnected()) {
    logPrintf("\n[WiFi] Chuyen sang quet danh sach WiFiMulti...\n");
    wifiMulti.run(WIFI_MULTI_TIMEOUT_MS);
  }

  if (wifiConnected()) {
    logPrintf("\n[WiFi] Ket noi thanh cong toi '%s'! IP ESP32: %s\n",
              WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
    synchronizeTime();
  } else {
    logPrintf("\n[WiFi] Ket noi THAT BAI (status=%d). Go lenh 'wifi <SSID> <PASS>' de doi mang.\n",
              WiFi.status());
  }
}

void Connectivity::ensureWiFiConnected() {
  if (wifiConnected() || millis() - lastWifiAttempt < WIFI_RETRY_INTERVAL_MS) {
    return;
  }
  lastWifiAttempt = millis();

  logPrintf("[WiFi] Mat ket noi! Dang quet lai qua WiFiMulti...\n");
  if (wifiMulti.run(WIFI_RECONNECT_TIMEOUT_MS) == WL_CONNECTED) {
    logPrintf("[WiFi] Da ket noi lai toi '%s'! IP: %s\n",
              WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
  }
}

void Connectivity::ensureMqttConnected() {
  if (!wifiConnected() || mqttClient.connected()) {
    return;
  }
  if (millis() - lastMqttAttempt < MQTT_RETRY_INTERVAL_MS) {
    return;
  }
  if (!timeSynced) {
    logPrintf("[MQTT] NTP chua dong bo. Thu dong bo lai...\n");

    if (!synchronizeTime()) {
      lastMqttAttempt = millis();
      logPrintf("[MQTT] Bo qua ket noi TLS do chua co thoi gian hop le.\n");
      return;
    }
  }
  lastMqttAttempt = millis();

  mqttClient.setServer(config.mqttHost.c_str(), config.mqttPort);
  logPrintf("[MQTT] Dang ket noi toi broker %s:%u ... ",
            config.mqttHost.c_str(), static_cast<unsigned>(config.mqttPort));

  const String clientId = String("AirSense-") + DEVICE_ID;
  const bool connected = config.mqttUser.length() > 0
      ? mqttClient.connect(clientId.c_str(), config.mqttUser.c_str(), config.mqttPass.c_str())
      : mqttClient.connect(clientId.c_str());

  if (connected) {
    logPrintf("THANH CONG!\n");
  } else {
    logPrintf("THAT BAI, rc = %d (%s)\n", mqttClient.state(), mqttStateDescription(mqttClient.state()));
  }
}

bool Connectivity::synchronizeTime() {
  timeSynced = false;
  logPrintf("[NTP] Dang dong bo thoi gian...\n");

  configTime(0, 0, NTP_SERVER_1, NTP_SERVER_2);

  struct tm timeInfo;
  const unsigned long start = millis();

  while (!getLocalTime(&timeInfo, 1000)) {
    if (millis() - start >= 15000) {
      logPrintf("[NTP] Timeout! Chua dong bo duoc thoi gian.\n");
      return false;
    }

    delay(250);
  }

  logPrintf("[NTP] Dong bo thanh cong: %04d-%02d-%02d %02d:%02d:%02d UTC\n",
            timeInfo.tm_year + 1900,
            timeInfo.tm_mon + 1,
            timeInfo.tm_mday,
            timeInfo.tm_hour,
            timeInfo.tm_min,
            timeInfo.tm_sec);

  timeSynced = true;
  return true;
}