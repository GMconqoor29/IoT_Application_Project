
/*
 * AirSense - Stage 2
 * ESP32-S3 + DHT22
 */

#include <DHT.h>

#define DEVICE_ID "AIRSENSE-001"
#define DHT_PIN   4
#define DHT_TYPE  DHT22

DHT dht(DHT_PIN, DHT_TYPE);

void setup() {
  Serial.begin(115200);
  delay(1000);

  dht.begin();

  Serial.println();
  Serial.println("================================");
  Serial.println("       AirSense ESP32-S3");
  Serial.println("       DHT22 Initialization");
  Serial.println("================================");
  Serial.print("Device ID: ");
  Serial.println(DEVICE_ID);
}

void loop() {
  // DHT22 requires approximately 2 seconds between readings
  delay(2000);

  float temperature = dht.readTemperature();
  float humidity = dht.readHumidity();

  if (isnan(temperature) || isnan(humidity)) {
    Serial.println("[ERROR] Failed to read DHT22!");
    return;
  }

  Serial.println("--------------------------------");
  Serial.print("Temperature: ");
  Serial.print(temperature, 1);
  Serial.println(" °C");

  Serial.print("Humidity: ");
  Serial.print(humidity, 1);
  Serial.println(" %");

  Serial.println("Status: OK");
}
