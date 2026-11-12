#pragma once

#include <cmath>
#include <cstdint>

static const uint8_t DHT22{22};

class DHT {
public:
  DHT(uint8_t pin, uint8_t type) : pin_(pin), type_(type) {}

  void begin() { initialized = true; }
  float readTemperature() { return mockTemperature; }
  float readHumidity() { return mockHumidity; }

  uint8_t pin() const { return pin_; }
  uint8_t type() const { return type_; }

  static bool initialized;
  static float mockTemperature;
  static float mockHumidity;

  static void resetMock() {
    initialized = false;
    mockTemperature = 25.0f;
    mockHumidity = 60.0f;
  }

private:
  uint8_t pin_;
  uint8_t type_;
};
