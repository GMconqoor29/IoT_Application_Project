package com.airsense.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public final class IngestionModels {
  private IngestionModels() {}

  public record Incoming(
      String topic,
      String deviceId,
      String kind,
      String messageId,
      JsonNode payload,
      byte[] original,
      Instant receivedAt,
      boolean retained) {}

  public record Profile(
      String profileId,
      String location,
      JsonNode definition,
      BigDecimal gasMin,
      BigDecimal gasMax,
      BigDecimal dustMin,
      BigDecimal dustMax) {
    public BigDecimal minimum(String channel) {
      return channel.equals("gas") ? gasMin : dustMin;
    }

    public BigDecimal maximum(String channel) {
      return channel.equals("gas") ? gasMax : dustMax;
    }
  }

  public record Calibration(BigDecimal baseline, JsonNode definition) {}

  public record TrustedContext(Profile profile, Calibration gas, Calibration dust) {}

  public record Accepted(String canonicalPayload, Instant receivedAt) {}

  public record Receipt(
      int schemaVersion,
      String deviceId,
      String messageId,
      String status,
      String receivedAt,
      String code,
      boolean retryable,
      String details) {}

  public record Result(Receipt receipt, boolean inserted, String deviceId) {}

  public static Receipt receipt(String device, String message, Instant now, String code) {
    return new Receipt(
        1,
        device,
        message,
        code.equals("COMMITTED") ? "STORED" : "REJECTED",
        now.toString(),
        code,
        false,
        code.equals("COMMITTED") ? "Measurement committed." : "Rejected by contract validation.");
  }
}
