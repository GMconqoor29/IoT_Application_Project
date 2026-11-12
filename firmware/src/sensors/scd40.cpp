#include "sensors/scd40.h"

#include <Wire.h>

namespace {
constexpr uint16_t CMD_STOP_PERIODIC_MEASUREMENT  = 0x3F86;
constexpr uint16_t CMD_START_PERIODIC_MEASUREMENT = 0x21B1;
constexpr uint16_t CMD_GET_DATA_READY_STATUS      = 0xE4B8;
constexpr uint16_t CMD_READ_MEASUREMENT           = 0xEC05;

constexpr uint32_t STOP_MEASUREMENT_DELAY_MS = 500;
constexpr uint32_t COMMAND_EXEC_DELAY_MS     = 1;
}  // namespace

Scd40::Scd40(uint8_t addr) : address(addr) {}

bool Scd40::begin() {
  connected = sendCommand(CMD_STOP_PERIODIC_MEASUREMENT);
  if (!connected) {
    return false;
  }
  delay(STOP_MEASUREMENT_DELAY_MS);

  connected = sendCommand(CMD_START_PERIODIC_MEASUREMENT);
  return connected;
}

bool Scd40::isDataReady() {
  uint16_t status = 0;
  if (!sendCommand(CMD_GET_DATA_READY_STATUS) || !readWords(&status, 1)) {
    return false;
  }
  // 11 bit thấp khác 0 nghĩa là đã có dữ liệu mới.
  return (status & 0x07FF) != 0;
}

bool Scd40::readMeasurement(Scd40Reading &out) {
  uint16_t words[3];
  if (!sendCommand(CMD_READ_MEASUREMENT) || !readWords(words, 3)) {
    return false;
  }

  out.co2Ppm       = words[0];
  out.temperatureC = -45.0f + 175.0f * (words[1] / 65535.0f);
  out.humidityPct  = 100.0f * (words[2] / 65535.0f);
  return true;
}

bool Scd40::sendCommand(uint16_t command) {
  Wire.beginTransmission(address);
  Wire.write(static_cast<uint8_t>(command >> 8));
  Wire.write(static_cast<uint8_t>(command & 0xFF));
  return Wire.endTransmission() == 0;
}

// Mỗi word gồm 2 byte dữ liệu + 1 byte CRC.
bool Scd40::readWords(uint16_t *words, size_t count) {
  delay(COMMAND_EXEC_DELAY_MS);

  const uint8_t length = static_cast<uint8_t>(count * 3);
  if (Wire.requestFrom(address, length) != length) {
    return false;
  }

  for (size_t i = 0; i < count; i++) {
    uint8_t msb = Wire.read();
    uint8_t lsb = Wire.read();
    uint8_t crc = Wire.read();
    if (crc8(msb, lsb) != crc) {
      return false;
    }
    words[i] = (static_cast<uint16_t>(msb) << 8) | lsb;
  }
  return true;
}

// CRC-8 theo datasheet Sensirion: đa thức 0x31, giá trị khởi tạo 0xFF.
uint8_t Scd40::crc8(uint8_t msb, uint8_t lsb) {
  const uint8_t data[2] = {msb, lsb};
  uint8_t crc = 0xFF;
  for (uint8_t byte : data) {
    crc ^= byte;
    for (uint8_t bit = 0; bit < 8; bit++) {
      crc = (crc & 0x80) ? (crc << 1) ^ 0x31 : (crc << 1);
    }
  }
  return crc;
}
