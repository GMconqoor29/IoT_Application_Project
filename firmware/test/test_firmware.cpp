#include "Arduino.h"
#include "ArduinoJson.h"
#include "DHT.h"
#include "Preferences.h"
#include "PubSubClient.h"
#include "WiFi.h"
#include "WiFiClientSecure.h"
#include "WiFiMulti.h"
#include "Wire.h"

#include "config.h"
#include "connectivity/connectivity.h"
#include "sensors/environment_sensor.h"
#include "sensors/scd40.h"
#include "sensors/sensor_types.h"
#include "system/device_config.h"
#include "system/logger.h"
#include "system/serial_commands.h"
#include "telemetry/telemetry_builder.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <map>
#include <string>
#include <vector>

// ================= MOCK GLOBAL DEFINITIONS =================

Stream Serial;
Stream Serial0;
TwoWire Wire;
WiFiClass WiFi;

bool DHT::initialized = false;
float DHT::mockTemperature = 25.0f;
float DHT::mockHumidity = 60.0f;

std::map<std::string, std::map<std::string, std::string>> Preferences::store_;

std::vector<std::pair<std::string, std::string>> WiFiMulti::registeredAps;
int WiFiMulti::runCallCount = 0;
bool WiFiMulti::shouldConnectOnRun = true;

Client *PubSubClient::activeClient = nullptr;
std::string PubSubClient::lastHost;
uint16_t PubSubClient::lastPort = 0;
uint16_t PubSubClient::bufferSize = 256;
std::string PubSubClient::lastClientId;
std::string PubSubClient::lastUser;
std::string PubSubClient::lastPass;
std::string PubSubClient::lastPublishedTopic;
std::string PubSubClient::lastPublishedPayload;
int PubSubClient::publishCount = 0;
bool PubSubClient::isConnected = false;
bool PubSubClient::shouldConnect = true;
int PubSubClient::currentState = -1;

namespace MockArduinoState {
unsigned long currentMillis = 1000;
time_t currentEpoch = 1762583400;  // 2025-11-08T06:30:00Z (>= 1760000000)
bool ntpSyncShouldSucceed = true;
}  // namespace MockArduinoState

// Override libc time() so TelemetryBuilder::getIsoTimestamp() reads deterministic mock epoch
extern "C" time_t time(time_t *tloc) noexcept {
  if (tloc) {
    *tloc = MockArduinoState::currentEpoch;
  }
  return MockArduinoState::currentEpoch;
}

