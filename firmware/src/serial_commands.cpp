#include "serial_commands.h"

#include "config.h"
#include "logger.h"

namespace {
constexpr int MAX_MQTT_ARGS = 4;

// Tách chuỗi theo dấu cách, tối đa maxTokens phần tử. Trả về số token đọc được.
int splitBySpace(String text, String *tokens, int maxTokens) {
  int count = 0;
  text.trim();
  while (text.length() > 0 && count < maxTokens) {
    const int space = text.indexOf(' ');
    if (space < 0) {
      tokens[count++] = text;
      break;
    }
    tokens[count++] = text.substring(0, space);
    text = text.substring(space + 1);
    text.trim();
  }
  return count;
}

const char *connectionLabel(bool connected) {
  return connected ? "CONNECTED" : "DISCONNECTED";
}
}  // namespace

SerialCommands::SerialCommands(DeviceConfig &cfg, Connectivity &network)
    : config(cfg), net(network) {}

void SerialCommands::poll() {
  pollStream(Serial);
  pollStream(Serial0);
}

void SerialCommands::pollStream(Stream &stream) {
  while (stream.available()) {
    handleLine(stream.readStringUntil('\n'));
  }
}

void SerialCommands::printStatus() {
  const bool wifiUp = net.wifiConnected();

  logPrintf("\n========== CAU HINH HIEN TAI ==========\n");
  logPrintf("Device ID : %s\n", DEVICE_ID);
  logPrintf("Wi-Fi NVS : %s (%s)\n", config.wifiSsid.c_str(), connectionLabel(wifiUp));
  if (wifiUp) {
    logPrintf("Active AP : %s | IP: %s\n",
              WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
  }
  logPrintf("MQTT Host : %s:%u (%s)\n",
            config.mqttHost.c_str(),
            static_cast<unsigned>(config.mqttPort),
            connectionLabel(net.mqttConnected()));
  logPrintf("MQTT Auth : %s\n", config.mqttUser.length() > 0 ? config.mqttUser.c_str() : "(anonymous)");
  logPrintf("---------------------------------------\n");
  logPrintf("Lenh doi cau hinh qua Serial Monitor:\n");
  logPrintf("  wifi <SSID> <PASSWORD>\n");
  logPrintf("  mqtt <IP_HOST> [PORT] [USER] [PASS]\n");
  logPrintf("  status  (xem trang thai hien tai)\n");
  logPrintf("  reset   (khoi phuc mac dinh trong config.h)\n");
  logPrintf("=======================================\n");
}

void SerialCommands::handleLine(String line) {
  line.trim();
  if (line.length() == 0) {
    return;
  }

  if (line.equalsIgnoreCase("status") || line.equalsIgnoreCase("help") || line.equals("?")) {
    printStatus();
  } else if (line.equalsIgnoreCase("reset")) {
    handleReset();
  } else if (line.startsWith("wifi ")) {
    handleWifi(line.substring(5));
  } else if (line.startsWith("mqtt ")) {
    handleMqtt(line.substring(5));
  } else {
    logPrintf("[WARN] Lenh khong hop le: '%s'. Go 'help' de xem huong dan.\n", line.c_str());
  }
}

void SerialCommands::handleReset() {
  config.resetToDefaults();
  logPrintf("[CONFIG] Da khoi phuc cau hinh mac dinh tu config.h!\n");
  printStatus();
  net.reconnectAll();
}

// wifi <SSID> <PASSWORD>  (mật khẩu được phép chứa dấu cách)
void SerialCommands::handleWifi(String args) {
  args.trim();
  const int space = args.indexOf(' ');
  if (space <= 0) {
    logPrintf("[ERR] Cu phap: wifi <SSID> <PASSWORD>\n");
    return;
  }

  const String ssid = args.substring(0, space);
  String pass = args.substring(space + 1);
  pass.trim();

  config.saveWifi(ssid, pass);
  net.addAccessPoint(ssid, pass);
  logPrintf("[CONFIG] Da luu Wi-Fi moi vao Flash NVS: SSID='%s'\n", config.wifiSsid.c_str());
  net.reconnectAll();
}

// mqtt <HOST> [PORT] [USER] [PASS]
void SerialCommands::handleMqtt(String args) {
  String tokens[MAX_MQTT_ARGS];
  const int count = splitBySpace(args, tokens, MAX_MQTT_ARGS);

  if (count < 1 || tokens[0].length() == 0) {
    logPrintf("[ERR] Cu phap: mqtt <IP_HOST> [PORT] [USER] [PASS]\n");
    return;
  }

  uint16_t port = (count >= 2) ? static_cast<uint16_t>(tokens[1].toInt()) : DEFAULT_MQTT_PORT;
  if (port == 0) {
    port = DEFAULT_MQTT_PORT;
  }

  config.saveMqtt(tokens[0], port, tokens[2], tokens[3]);  // token thiếu = chuỗi rỗng
  logPrintf("[CONFIG] Da luu MQTT moi vao Flash NVS: %s:%u (user=%s)\n",
            config.mqttHost.c_str(),
            static_cast<unsigned>(config.mqttPort),
            config.mqttUser.length() ? config.mqttUser.c_str() : "anonymous");
  net.reconnectMqttNow();
}
