#pragma once

#include <Arduino.h>
#include "sensors/sensor_types.h"

class TelemetryBuilder {
public:
    TelemetryBuilder(const char* deviceId);

    void begin();

    String buildJson(
        const EnvironmentReading& reading,
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
