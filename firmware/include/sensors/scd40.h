#pragma once

#include <Arduino.h>

struct Scd40Reading {
  uint16_t co2Ppm;
  float temperatureC;
  float humidityPct;
};

// Driver tối giản cho cảm biến Sensirion SCD40 qua I2C (Wire).
class Scd40 {
public:
  explicit Scd40(uint8_t address);

  // Dừng đo cũ rồi bắt đầu chế độ đo định kỳ. Trả về true nếu cảm biến phản hồi.
  bool begin();
  bool isConnected() const { return connected; }

  bool isDataReady();
  bool readMeasurement(Scd40Reading &out);

private:
  uint8_t address;
  bool connected = false;

  bool sendCommand(uint16_t command);
  bool readWords(uint16_t *words, size_t count);
  static uint8_t crc8(uint8_t msb, uint8_t lsb);
};
