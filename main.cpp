
/*
 * AirSense - Stage 2
 * ESP32-S3 + DHT22 + MQ135
 */

#include <Arduino.h>
#include <DHT.h>


#define DEVICE_ID "AIRSENSE-001"
#define DHT_PIN   4
#define DHT_TYPE  DHT22
#define MQ135_PIN 5   // Analog output (AO) of MQ135 on GPIO 5 (ADC1)

// Thông số chuẩn của cảm biến MQ135 để tính CO2 (ppm)
#define MQ135_RLOAD 10.0f           // Điện trở tải trên module (kOhm)
#define MQ135_RZERO 76.63f          // Điện trở chuẩn R0 trong không khí sạch (kOhm)
#define MQ135_PARA  116.6020682f    // Hệ số A đường đặc tuyến CO2
#define MQ135_PARB  2.769034857f    // Hệ số B đường đặc tuyến CO2

DHT dht(DHT_PIN, DHT_TYPE);

// In ra đồng thời cả cổng USB (Serial / HWCDC) và cổng UART (Serial0 / CH343)
void logPrintf(const char *format, ...) {
  char buf[256];
  va_list args;
  va_start(args, format);
  vsnprintf(buf, sizeof(buf), format, args);
  va_end(args);

  Serial.print(buf);
  Serial0.print(buf);
}

// Tính hệ số bù nhiệt độ và độ ẩm từ DHT22 cho MQ135
float getCorrectionFactor(float temperature, float humidity) {
  return 0.00035f * temperature * temperature - 0.02718f * temperature + 1.39538f - (humidity - 33.0f) * 0.0018f;
}

// Chuyển đổi giá trị ADC (12-bit: 0..4095) sang nồng độ CO2 (ppm)
float calculateCO2PPM(int adcRaw, float temperature, float humidity) {
  if (adcRaw <= 0 || adcRaw >= 4095) {
    return 0.0f;
  }
  // Tính điện trở cảm biến Rs (kOhm)
  float rs = ((4095.0f / (float)adcRaw) - 1.0f) * MQ135_RLOAD;

  // Bù nhiệt độ & độ ẩm nếu đọc DHT22 thành công
  if (!isnan(temperature) && !isnan(humidity)) {
    rs /= getCorrectionFactor(temperature, humidity);
  }

  // Công thức đặc tuyến: PPM = PARA * (Rs / R0)^(-PARB)
  return MQ135_PARA * powf((rs / MQ135_RZERO), -MQ135_PARB);
}

void setup() {
  Serial.begin(115200);
  Serial0.begin(115200);
  delay(1500);

  dht.begin();
  pinMode(MQ135_PIN, INPUT);
  analogReadResolution(12); // ADC 12-bit (0 - 4095)

  logPrintf("\n================================\n");
  logPrintf("       AirSense ESP32-S3\n");
  logPrintf("   DHT22 + MQ135 Initialized\n");
  logPrintf("================================\n");
  logPrintf("Device ID: %s\n", DEVICE_ID);
}

void loop() {
  // DHT22 requires approximately 2 seconds between readings
  delay(2000);

  float temperature = dht.readTemperature();
  float humidity = dht.readHumidity();

  int mq135Raw = analogRead(MQ135_PIN);
  float mq135Voltage = mq135Raw * (3.3f / 4095.0f);
  float co2PPM = calculateCO2PPM(mq135Raw, temperature, humidity);

  logPrintf("--------------------------------\n");

  if (isnan(temperature) || isnan(humidity)) {
    logPrintf("[ERROR] Failed to read DHT22!\n");
  } else {
    logPrintf("Temperature: %.1f °C\n", temperature);
    logPrintf("Humidity:    %.1f %%\n", humidity);
  }

  logPrintf("MQ135 Raw:   %d (%.2f V)\n", mq135Raw, mq135Voltage);
  if (mq135Raw == 0) {
    logPrintf("[WARN] MQ135 Raw = 0! Kiem tra nguon VCC (den LED tren MQ135) va chan AO -> GPIO 5\n");
  } else {
    logPrintf("CO2 Level:   %.1f ppm\n", co2PPM);
  }
  logPrintf("Status: OK\n");
}