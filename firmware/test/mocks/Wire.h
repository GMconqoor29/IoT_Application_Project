#pragma once

#include <cstdint>
#include <vector>

class TwoWire {
public:
  void begin(int sda = -1, int scl = -1) {
    lastSda = sda;
    lastScl = scl;
  }

  void beginTransmission(uint8_t address) {
    lastAddress = address;
    currentTx.clear();
  }

  size_t write(uint8_t byte) {
    currentTx.push_back(byte);
    return 1;
  }

  uint8_t endTransmission() {
    if (!currentTx.empty()) {
      uint16_t cmd = 0;
      if (currentTx.size() >= 2) {
        cmd = (static_cast<uint16_t>(currentTx[0]) << 8) | currentTx[1];
      }
      sentCommands.push_back(cmd);
    }
    if (!endTransmissionResults.empty()) {
      uint8_t res = endTransmissionResults.front();
      endTransmissionResults.erase(endTransmissionResults.begin());
      return res;
    }
    return defaultEndTransmissionResult;
  }

  uint8_t requestFrom(uint8_t /*address*/, uint8_t quantity) {
    if (forceShortRead) {
      return 0;
    }
    return rxBuffer.size() >= quantity ? quantity : static_cast<uint8_t>(rxBuffer.size());
  }

  int read() {
    if (rxBuffer.empty()) {
      return -1;
    }
    uint8_t b = rxBuffer.front();
    rxBuffer.erase(rxBuffer.begin());
    return b;
  }

  void resetMock() {
    lastSda = -1;
    lastScl = -1;
    lastAddress = 0;
    currentTx.clear();
    sentCommands.clear();
    rxBuffer.clear();
    endTransmissionResults.clear();
    defaultEndTransmissionResult = 0;
    forceShortRead = false;
  }

  // Compute Sensirion CRC-8 (poly 0x31, init 0xFF) and push 16-bit word + CRC to rxBuffer
  void queueWordWithCrc(uint16_t word, bool corruptCrc = false) {
    uint8_t msb = static_cast<uint8_t>(word >> 8);
    uint8_t lsb = static_cast<uint8_t>(word & 0xFF);
    uint8_t crc = 0xFF;
    uint8_t bytes[2] = {msb, lsb};
    for (uint8_t b : bytes) {
      crc ^= b;
      for (int i = 0; i < 8; i++) {
        crc = (crc & 0x80) ? static_cast<uint8_t>((crc << 1) ^ 0x31)
                           : static_cast<uint8_t>(crc << 1);
      }
    }
    if (corruptCrc) {
      crc ^= 0xFF;
    }
    rxBuffer.push_back(msb);
    rxBuffer.push_back(lsb);
    rxBuffer.push_back(crc);
  }

  int lastSda = -1;
  int lastScl = -1;
  uint8_t lastAddress = 0;
  std::vector<uint8_t> currentTx;
  std::vector<uint16_t> sentCommands;
  std::vector<uint8_t> rxBuffer;
  std::vector<uint8_t> endTransmissionResults;
  uint8_t defaultEndTransmissionResult = 0;
  bool forceShortRead = false;
};

extern TwoWire Wire;
