package com.airsense.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class MonitoringModels {
  private MonitoringModels() {}

  public record DeviceEvidence(
      String deviceId,
      String location,
      String firmwareVersion,
      Instant lastReceivedAt,
      Instant stateReceivedAt,
      boolean reportedOnline) {}

  public record Measurement(
      JsonNode telemetry,
      Instant receivedAt,
      Instant measuredAt,
      int reportIntervalSec,
      String profileId,
      String gasCalibrationId,
      String dustCalibrationId) {}

  public record Device(
      String deviceId,
      String location,
      String firmwareVersion,
      boolean online,
      String freshness,
      String lastReceivedAt,
      long reportIntervalSec) {}

  public record DeviceList(List<Device> items) {}

  public record StoredMeasurement(JsonNode telemetry, String receivedAt) {}

  public record Recommendation(
      String id, String message, List<String> measurementIds, String status) {}

  public record Evaluation(
      String condition, List<String> reasons, List<Recommendation> recommendations) {}

  public record Latest(
      Device device,
      StoredMeasurement measurement,
      String condition,
      List<String> reasons,
      List<Recommendation> recommendations) {}

  public record HistoryPage(List<StoredMeasurement> items, String nextCursor) {}

  public record HistoryMember(StoredMeasurement measurement, long ordinal) {}

  public record ParameterStatistics(
      String unit,
      BigDecimal min,
      BigDecimal max,
      BigDecimal mean,
      long validCount,
      String calibrationId) {}

  public record Statistics(
      String deviceId, String from, String to, Map<String, ParameterStatistics> parameters) {}

  public record Range(Instant from, Instant to) {}
}
