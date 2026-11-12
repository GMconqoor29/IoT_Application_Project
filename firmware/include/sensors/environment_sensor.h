#pragma once

#include <Arduino.h>
#include <DHT.h>

#include "config.h"
#include "sensors/scd40.h"
#include "sensors/sensor_types.h"

class EnvironmentSensor {
public:
  EnvironmentSensor();

  // Initialize DHT22, I2C, and SCD40.
  void begin();

  // Acquire readings and apply temperature/humidity fallback.
  EnvironmentReading read();

private:
  DHT dht;
  Scd40 scd40;

  bool readDht(float &temperatureC, float &humidityPct);
  bool readScd40(Scd40Reading &reading);

  static bool isValidTemperature(float value);
  static bool isValidHumidity(float value);
};
