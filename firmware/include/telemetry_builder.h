#pragma once

#include <Arduino.h>

struct DhtReading {
    float temperatureC;
    float humidityPct;
    bool valid;
};

class TelemetryBuilder {
public:
    TelemetryBuilder(const char* deviceId);

    void begin();

    String buildJson(
        const DhtReading& reading,
        uint32_t uptimeMs,
        uint32_t reportIntervalSec
    );

private:
    String deviceId;
    String bootId;
    uint64_t sequence;

    String generateBootId();
    String getIsoTimestamp(bool& synced);
};
