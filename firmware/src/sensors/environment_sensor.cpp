#include "sensors/environment_sensor.h"

#include <Wire.h>
#include <math.h>

#include "system/logger.h"

EnvironmentSensor::EnvironmentSensor()
    : dht(DHT_PIN, DHT_TYPE),
      scd40(SCD40_ADDR) {}

void EnvironmentSensor::begin() {
  dht.begin();

  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);

  if (scd40.begin()) {
    logPrintf(
        "[OK] Tim thay cam bien SCD40 (0x%02X)! "
        "Cho chu ky do dau tien...\n",
        SCD40_ADDR);
  } else {
    logPrintf(
        "[WARN] Khong tim thay SCD40 tai 0x%02X! "
        "Kiem tra day SDA(%d), SCL(%d)\n",
        SCD40_ADDR, I2C_SDA_PIN, I2C_SCL_PIN);
  }
}

bool EnvironmentSensor::isValidTemperature(float value) {
  return !isnan(value) && value >= -40.0f && value <= 125.0f;
}

bool EnvironmentSensor::isValidHumidity(float value) {
  return !isnan(value) && value >= 0.0f && value <= 100.0f;
}

bool EnvironmentSensor::readDht(
    float &temperatureC,
    float &humidityPct) {
  temperatureC = dht.readTemperature();
  humidityPct = dht.readHumidity();

  if (!isValidTemperature(temperatureC) || !isValidHumidity(humidityPct)) {
    logPrintf(
        "[DHT22] Khong doc duoc du lieu "
        "(kiem tra GPIO %d)\n",
        DHT_PIN);
    return false;
  }

  logPrintf(
      "[DHT22] Temp: %.1f C | Hum: %.1f %%\n",
      temperatureC, humidityPct);

  return true;
}


bool EnvironmentSensor::readScd40(Scd40Reading &reading) {
  if (!scd40.isConnected() && !scd40.begin()) {
    logPrintf(
        "[SCD40] Chua ket noi "
        "(SDA->GPIO%d, SCL->GPIO%d)\n",
        I2C_SDA_PIN, I2C_SCL_PIN);
    return false;
  }

  if (!scd40.isDataReady()) {
    logPrintf("[SCD40] Dang chuan bi du lieu do...\n");
    return false;
  }

  if (!scd40.readMeasurement(reading)) {
    logPrintf("[SCD40] Loi doc du lieu I2C / CRC!\n");
    return false;
  }

  logPrintf(
      "[SCD40] True CO2: %u ppm | Temp: %.1f C | Hum: %.1f %%\n",
      static_cast<unsigned>(reading.co2Ppm),
      reading.temperatureC,
      reading.humidityPct);

  // Successful acquisition is independent of field validity.
  return true;
}


EnvironmentReading EnvironmentSensor::read() {
  EnvironmentReading result;

  // 1. DHT22 is the primary source.
  float dhtTemperature = NAN;
  float dhtHumidity = NAN;

  if (readDht(dhtTemperature, dhtHumidity)) {
    result.temperatureC = dhtTemperature;
    result.humidityPct = dhtHumidity;
    result.temperatureHumidityValid = true;
    result.temperatureHumiditySource = EnvironmentSource::DHT22;
  }

  // 2. SCD40 is independently acquired for CO2 and fallback.
 
  Scd40Reading scdReading{};

  if (readScd40(scdReading)) {
    // CO2 is validated independently.
    result.co2Ppm = scdReading.co2Ppm;
    result.co2Valid = scdReading.co2Ppm > 0;

    const bool scdTemperatureHumidityValid =
        isValidTemperature(scdReading.temperatureC) &&
        isValidHumidity(scdReading.humidityPct);

    // Fallback requires valid SCD40 Temp/Hum.
    if (!result.temperatureHumidityValid &&
        scdTemperatureHumidityValid) {
      result.temperatureC = scdReading.temperatureC;
      result.humidityPct = scdReading.humidityPct;
      result.temperatureHumidityValid = true;
      result.temperatureHumiditySource = EnvironmentSource::SCD40;

      logPrintf(
          "[FALLBACK] Su dung Temp & Hum tu SCD40 "
          "cho Telemetry\n");
    }
  }

  return result;
}
