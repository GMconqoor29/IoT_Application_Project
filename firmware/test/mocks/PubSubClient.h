#pragma once

#include "WiFi.h"
#include <cstdint>

class PubSubClient {
public:
  explicit PubSubClient(Client &client) { activeClient = &client; }

  PubSubClient &setClient(Client &client) {
    activeClient = &client;
    return *this;
  }

  PubSubClient &setServer(const char *domain, uint16_t port) {
    lastHost = domain ? domain : "";
    lastPort = port;
    return *this;
  }

  bool setBufferSize(uint16_t size) {
    bufferSize = size;
    return true;
  }

  bool connect(const char *id) {
    lastClientId = id ? id : "";
    lastUser = "";
    lastPass = "";
    if (shouldConnect) {
      isConnected = true;
      currentState = 0;
    }
    return isConnected;
  }

  bool connect(const char *id, const char *user, const char *pass) {
    lastClientId = id ? id : "";
    lastUser = user ? user : "";
    lastPass = pass ? pass : "";
    if (shouldConnect) {
      isConnected = true;
      currentState = 0;
    }
    return isConnected;
  }

  void disconnect() {
    isConnected = false;
    currentState = -1;
  }

  bool connected() { return isConnected; }
  bool loop() { return isConnected; }

  bool publish(const char *topic, const char *payload) {
    if (!isConnected) {
      return false;
    }
    lastPublishedTopic = topic ? topic : "";
    lastPublishedPayload = payload ? payload : "";
    publishCount++;
    return true;
  }

  int state() const { return currentState; }

  static void resetMock() {
    activeClient = nullptr;
    lastHost = "";
    lastPort = 0;
    bufferSize = 256;
    lastClientId = "";
    lastUser = "";
    lastPass = "";
    lastPublishedTopic = "";
    lastPublishedPayload = "";
    publishCount = 0;
    isConnected = false;
    shouldConnect = true;
    currentState = -1;
  }

  static Client *activeClient;
  static std::string lastHost;
  static uint16_t lastPort;
  static uint16_t bufferSize;
  static std::string lastClientId;
  static std::string lastUser;
  static std::string lastPass;
  static std::string lastPublishedTopic;
  static std::string lastPublishedPayload;
  static int publishCount;
  static bool isConnected;
  static bool shouldConnect;
  static int currentState;
};
