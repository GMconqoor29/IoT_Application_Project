# AirSense ESP32-S3 Firmware

Modular PlatformIO C++ firmware for the ESP32-S3 (`esp32-s3-devkitm-1`) edge node in the AirSense air quality monitoring pipeline.

## Directory Structure

```text
firmware/
├── include/
│   ├── config.h                        # Umbrella configuration header
│   ├── config/
│   │   ├── device_constants.h          # DEVICE_ID, DEFAULT_BOOT_ID, REPORT_INTERVAL_SEC, WIFI_TX_POWER
│   │   ├── mqtt_ca.h                   # Root CA certificate for TLS MQTT brokers (port 8883/8884)
│   │   ├── pins.h                      # GPIO & I2C pin assignments (DHT22 GPIO 4, SCD40 SDA 8 / SCL 9)
│   │   ├── secrets.example.h           # Tracked template for Wi-Fi and MQTT credentials
│   │   └── secrets.h                   # Local git-ignored Wi-Fi and MQTT credentials
│   ├── connectivity/
│   │   └── connectivity.h              # WiFiMulti failover + dual Plain-TCP (1883) / TLS (8883) MQTT client
│   ├── sensors/
│   │   ├── environment_sensor.h        # Unified DHT22 + SCD40 sensor acquisition & automatic fallback
│   │   ├── scd40.h                     # Sensirion SCD40 I2C driver with CRC-8 verification
│   │   └── sensor_types.h              # EnvironmentReading and EnvironmentSource types
│   ├── system/
│   │   ├── device_config.h             # Runtime Wi-Fi & MQTT configuration backed by ESP32 Flash NVS
│   │   ├── logger.h                    # Dual-stream formatted logging (USB CDC Serial + UART0 Serial0)
│   │   └── serial_commands.h           # Interactive Serial CLI (wifi, mqtt, status, reset, help)
│   └── telemetry/
│       └── telemetry_builder.h         # Contract-compliant JSON payload builder with NVS monotonic sequences
├── src/
│   ├── main.cpp                        # Application entry point (setup & loop)
│   ├── connectivity/connectivity.cpp
│   ├── sensors/environment_sensor.cpp
│   ├── sensors/scd40.cpp
│   ├── system/device_config.cpp
│   ├── system/logger.cpp
│   ├── system/serial_commands.cpp
│   └── telemetry/telemetry_builder.cpp
├── test/
│   ├── README                          # Test suite overview and usage
│   ├── run_tests.sh                    # Host C++ unit test runner & JSON contract schema validator
│   ├── test_firmware.cpp               # Unit tests for all 7 firmware modules
│   └── mocks/                          # Host mocks for Arduino, Wire, DHT, Preferences, WiFi, PubSubClient
└── platformio.ini                      # PlatformIO board, framework, build flags, and libraries
```

## Key Features

- **Unified Sensor Acquisition & Fallback (`EnvironmentSensor`)**:
  - Reads ambient temperature and humidity primarily from **DHT22** (`GPIO 4`).
  - Independently acquires true CO2 (ppm), temperature, and humidity from **Sensirion SCD40** over I2C (`SDA: GPIO 8`, `SCL: GPIO 9`, address `0x62`) with Sensirion CRC-8 (`0x31`) validation.
  - If DHT22 fails or is disconnected, automatically falls back to SCD40 temperature and humidity (`EnvironmentSource::SCD40`).
- **Dual Plain-TCP & TLS MQTT Connectivity (`Connectivity`)**:
  - Uses `WiFiMulti` to scan primary and backup Wi-Fi networks with reduced TX power (`WIFI_POWER_8_5dBm`) to prevent USB brownout resets.
  - Automatically selects `WiFiClient` (plain TCP) for local Mosquitto brokers (`port 1883`) and `WiFiClientSecure` (TLS with `MQTT_ROOT_CA` and NTP time synchronization validation) for encrypted brokers (`port 8883` / `8884`).
- **NVS Persistence & Runtime Serial CLI (`DeviceConfig` & `SerialCommands`)**:
  - Loads saved Wi-Fi and MQTT settings from ESP32 Flash NVS (`namespace "airsense"`) on boot via `DeviceConfig::load()`.
  - Supports live reconfiguration over USB (`Serial`) or UART (`Serial0`) at `115200` baud without reflashing:
    - `wifi <SSID> <PASSWORD>`
    - `mqtt <HOST> [PORT] [USER] [PASS]`
    - `status` / `help` / `?`
    - `reset`
- **Contract-Compliant Telemetry (`TelemetryBuilder`)**:
  - Persists and increments `boot_count` in NVS (`namespace "airsense_seq"`) so `sequence` numbers (`bootCount * 100000 + i`) never collide (`ID_CONFLICT`) across hardware resets when using the provisioned `DEFAULT_BOOT_ID` (`"b7f3a901"`).

## Setup & Flashing

1. Copy the credentials template and fill in your Wi-Fi and MQTT broker details:
   ```bash
   cp include/config/secrets.example.h include/config/secrets.h
   ```
2. Build and upload to ESP32-S3 via PlatformIO:
   ```bash
   pio run -t upload
   pio device monitor -b 115200
   ```

## Running Unit Tests

You can run the host C++ unit tests and `contracts/schemas/telemetry.schema.json` validation without physical ESP32 hardware:

```bash
./test/run_tests.sh
```
