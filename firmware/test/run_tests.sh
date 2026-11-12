#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIRMWARE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_BIN="/tmp/airsense_firmware_tests"

echo "[1/3] Compiling firmware components and unit tests..."
g++ -std=c++17 -Wall -Wextra \
  -I"$SCRIPT_DIR/mocks" \
  -I"$FIRMWARE_DIR/include" \
  "$FIRMWARE_DIR/src/system/logger.cpp" \
  "$FIRMWARE_DIR/src/system/device_config.cpp" \
  "$FIRMWARE_DIR/src/system/serial_commands.cpp" \
  "$FIRMWARE_DIR/src/sensors/scd40.cpp" \
  "$FIRMWARE_DIR/src/sensors/environment_sensor.cpp" \
  "$FIRMWARE_DIR/src/connectivity/connectivity.cpp" \
  "$FIRMWARE_DIR/src/telemetry/telemetry_builder.cpp" \
  "$SCRIPT_DIR/test_firmware.cpp" \
  -o "$BUILD_BIN"

echo "[2/3] Running C++ unit tests..."
"$BUILD_BIN"

echo "[3/3] Validating TelemetryBuilder JSON output against contracts/schemas/telemetry.schema.json..."
SYNCED_JSON="$("$BUILD_BIN" --dump-synced-json)"
UNSYNCED_JSON="$("$BUILD_BIN" --dump-unsynced-json)"

SYNCED_JSON="$SYNCED_JSON" UNSYNCED_JSON="$UNSYNCED_JSON" node --input-type=module -e '
  import assert from "node:assert/strict";

  function assertConformsToTelemetryContract(rawJson, expectedSynced, expectedValidSensor) {
    const p = JSON.parse(rawJson);
    const requiredRoot = [
      "schemaVersion", "deviceId", "messageId", "bootId", "sequence",
      "measuredAt", "uptimeMs", "timeQuality", "reportIntervalSec",
      "values", "raw", "quality", "calibration"
    ];
    assert.deepEqual(Object.keys(p).sort(), [...requiredRoot].sort());
    assert.equal(p.schemaVersion, 1);
    assert.match(p.deviceId, /^[A-Z0-9][A-Z0-9_-]{0,63}$/);
    assert.match(p.bootId, /^[a-f0-9]{8,32}$/);
    assert.equal(p.messageId, `${p.deviceId}:${p.bootId}:${p.sequence}`);
    assert.equal(typeof p.sequence, "number");
    assert.equal(typeof p.uptimeMs, "number");
    assert.equal(typeof p.reportIntervalSec, "number");

    if (expectedSynced) {
      assert.equal(p.timeQuality, "SYNCED");
      assert.match(p.measuredAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/);
    } else {
      assert.equal(p.timeQuality, "UNSYNCED");
      assert.equal(p.measuredAt, null);
    }

    if (expectedValidSensor) {
      assert.equal(p.quality.sht30, "VALID");
      assert.equal(typeof p.values.temperatureC, "number");
      assert.ok(p.values.temperatureC >= -40 && p.values.temperatureC <= 125);
      assert.equal(typeof p.values.humidityPct, "number");
      assert.ok(p.values.humidityPct >= 0 && p.values.humidityPct <= 100);
    } else {
      assert.equal(p.quality.sht30, "INVALID");
      assert.equal(p.values.temperatureC, null);
      assert.equal(p.values.humidityPct, null);
    }

    assert.equal(p.quality.mq135, "UNAVAILABLE");
    assert.equal(p.quality.dust, "UNAVAILABLE");
    assert.equal(p.values.gasRelative, null);
    assert.equal(p.values.dustEstimateUgM3, null);
    assert.deepEqual(p.raw, {
      gasAdcCounts: null,
      gasAdcMv: null,
      dustAdcCounts: null,
      dustAdcMv: null,
    });
    assert.deepEqual(p.calibration, {
      gasId: null,
      dustId: null,
    });
  }

  assertConformsToTelemetryContract(process.env.SYNCED_JSON, true, true);
  console.log("[PASS] Contract validation: SYNCED + valid reading payload conforms to telemetry.schema.json");

  assertConformsToTelemetryContract(process.env.UNSYNCED_JSON, false, false);
  console.log("[PASS] Contract validation: UNSYNCED + sensor fault payload conforms to telemetry.schema.json");
'

rm -f "$BUILD_BIN"
