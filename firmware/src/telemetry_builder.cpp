#include "telemetry_builder.h"
#include <ArduinoJson.h>
#include <time.h>

TelemetryBuilder::TelemetryBuilder(const char* id)
    : deviceId(id), sequence(0) {}

void TelemetryBuilder::begin() {
    bootId = generateBootId();
}

String TelemetryBuilder::generateBootId() {
    char buffer[9];

    uint32_t randomValue = esp_random();

    snprintf(buffer, sizeof(buffer), "%08lx",
             static_cast<unsigned long>(randomValue));

    return String(buffer);
}

String TelemetryBuilder::getIsoTimestamp(bool& synced) {
    time_t now = time(nullptr);

    // Basic clock synchronization check.
    synced = now >= 1760000000;

    if (!synced) {
        return "";
    }

    struct tm utcTime;
    gmtime_r(&now, &utcTime);

    char buffer[25];

    strftime(buffer, sizeof(buffer),
             "%Y-%m-%dT%H:%M:%SZ", &utcTime);

    return String(buffer);
}

String TelemetryBuilder::buildJson(
    const DhtReading& reading,
    uint32_t uptimeMs,
    uint32_t reportIntervalSec
) {
    JsonDocument doc;

    bool timeSynced = false;
    String timestamp = getIsoTimestamp(timeSynced);

    // Identity
    doc["schemaVersion"] = 1;
    doc["deviceId"] = deviceId;

    String messageId = deviceId + ":" +
                       bootId + ":" +
                       String(sequence);

    doc["messageId"] = messageId;
    doc["bootId"] = bootId;
    doc["sequence"] = sequence++;

    // Acquisition information
    if (timeSynced) {
        doc["measuredAt"] = timestamp;
    } else {
        doc["measuredAt"] = nullptr;
    }

    doc["uptimeMs"] = uptimeMs;
    doc["timeQuality"] = timeSynced
        ? "SYNCED"
        : "UNSYNCED";

    doc["reportIntervalSec"] = reportIntervalSec;

    // Converted sensor values
    JsonObject values = doc["values"].to<JsonObject>();

    if (reading.valid) {
        values["temperatureC"] = reading.temperatureC;
        values["humidityPct"] = reading.humidityPct;
    } else {
        values["temperatureC"] = nullptr;
        values["humidityPct"] = nullptr;
    }

    values["gasRelative"] = nullptr;
    values["dustEstimateUgM3"] = nullptr;

    // Raw ADC evidence (sensors not connected yet)
    JsonObject raw = doc["raw"].to<JsonObject>();

    raw["gasAdcCounts"] = nullptr;
    raw["gasAdcMv"] = nullptr;
    raw["dustAdcCounts"] = nullptr;
    raw["dustAdcMv"] = nullptr;

    // Sensor quality
    JsonObject quality = doc["quality"].to<JsonObject>();

    quality["sht30"] = reading.valid
        ? "VALID"
        : "INVALID";

    quality["mq135"] = "UNAVAILABLE";
    quality["dust"] = "UNAVAILABLE";

    // Calibration references
    JsonObject calibration =
        doc["calibration"].to<JsonObject>();

    calibration["gasId"] = nullptr;
    calibration["dustId"] = nullptr;

    String json;
    serializeJson(doc, json);

    return json;
}
