package com.airsense;

import static com.airsense.ingestion.IngestionModels.*;
import static com.airsense.monitoring.MonitoringModels.*;
import static com.airsense.threshold.ThresholdModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.airsense.auth.AuthModels;
import com.airsense.common.*;
import com.airsense.ingestion.TelemetryValidator;
import com.airsense.monitoring.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyTest {
  final Instant now = Instant.parse("2026-11-06T07:00:01Z");

  JsonNode fixture(String name) throws Exception {
    return JsonSupport.parse(Files.readString(Path.of("../contracts/fixtures/" + name)));
  }

  Measurement reading(JsonNode p, Instant measured) {
    return new Measurement(p, now, measured, 30, "simulator-s3-001", "gas-baseline-001", null);
  }

  List<Rule> rules() {
    var result = new ArrayList<Rule>();
    for (String parameter : List.of("temperatureC", "humidityPct", "gasRelative", "dustAdcMv")) {
      boolean gas = parameter.equals("gasRelative");
      result.add(
          new Rule(
              parameter,
              "AIRSENSE-001",
              parameter,
              "test",
              gas ? "gas-baseline-001" : null,
              1,
              "UPPER",
              new Bounds(null, new BigDecimal(gas ? "1.2" : "2000")),
              new Bounds(null, new BigDecimal(gas ? "1.3" : "2500")),
              new Bounds(null, new BigDecimal(gas ? "1.15" : "1500")),
              true,
              "simulator-s3-001"));
    }
    return result;
  }

  @Test
  void freshnessRequiresBothAcquisitionAndReception() throws Exception {
    var policy = new FreshnessPolicy(Clock.fixed(now, ZoneOffset.UTC));
    var p = fixture("valid/telemetry-normal.json");
    var evidence = new DeviceEvidence("AIRSENSE-001", "room", "test", now, null, false);
    assertEquals("FRESH", policy.device(evidence, reading(p, now.minusSeconds(90))).freshness());
    assertEquals("STALE", policy.device(evidence, reading(p, now.minusSeconds(91))).freshness());
    assertEquals("STALE", policy.device(evidence, reading(p, now.plusSeconds(1))).freshness());
    var oldReception =
        new DeviceEvidence("AIRSENSE-001", "room", "test", now.minusSeconds(91), null, false);
    assertEquals("STALE", policy.device(oldReception, reading(p, now)).freshness());
    assertEquals("UNKNOWN", policy.device(evidence, null).freshness());
    // Stored evidence from before process startup cannot establish a live connection.
    assertFalse(policy.device(oldReception, reading(p, now)).online());
  }

  @Test
  void classificationIsImmediatePureAndQualityAware() throws Exception {
    var evaluator = new ConditionEvaluator();
    var p = (ObjectNode) fixture("valid/telemetry-normal.json");
    assertEquals("NORMAL", evaluator.evaluate(reading(p, now), true, rules()).condition());
    ((ObjectNode) p.path("values")).put("gasRelative", 1.3);
    var alert = evaluator.evaluate(reading(p, now), true, rules());
    assertEquals("ALERT", alert.condition());
    assertEquals(alert, evaluator.evaluate(reading(p, now), true, rules()));
    assertEquals(
        List.of(p.path("messageId").asText()), alert.recommendations().getFirst().measurementIds());
    assertEquals("UNKNOWN", evaluator.evaluate(reading(p, now), false, rules()).condition());
    ((ObjectNode) p.path("quality")).put("mq135", "INVALID");
    assertEquals("UNKNOWN", evaluator.evaluate(reading(p, now), true, rules()).condition());
  }

  @Test
  void differentCalibrationCannotUseCurrentRule() throws Exception {
    var p = fixture("valid/telemetry-normal.json");
    var other = new Measurement(p, now, now, 30, "simulator-s3-001", "other-baseline", null);
    assertEquals("UNKNOWN", new ConditionEvaluator().evaluate(other, true, rules()).condition());
  }

  TrustedContext context() throws Exception {
    var definition = fixture("profiles/simulator-s3.json");
    var profile =
        new Profile(
            "simulator-s3-001",
            "room",
            definition,
            new BigDecimal("150"),
            new BigDecimal("3000"),
            new BigDecimal("150"),
            new BigDecimal("3000"));
    return new TrustedContext(
        profile, new Calibration(new BigDecimal("1000"), JsonSupport.parse("{}")), null);
  }

  Incoming incoming(JsonNode p) {
    return new Incoming(
        "airsense/AIRSENSE-001/telemetry",
        "AIRSENSE-001",
        "telemetry",
        p.path("messageId").asText(),
        p,
        p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        now,
        false);
  }

  @Test
  void telemetrySemanticsUseTrustedContextWithoutDatabase() throws Exception {
    var validator = new TelemetryValidator(new ContractValidator());
    var p = (ObjectNode) fixture("valid/telemetry-normal.json");
    var trusted = context();
    validator.envelope(incoming(p));
    validator.identity(incoming(p));
    assertEquals(now.minusSeconds(1), validator.validate(incoming(p), trusted));
    ((ObjectNode) p.path("raw")).put("gasAdcCounts", 4095);
    assertEquals(
        "OUT_OF_RANGE",
        assertThrows(ApiException.class, () -> validator.validate(incoming(p), trusted)).code);
    ((ObjectNode) p.path("raw")).put("gasAdcCounts", 1474);
    ((ObjectNode) p.path("values")).put("gasRelative", 9);
    assertEquals(
        "INVALID_CALIBRATION",
        assertThrows(ApiException.class, () -> validator.validate(incoming(p), trusted)).code);
  }

  @Test
  void invalidTrustedProfileIsRetryableAndFutureReadingIsRejected() throws Exception {
    var validator = new TelemetryValidator(new ContractValidator());
    var trusted = context();
    var p = (ObjectNode) fixture("valid/telemetry-normal.json");
    p.put("measuredAt", now.plusSeconds(121).toString());
    assertEquals(
        "FUTURE_TIMESTAMP",
        assertThrows(ApiException.class, () -> validator.validate(incoming(p), trusted)).code);
    ((ObjectNode) trusted.profile().definition()).put("gasUsableMinMv", 151);
    assertEquals(
        503,
        assertThrows(ApiException.class, () -> validator.validate(incoming(p), trusted)).status);
  }

  @Test
  void typedResponsesPreserveListEnvelopeAndHideInternalBindings() {
    var json = JsonSupport.MAPPER.valueToTree(new RuleList(rules()));
    assertTrue(json.path("items").isArray());
    assertFalse(json.path("items").get(0).has("profileId"));
    assertTrue(JsonSupport.MAPPER.valueToTree(new DeviceList(List.of())).path("items").isArray());
    assertFalse(new AuthModels.Login("admin", "secret").toString().contains("secret"));
    assertFalse(
        new AuthModels.LoginResult(
                new AuthModels.SessionResponse("admin", "ADMIN", "secret"), "secret")
            .toString()
            .contains("secret"));
  }

  @Test
  void utcQueryParsingRejectsExcessPrecisionAndInvalidDates() {
    assertEquals(now, MonitoringService.utc(now.toString()));
    assertThrows(ApiException.class, () -> MonitoringService.utc("2026-11-06T07:00:01.0001Z"));
    assertThrows(ApiException.class, () -> MonitoringService.utc("2026-13-06T07:00:01Z"));
  }
}
