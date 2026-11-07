/*
 * Standalone DHT22 Sensor Test Sketch for ESP32-S3-N16R8
 * ======================================================
 * Wiring (3-pin DHT22 PCB Module -> ESP32-S3-N16R8):
 *   - (+) / VCC       -> 3V3 (3.3V)
 *   - (OUT) / DATA    -> GPIO 4
 *   - (-) / GND       -> GND
 *
 * Recommended Arduino IDE Tools Settings:
 *   - Board            : "ESP32S3 Dev Module"
 *   - Flash Mode       : "DIO 80MHz"
 *   - Flash Size       : "4MB (32Mb)" (or "16MB (128Mb)")
 *   - PSRAM            : "Disabled"
 *   - USB CDC On Boot  : "Enabled"
 *   - USB Mode         : "Hardware CDC and JTAG"
 */

#include <Arduino.h>
#include <DHT.h>

// Automatically output to BOTH USB (/dev/ttyACM0) and UART0 (/dev/ttyUSB0)
// regardless of whether "USB CDC On Boot" is Enabled or Disabled.
#if defined(CONFIG_IDF_TARGET_ESP32S3) && (ARDUINO_USB_MODE == 1)
  #if ARDUINO_USB_CDC_ON_BOOT == 1
    #define LOG_BEGIN(baud)  do { Serial.begin(baud); Serial0.begin(baud); } while (0)
    #define LOG_PRINTF(...)  do { Serial.printf(__VA_ARGS__); Serial0.printf(__VA_ARGS__); } while (0)
  #else
    #define LOG_BEGIN(baud)  do { Serial.begin(baud); USBSerial.begin(baud); } while (0)
    #define LOG_PRINTF(...)  do { Serial.printf(__VA_ARGS__); USBSerial.printf(__VA_ARGS__); } while (0)
  #endif
#else
  #define LOG_BEGIN(baud)    Serial.begin(baud)
  #define LOG_PRINTF(...)    Serial.printf(__VA_ARGS__)
#endif

// ── Pin & Sensor Configuration ────────────────────────────────────────────────
#define DHTPIN  4       // DATA pin connected to GPIO 4 on ESP32-S3
#define DHTTYPE DHT22   // DHT 22 (AM2302)

DHT dht(DHTPIN, DHTTYPE);

const unsigned long READ_INTERVAL_MS = 2500UL; // Read every 2.5 seconds
unsigned long lastReadMs = 0;
uint32_t sampleCount = 0;

void setup() {
  LOG_BEGIN(115200);
  delay(1000);

  LOG_PRINTF("\n================================================\n");
  LOG_PRINTF("  ESP32-S3-N16R8 - Standalone DHT22 Test Node   \n");
  LOG_PRINTF("  DHT22 DATA Pin : GPIO %d\n", DHTPIN);
  LOG_PRINTF("================================================\n");

  dht.begin();
}

void loop() {
  unsigned long now = millis();
  if (now - lastReadMs < READ_INTERVAL_MS) {
    return;
  }
  lastReadMs = now;
  sampleCount++;

  // Read Relative Humidity (%) and Temperature (Celsius)
  float humidity    = dht.readHumidity();
  float temperature = dht.readTemperature();

  // Check if any read failed
  if (isnan(humidity) || isnan(temperature)) {
    LOG_PRINTF(
      "[#%lu] [ERROR] Failed to read from DHT22 on GPIO %d! Check 3V3, GND, and DATA wiring.\n",
      sampleCount,
      DHTPIN
    );
    return;
  }

  // Compute Heat Index in Celsius (isFahreheit = false)
  float heatIndex = dht.computeHeatIndex(temperature, humidity, false);

  LOG_PRINTF(
    "[#%lu] Temperature: %.1f °C | Humidity: %.1f %% | Heat Index: %.1f °C\n",
    sampleCount,
    temperature,
    humidity,
    heatIndex
  );
}
