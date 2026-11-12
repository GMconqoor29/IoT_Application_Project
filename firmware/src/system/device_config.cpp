#include "system/device_config.h"

#include <Preferences.h>

#include "config.h"

namespace {
constexpr const char *NVS_NAMESPACE = "airsense";
}  // namespace

// Lưu ý: khi khởi động, firmware LUÔN dùng cấu hình trong include/config.h.
// Giá trị lưu qua Serial (NVS) chỉ có hiệu lực cho tới lần khởi động lại.
DeviceConfig DeviceConfig::defaults() {
  return DeviceConfig{
      DEFAULT_WIFI_SSID,
      DEFAULT_WIFI_PASS,
      DEFAULT_MQTT_HOST,
      DEFAULT_MQTT_PORT,
      DEFAULT_MQTT_USER,
      DEFAULT_MQTT_PASS,
  };
}

void DeviceConfig::saveWifi(const String &ssid, const String &pass) {
  Preferences prefs;
  prefs.begin(NVS_NAMESPACE, false);
  prefs.putString("wifi_ssid", ssid);
  prefs.putString("wifi_pass", pass);
  prefs.end();

  wifiSsid = ssid;
  wifiPass = pass;
}

void DeviceConfig::saveMqtt(const String &host, uint16_t port, const String &user, const String &pass) {
  Preferences prefs;
  prefs.begin(NVS_NAMESPACE, false);
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

void DeviceConfig::resetToDefaults() {
  Preferences prefs;
  prefs.begin(NVS_NAMESPACE, false);
  prefs.clear();
  prefs.end();

  *this = defaults();
}

DeviceConfig DeviceConfig::load() {
  DeviceConfig config = defaults();

  Preferences prefs;
  if (!prefs.begin(NVS_NAMESPACE, true)) {
    return config;
  }

  config.wifiSsid = prefs.getString("wifi_ssid", config.wifiSsid);
  config.wifiPass = prefs.getString("wifi_pass", config.wifiPass);

  config.mqttHost = prefs.getString("mqtt_host", config.mqttHost);
  config.mqttPort = prefs.getUShort("mqtt_port", config.mqttPort);
  config.mqttUser = prefs.getString("mqtt_user", config.mqttUser);
  config.mqttPass = prefs.getString("mqtt_pass", config.mqttPass);

  prefs.end();
  return config;
}
