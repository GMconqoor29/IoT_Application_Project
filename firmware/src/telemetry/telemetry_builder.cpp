#include "telemetry/telemetry_builder.h"
#include "config.h"
#include <ArduinoJson.h>
#include <Preferences.h>
#include <time.h>

TelemetryBuilder::TelemetryBuilder(const char* id)
    : deviceId(id), sequence(0) {}

void TelemetryBuilder::begin() {
    bootId = generateBootId();

    // Lưu và tăng bộ đếm khởi động trong Flash NVS để sequence không bao giờ bị trùng (ID_CONFLICT)
    // giữa các lần nhấn nút Reset trên ESP32 khi dùng chung bootId đã provision.
    Preferences prefs;
    prefs.begin("airsense_seq", false);
    uint32_t bootCount = prefs.getUInt("boot_count", 0) + 1;
    prefs.putUInt("boot_count", bootCount);
    prefs.end();

    sequence = static_cast<uint64_t>(bootCount) * 100000ULL;
}

String TelemetryBuilder::generateBootId() {
    // Sử dụng bootId khớp với bản ghi đã provision trong backend/provision/simulator.sql ("b7f3a901")
    return String(DEFAULT_BOOT_ID);
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
    const EnvironmentReading& reading,
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

    if (reading.temperatureHumidityValid) {
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

    quality["sht30"] = reading.temperatureHumidityValid
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
