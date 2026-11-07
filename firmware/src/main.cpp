#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include "telemetry_builder.h"

// ================= CONFIGURATION =================

#define DHT_PIN 4
#define DHT_TYPE DHT22

const char* DEVICE_ID = "AIRSENSE-001";

// Wi-Fi: use a 2.4 GHz network
const char* WIFI_SSID = "Fullhouse";
const char* WIFI_PASSWORD = "Me200544";

// IMPORTANT: Replace with your PC's LAN IPv4 address
// NOT 127.0.0.1 or localhost!
const char* MQTT_HOST = "192.168.1.32";
const uint16_t MQTT_PORT = 1883;

const uint32_t REPORT_INTERVAL_SEC = 10;

// ================= MQTT CLIENT =================

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

DHT dht(DHT_PIN, DHT_TYPE);
TelemetryBuilder telemetry(DEVICE_ID);

unsigned long lastReport = 0;
unsigned long lastMqttAttempt = 0;

// ================= WIFI =================


void connectWiFi() {
    Serial.println("\nConnecting to Wi-Fi...");
    
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    unsigned long start = millis();

    while (WiFi.status() != WL_CONNECTED &&
           millis() - start < 20000) {
        delay(500);
        Serial.print(".");
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.println("\nWi-Fi connected!");
        Serial.print("ESP32 IP: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println("\nWi-Fi connection FAILED!");
        Serial.print("Wi-Fi status: ");
        Serial.println(WiFi.status());
    }
}


// ================= MQTT =================


void reconnectMQTT() {
    if (WiFi.status() != WL_CONNECTED) {
        return;
    }

    if (mqttClient.connected()) {
        return;
    }

    if (millis() - lastMqttAttempt < 5000) {
        return;
    }

    lastMqttAttempt = millis();

    Serial.print("Connecting to MQTT broker ");
    Serial.print(MQTT_HOST);
    Serial.print(":");
    Serial.print(MQTT_PORT);
    Serial.print(" ... ");

    String clientId = String("AirSense-") + DEVICE_ID;

    if (mqttClient.connect(clientId.c_str())) {
        Serial.println("SUCCESS!");
    } else {
        Serial.print("FAILED, state = ");
        Serial.println(mqttClient.state());
    }
}


// ================= SETUP =================

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("\n=== AirSense MQTT Telemetry ===");

    dht.begin();
    telemetry.begin();

    connectWiFi();

    mqttClient.setServer(MQTT_HOST, MQTT_PORT);

    // Our JSON payload is larger than PubSubClient's default 256 bytes
    mqttClient.setBufferSize(1024);

    // Synchronize time through NTP
    configTime(0, 0, "pool.ntp.org", "time.google.com");

    reconnectMQTT();
}

// ================= MAIN LOOP =================

void loop() {
    // Maintain MQTT connection
    reconnectMQTT();
    mqttClient.loop();

    // Collect and publish telemetry every 30 seconds
    if (millis() - lastReport >= REPORT_INTERVAL_SEC * 1000UL) {
        lastReport = millis();

        DhtReading reading;

        reading.temperatureC = dht.readTemperature();
        reading.humidityPct = dht.readHumidity();

        reading.valid =
            !isnan(reading.temperatureC) &&
            !isnan(reading.humidityPct) &&
            reading.temperatureC >= -40 &&
            reading.temperatureC <= 125 &&
            reading.humidityPct >= 0 &&
            reading.humidityPct <= 100;

        String payload = telemetry.buildJson(
            reading,
            millis(),
            REPORT_INTERVAL_SEC
        );

        Serial.println("\n========== TELEMETRY ==========");
        Serial.println(payload);

        if (mqttClient.connected()) {
            String topic = String("airsense/") +
                           DEVICE_ID +
                           "/telemetry";

            bool published = mqttClient.publish(
                topic.c_str(),
                payload.c_str()
            );

            if (published) {
                Serial.println("MQTT publish: SUCCESS");
                Serial.println(topic);
            } else {
                Serial.println("MQTT publish: FAILED");
            }
        } else {
            Serial.println("MQTT disconnected; message not published.");
        }

        Serial.println("==============================");
    }

    delay(10);
}
