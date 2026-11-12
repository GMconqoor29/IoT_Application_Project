#pragma once

#include <Arduino.h>

enum class EnvironmentSource : uint8_t {
  NONE,
  DHT22,
  SCD40
};

struct EnvironmentReading {
  float temperatureC = NAN;
  float humidityPct = NAN;
  uint16_t co2Ppm = 0;

  bool temperatureHumidityValid = false;
  bool co2Valid = false;

  EnvironmentSource temperatureHumiditySource = EnvironmentSource::NONE;
};