namespace {

int g_testsRun = 0;
int g_testsFailed = 0;

void resetAllMocks() {
  Serial.clearOutput();
  Serial0.clearOutput();
  Wire.resetMock();
  DHT::resetMock();
  Preferences::resetAll();
  WiFi.resetMock();
  WiFiMulti::resetMock();
  PubSubClient::resetMock();
  MockArduinoState::currentMillis = 1000;
  MockArduinoState::currentEpoch = 1762583400;
  MockArduinoState::ntpSyncShouldSucceed = true;
}

#define ASSERT_TRUE(cond)                                                                 \
  do {                                                                                    \
    if (!(cond)) {                                                                        \
      std::fprintf(stderr, "  [FAIL] %s:%d: Expected true: %s\n", __FILE__, __LINE__,     \
                   #cond);                                                                \
      return false;                                                                       \
    }                                                                                     \
  } while (0)

#define ASSERT_FALSE(cond) ASSERT_TRUE(!(cond))

#define ASSERT_EQ(actual, expected)                                                       \
  do {                                                                                    \
    if (!((actual) == (expected))) {                                                      \
      std::fprintf(stderr, "  [FAIL] %s:%d: %s != %s\n", __FILE__, __LINE__, #actual,     \
                   #expected);                                                            \
      return false;                                                                       \
    }                                                                                     \
  } while (0)

#define ASSERT_NEAR(actual, expected, tol)                                                \
  do {                                                                                    \
    if (std::fabs(static_cast<double>(actual) - static_cast<double>(expected)) > (tol)) { \
      std::fprintf(stderr, "  [FAIL] %s:%d: |%.4f - %.4f| > %.4f\n", __FILE__, __LINE__,  \
                   static_cast<double>(actual), static_cast<double>(expected),            \
                   static_cast<double>(tol));                                             \
      return false;                                                                       \
    }                                                                                     \
  } while (0)

void runTest(const char *name, bool (*fn)()) {
  resetAllMocks();
  g_testsRun++;
  if (fn()) {
    std::printf("[PASS] %s\n", name);
  } else {
    g_testsFailed++;
    std::printf("[FAIL] %s\n", name);
  }
}

// ================= 1. LOGGER TESTS =================

bool test_logger_dual_serial_output() {
  logPrintf("Hello %s #%d\n", "AirSense", 42);
  ASSERT_EQ(Serial.output(), std::string("Hello AirSense #42\n"));
  ASSERT_EQ(Serial0.output(), std::string("Hello AirSense #42\n"));
  return true;
}

// ================= 2. SCD40 DRIVER TESTS =================

bool test_scd40_begin_and_nack_handling() {
  Scd40 sensor(SCD40_ADDR);
  ASSERT_FALSE(sensor.isConnected());

  // 1. Successful begin() sends STOP (0x3F86) then START (0x21B1)
  ASSERT_TRUE(sensor.begin());
  ASSERT_TRUE(sensor.isConnected());
  ASSERT_EQ(Wire.sentCommands.size(), static_cast<size_t>(2));
  ASSERT_EQ(Wire.sentCommands[0], static_cast<uint16_t>(0x3F86));
  ASSERT_EQ(Wire.sentCommands[1], static_cast<uint16_t>(0x21B1));

  // 2. NACK on I2C returns false and marks disconnected
  Wire.resetMock();
  Wire.defaultEndTransmissionResult = 2;  // NACK on address
  ASSERT_FALSE(sensor.begin());
  ASSERT_FALSE(sensor.isConnected());
  return true;
}

bool test_scd40_data_ready_and_measurement_crc() {
  Scd40 sensor(SCD40_ADDR);
  ASSERT_TRUE(sensor.begin());

  // 1. Lower 11 bits == 0 -> data NOT ready
  Wire.queueWordWithCrc(0x8000);
  ASSERT_FALSE(sensor.isDataReady());

  // 2. Lower 11 bits != 0 -> data ready
  Wire.queueWordWithCrc(0x8006);
  ASSERT_TRUE(sensor.isDataReady());

  // 3. Valid measurement: CO2 = 850 ppm, Temp ~ 25.0C, Hum ~ 50.0%
  const uint16_t rawTemp = static_cast<uint16_t>((25.0f + 45.0f) / 175.0f * 65535.0f + 0.5f);
  const uint16_t rawHum = static_cast<uint16_t>(50.0f / 100.0f * 65535.0f + 0.5f);
  Wire.queueWordWithCrc(850);
  Wire.queueWordWithCrc(rawTemp);
  Wire.queueWordWithCrc(rawHum);

  Scd40Reading out{};
  ASSERT_TRUE(sensor.readMeasurement(out));
  ASSERT_EQ(out.co2Ppm, static_cast<uint16_t>(850));
  ASSERT_NEAR(out.temperatureC, 25.0f, 0.05f);
  ASSERT_NEAR(out.humidityPct, 50.0f, 0.05f);

  // 4. Corrupted CRC byte must be rejected
  Wire.queueWordWithCrc(900);
  Wire.queueWordWithCrc(rawTemp, /*corruptCrc=*/true);
  Wire.queueWordWithCrc(rawHum);
  ASSERT_FALSE(sensor.readMeasurement(out));
  return true;
}

// ================= 3. ENVIRONMENT SENSOR TESTS =================

void queueValidScd40Sample(uint16_t co2Ppm, float tempC, float humPct) {
  Wire.queueWordWithCrc(0x8001);  // data ready = true
  const uint16_t rawTemp = static_cast<uint16_t>((tempC + 45.0f) / 175.0f * 65535.0f + 0.5f);
  const uint16_t rawHum = static_cast<uint16_t>(humPct / 100.0f * 65535.0f + 0.5f);
  Wire.queueWordWithCrc(co2Ppm);
  Wire.queueWordWithCrc(rawTemp);
  Wire.queueWordWithCrc(rawHum);
}

bool test_environment_sensor_dht22_primary_with_scd40_co2() {
  EnvironmentSensor env;
  env.begin();
  ASSERT_TRUE(DHT::initialized);
  ASSERT_EQ(Wire.lastSda, I2C_SDA_PIN);
  ASSERT_EQ(Wire.lastScl, I2C_SCL_PIN);

  DHT::mockTemperature = 26.4f;
  DHT::mockHumidity = 63.2f;
  queueValidScd40Sample(720, 27.1f, 61.0f);

  EnvironmentReading reading = env.read();
  ASSERT_TRUE(reading.temperatureHumidityValid);
  ASSERT_EQ(reading.temperatureHumiditySource, EnvironmentSource::DHT22);
  ASSERT_NEAR(reading.temperatureC, 26.4f, 0.01f);
  ASSERT_NEAR(reading.humidityPct, 63.2f, 0.01f);
  ASSERT_TRUE(reading.co2Valid);
  ASSERT_EQ(reading.co2Ppm, static_cast<uint16_t>(720));
  return true;
}

bool test_environment_sensor_scd40_fallback_when_dht22_fails() {
  EnvironmentSensor env;
  env.begin();

  // Simulate disconnected/failed DHT22 returning NAN
  DHT::mockTemperature = NAN;
  DHT::mockHumidity = NAN;
  queueValidScd40Sample(640, 24.5f, 55.0f);

  EnvironmentReading reading = env.read();
  ASSERT_TRUE(reading.temperatureHumidityValid);
  ASSERT_EQ(reading.temperatureHumiditySource, EnvironmentSource::SCD40);
  ASSERT_NEAR(reading.temperatureC, 24.5f, 0.05f);
  ASSERT_NEAR(reading.humidityPct, 55.0f, 0.05f);
  ASSERT_TRUE(reading.co2Valid);
  ASSERT_EQ(reading.co2Ppm, static_cast<uint16_t>(640));
  return true;
}

bool test_environment_sensor_handles_both_sensors_failing() {
  Wire.defaultEndTransmissionResult = 2;  // SCD40 not connected
  EnvironmentSensor env;
  env.begin();

  DHT::mockTemperature = 150.0f;  // Out of valid [-40, 125] range
  DHT::mockHumidity = -5.0f;      // Out of valid [0, 100] range

  EnvironmentReading reading = env.read();
  ASSERT_FALSE(reading.temperatureHumidityValid);
  ASSERT_EQ(reading.temperatureHumiditySource, EnvironmentSource::NONE);
  ASSERT_FALSE(reading.co2Valid);
  ASSERT_EQ(reading.co2Ppm, static_cast<uint16_t>(0));
  return true;
}

// ================= 4. DEVICE CONFIG (NVS) TESTS =================

bool test_device_config_load_save_and_reset() {
  DeviceConfig cfg = DeviceConfig::load();
  ASSERT_EQ(cfg.wifiSsid, String(DEFAULT_WIFI_SSID));
  ASSERT_EQ(cfg.mqttHost, String(DEFAULT_MQTT_HOST));
  ASSERT_EQ(cfg.mqttPort, static_cast<uint16_t>(DEFAULT_MQTT_PORT));

  // Save custom Wi-Fi and MQTT settings to NVS
  cfg.saveWifi("LabNetwork", "LabPass123");
  cfg.saveMqtt("broker.emqx.io", 8883, "airsense_user", "secret_key");

  // Simulate reboot by calling DeviceConfig::load() into a fresh struct
  DeviceConfig reloaded = DeviceConfig::load();
  ASSERT_EQ(reloaded.wifiSsid, String("LabNetwork"));
  ASSERT_EQ(reloaded.wifiPass, String("LabPass123"));
  ASSERT_EQ(reloaded.mqttHost, String("broker.emqx.io"));
  ASSERT_EQ(reloaded.mqttPort, static_cast<uint16_t>(8883));
  ASSERT_EQ(reloaded.mqttUser, String("airsense_user"));
  ASSERT_EQ(reloaded.mqttPass, String("secret_key"));

  // Reset to defaults clears NVS and restores compile-time defaults
  reloaded.resetToDefaults();
  DeviceConfig afterReset = DeviceConfig::load();
  ASSERT_EQ(afterReset.wifiSsid, String(DEFAULT_WIFI_SSID));
  ASSERT_EQ(afterReset.mqttHost, String(DEFAULT_MQTT_HOST));
  ASSERT_EQ(afterReset.mqttPort, static_cast<uint16_t>(DEFAULT_MQTT_PORT));
  return true;
}

// ================= 5. CONNECTIVITY TESTS =================

bool test_connectivity_plain_tcp_port_1883() {
  DeviceConfig cfg = DeviceConfig::defaults();
  cfg.mqttHost = "192.168.1.94";
  cfg.mqttPort = 1883;

  Connectivity net(cfg);
  net.begin();

  ASSERT_TRUE(net.wifiConnected());
  ASSERT_TRUE(net.mqttConnected());
  ASSERT_TRUE(PubSubClient::activeClient != nullptr);
  ASSERT_FALSE(PubSubClient::activeClient->isSecureClient());
  ASSERT_EQ(PubSubClient::lastHost, std::string("192.168.1.94"));
  ASSERT_EQ(PubSubClient::lastPort, static_cast<uint16_t>(1883));

  ASSERT_TRUE(net.publish("airsense/AIRSENSE-001/telemetry", "{\"test\":1}"));
  ASSERT_EQ(PubSubClient::publishCount, 1);
  ASSERT_EQ(PubSubClient::lastPublishedTopic, std::string("airsense/AIRSENSE-001/telemetry"));
  return true;
}

bool test_connectivity_tls_port_8883_and_ntp_requirement() {
  DeviceConfig cfg = DeviceConfig::defaults();
  cfg.mqttHost = "emqx.cloud.local";
  cfg.mqttPort = 8883;
  cfg.mqttUser = "tls_user";
  cfg.mqttPass = "tls_pass";

  // 1. If NTP fails, TLS connection must be skipped
  MockArduinoState::ntpSyncShouldSucceed = false;
  Connectivity net(cfg);
  net.begin();
  ASSERT_TRUE(net.wifiConnected());
  ASSERT_FALSE(net.mqttConnected());

  // 2. Once NTP succeeds, TLS connection proceeds using WiFiClientSecure
  MockArduinoState::ntpSyncShouldSucceed = true;
  MockArduinoState::currentMillis += 6000;
  net.loop();

  ASSERT_TRUE(net.mqttConnected());
  ASSERT_TRUE(PubSubClient::activeClient != nullptr);
  ASSERT_TRUE(PubSubClient::activeClient->isSecureClient());
  ASSERT_EQ(PubSubClient::lastPort, static_cast<uint16_t>(8883));
  ASSERT_EQ(PubSubClient::lastUser, std::string("tls_user"));
  return true;
}

bool test_connectivity_wifi_multi_failover() {
  DeviceConfig cfg = DeviceConfig::defaults();
  WiFi.shouldConnectOnBegin = false;  // Direct WiFi.begin() fails
  WiFi.currentStatus = WL_DISCONNECTED;
  WiFiMulti::shouldConnectOnRun = true;  // WiFiMulti succeeds

  Connectivity net(cfg);
  net.begin();

  ASSERT_EQ(WiFiMulti::runCallCount, 1);
  ASSERT_TRUE(net.wifiConnected());
  return true;
}

// ================= 6. SERIAL COMMANDS TESTS =================

bool test_serial_commands_wifi_mqtt_and_reset() {
  DeviceConfig cfg = DeviceConfig::defaults();
  Connectivity net(cfg);
  net.begin();
  SerialCommands commands(cfg, net);

  // 1. Wi-Fi command with spaces in password
  Serial.injectLine("wifi Hotspot_5G my Pass With Spaces");
  commands.poll();
  ASSERT_EQ(cfg.wifiSsid, String("Hotspot_5G"));
  ASSERT_EQ(cfg.wifiPass, String("my Pass With Spaces"));

  // 2. MQTT command with host, port, user, pass via UART0 (Serial0)
  Serial0.injectLine("mqtt 10.10.0.42 8883 admin_user secret_pw");
  commands.poll();
  ASSERT_EQ(cfg.mqttHost, String("10.10.0.42"));
  ASSERT_EQ(cfg.mqttPort, static_cast<uint16_t>(8883));
  ASSERT_EQ(cfg.mqttUser, String("admin_user"));
  ASSERT_EQ(cfg.mqttPass, String("secret_pw"));
  ASSERT_TRUE(PubSubClient::activeClient->isSecureClient());

  // 3. MQTT command with host only (defaults to DEFAULT_MQTT_PORT)
  Serial.injectLine("mqtt 192.168.1.200");
  commands.poll();
  ASSERT_EQ(cfg.mqttHost, String("192.168.1.200"));
  ASSERT_EQ(cfg.mqttPort, static_cast<uint16_t>(DEFAULT_MQTT_PORT));
  ASSERT_EQ(cfg.mqttUser, String(""));
  const bool expectedSecureDefault = (DEFAULT_MQTT_PORT == 8883 || DEFAULT_MQTT_PORT == 8884);
  ASSERT_EQ(PubSubClient::activeClient->isSecureClient(), expectedSecureDefault);

  // 4. MQTT command switching to plain TCP (port 1883)
  Serial.injectLine("mqtt 192.168.1.200 1883");
  commands.poll();
  ASSERT_EQ(cfg.mqttPort, static_cast<uint16_t>(1883));
  ASSERT_FALSE(PubSubClient::activeClient->isSecureClient());

  // 5. Reset command restores config.h defaults
  Serial.injectLine("reset");
  commands.poll();
  ASSERT_EQ(cfg.wifiSsid, String(DEFAULT_WIFI_SSID));
  ASSERT_EQ(cfg.mqttHost, String(DEFAULT_MQTT_HOST));
  return true;
}

// ================= 7. TELEMETRY BUILDER TESTS =================

bool test_telemetry_builder_boot_sequence_and_synced_json() {
  TelemetryBuilder builder(DEVICE_ID);
  builder.begin();

  EnvironmentReading reading;
  reading.temperatureC = 25.5f;
  reading.humidityPct = 62.0f;
  reading.temperatureHumidityValid = true;
  reading.temperatureHumiditySource = EnvironmentSource::DHT22;
  reading.co2Ppm = 680;
  reading.co2Valid = true;

  String json1 = builder.buildJson(reading, 10000, 30);
  String json2 = builder.buildJson(reading, 40000, 30);

  // Boot 1 starts at sequence 100000 and increments monotonically
  ASSERT_TRUE(json1.str().find("\"messageId\":\"AIRSENSE-001:b7f3a901:100000\"") !=
              std::string::npos);
  ASSERT_TRUE(json1.str().find("\"sequence\":100000") != std::string::npos);
  ASSERT_TRUE(json1.str().find("\"timeQuality\":\"SYNCED\"") != std::string::npos);
  ASSERT_TRUE(json1.str().find("\"measuredAt\":\"2025-11-08T06:30:00Z\"") != std::string::npos);
  ASSERT_TRUE(json1.str().find("\"sht30\":\"VALID\"") != std::string::npos);

  ASSERT_TRUE(json2.str().find("\"messageId\":\"AIRSENSE-001:b7f3a901:100001\"") !=
              std::string::npos);
  ASSERT_TRUE(json2.str().find("\"sequence\":100001") != std::string::npos);

  // Simulate second reboot: NVS boot_count increments to 2 -> sequence starts at 200000
  TelemetryBuilder rebootedBuilder(DEVICE_ID);
  rebootedBuilder.begin();
  String jsonAfterReboot = rebootedBuilder.buildJson(reading, 5000, 30);
  ASSERT_TRUE(jsonAfterReboot.str().find("\"sequence\":200000") != std::string::npos);
  return true;
}

bool test_telemetry_builder_unsynced_and_sensor_fault() {
  MockArduinoState::currentEpoch = 100;  // Unsynced clock (< 1760000000)

  TelemetryBuilder builder(DEVICE_ID);
  builder.begin();

  EnvironmentReading invalidReading;
  invalidReading.temperatureHumidityValid = false;

  String json = builder.buildJson(invalidReading, 15000, 30);
  ASSERT_TRUE(json.str().find("\"measuredAt\":null") != std::string::npos);
  ASSERT_TRUE(json.str().find("\"timeQuality\":\"UNSYNCED\"") != std::string::npos);
  ASSERT_TRUE(json.str().find("\"temperatureC\":null") != std::string::npos);
  ASSERT_TRUE(json.str().find("\"humidityPct\":null") != std::string::npos);
  ASSERT_TRUE(json.str().find("\"sht30\":\"INVALID\"") != std::string::npos);
  return true;
}

}  // namespace

int main(int argc, char **argv) {
  if (argc > 1 && std::strcmp(argv[1], "--dump-synced-json") == 0) {
    resetAllMocks();
    TelemetryBuilder builder(DEVICE_ID);
    builder.begin();
    EnvironmentReading reading;
    reading.temperatureC = 25.5f;
    reading.humidityPct = 62.0f;
    reading.temperatureHumidityValid = true;
    reading.temperatureHumiditySource = EnvironmentSource::DHT22;
    std::cout << builder.buildJson(reading, 30000, 30).str() << std::endl;
    return 0;
  }

  if (argc > 1 && std::strcmp(argv[1], "--dump-unsynced-json") == 0) {
    resetAllMocks();
    MockArduinoState::currentEpoch = 45;
    TelemetryBuilder builder(DEVICE_ID);
    builder.begin();
    EnvironmentReading reading;
    reading.temperatureHumidityValid = false;
    std::cout << builder.buildJson(reading, 12000, 30).str() << std::endl;
    return 0;
  }

  std::printf("==========================================\n");
  std::printf("   AirSense Firmware Unit Test Suite\n");
  std::printf("==========================================\n");

  runTest("Logger: dual USB Serial + UART0 output", test_logger_dual_serial_output);
  runTest("SCD40: I2C initialization & NACK handling", test_scd40_begin_and_nack_handling);
  runTest("SCD40: data ready status & CRC-8 measurement validation",
          test_scd40_data_ready_and_measurement_crc);
  runTest("EnvironmentSensor: DHT22 primary + SCD40 CO2 acquisition",
          test_environment_sensor_dht22_primary_with_scd40_co2);
  runTest("EnvironmentSensor: automatic SCD40 fallback when DHT22 fails",
          test_environment_sensor_scd40_fallback_when_dht22_fails);
  runTest("EnvironmentSensor: out-of-range & total sensor failure handling",
          test_environment_sensor_handles_both_sensors_failing);
  runTest("DeviceConfig: NVS load, save, and reset across reboots",
          test_device_config_load_save_and_reset);
  runTest("Connectivity: plain TCP (WiFiClient) on port 1883",
          test_connectivity_plain_tcp_port_1883);
  runTest("Connectivity: TLS (WiFiClientSecure) on port 8883 & NTP guard",
          test_connectivity_tls_port_8883_and_ntp_requirement);
  runTest("Connectivity: WiFiMulti automatic failover", test_connectivity_wifi_multi_failover);
  runTest("SerialCommands: wifi, mqtt, and reset CLI parsing",
          test_serial_commands_wifi_mqtt_and_reset);
  runTest("TelemetryBuilder: NVS boot sequence & SYNCED JSON payload",
          test_telemetry_builder_boot_sequence_and_synced_json);
  runTest("TelemetryBuilder: UNSYNCED clock & invalid sensor JSON payload",
          test_telemetry_builder_unsynced_and_sensor_fault);

  std::printf("------------------------------------------\n");
  std::printf("Result: %d/%d tests passed.\n", g_testsRun - g_testsFailed, g_testsRun);
  std::printf("==========================================\n");

  return g_testsFailed == 0 ? 0 : 1;
}
