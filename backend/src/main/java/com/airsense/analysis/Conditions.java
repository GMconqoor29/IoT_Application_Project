package com.airsense.analysis;

import static com.airsense.common.Json.*;

import com.airsense.common.*;
import com.airsense.measurement.Measurements;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Conditions {
  private final JdbcTemplate db;
  private final Measurements measurements;
  private volatile List<Map<String, Object>> rules = List.of();
  private final Map<String, Pending> pending = new HashMap<>();
  private final Map<String, Map<String, Object>> watermarks = new HashMap<>();

  @org.springframework.beans.factory.annotation.Value("${airsense.analysis.enter-consecutive:3}")
  private int enterConsecutive = 3;

  @org.springframework.beans.factory.annotation.Value("${airsense.analysis.clear-consecutive:3}")
  private int clearConsecutive = 3;

  @org.springframework.beans.factory.annotation.Value("${airsense.analysis.cooldown-sec:600}")
  private long cooldownSec = 600;

  private static class Pending {
    int enter, clear;
    boolean active;
    java.time.Instant lastNotification = java.time.Instant.EPOCH;
  }

  public Conditions(JdbcTemplate db, Measurements measurements) {
    this.db = db;
    this.measurements = measurements;
  }

  @PostConstruct
  public synchronized void refresh() {
    rules = List.copyOf(db.queryForList("select * from thresholds order by id"));
    pending.clear();
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void bootstrap() {
    for (var device : db.queryForList("select device_id from devices"))
      observe(device.get("device_id").toString());
  }

  public synchronized void observe(String device) {
    var row = measurements.latestRow(device);
    if (row == null) return;
    var old = watermarks.get(device);
    if (old != null && compare(row, old) <= 0) return;
    watermarks.put(device, row);
    boolean fresh = measurements.fresh(row);
    var p = parse(row.get("canonical_payload").toString());
    for (var r : rules) {
      if (!r.get("device_id").equals(device) || !Boolean.TRUE.equals(r.get("enabled"))) continue;
      var state = pending.computeIfAbsent(r.get("id").toString(), k -> new Pending());
      String parameter = r.get("parameter").toString();
      var value = p.path(parameter.equals("dustAdcMv") ? "raw" : "values").path(parameter);
      String binding =
          parameter.equals("gasRelative")
              ? "gas_calibration_id"
              : parameter.equals("dustEstimateUgM3")
                  ? "dust_calibration_id"
                  : parameter.equals("dustAdcMv") ? "profile_id" : null;
      if (!fresh
          || value.isNull()
          || !validQuality(p, parameter)
          || (binding != null
              && !Objects.equals(
                  row.get(binding),
                  r.get(binding.equals("profile_id") ? "profile_id" : "calibration_id")))) {
        state.enter = 0;
        state.clear = 0;
        continue;
      }
      if (violates(value.asDouble(), r, "warning")) {
        state.clear = 0;
        state.enter = Math.min(enterConsecutive, state.enter + 1);
        if (state.enter >= enterConsecutive
            && !java.time.Instant.now().isBefore(state.lastNotification.plusSeconds(cooldownSec))) {
          state.active = true;
          state.lastNotification = java.time.Instant.now();
          org.slf4j.LoggerFactory.getLogger(Conditions.class)
              .info(
                  "notification_candidate device={} rule={} messageId={} value={} unit={}",
                  device,
                  r.get("id"),
                  p.path("messageId").asText(),
                  value,
                  r.get("unit"));
        }
      } else {
        state.enter = 0;
        if (!violates(value.asDouble(), r, "recovery")) {
          state.clear = Math.min(clearConsecutive, state.clear + 1);
          if (state.clear >= clearConsecutive) state.active = false;
        } else state.clear = 0;
      }
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
  public synchronized void expirePending() {
    for (var entry : watermarks.entrySet())
      if (!measurements.fresh(entry.getValue()))
        for (var r : rules)
          if (r.get("device_id").equals(entry.getKey())) {
            var state = pending.get(r.get("id").toString());
            if (state != null) {
              state.enter = 0;
              state.clear = 0;
            }
          }
  }

  private static int compare(Map<String, Object> a, Map<String, Object> b) {
    int c =
        ((java.sql.Timestamp) a.get("measured_at"))
            .compareTo((java.sql.Timestamp) b.get("measured_at"));
    if (c != 0) return c;
    c = a.get("boot_id").toString().compareTo(b.get("boot_id").toString());
    return c != 0
        ? c
        : Long.compare(
            ((Number) a.get("sequence")).longValue(), ((Number) b.get("sequence")).longValue());
  }

  public List<Map<String, Object>> thresholds(String device) {
    measurements.registered(device);
    return rules.stream().filter(r -> r.get("device_id").equals(device)).map(this::dto).toList();
  }

  public Map<String, Object> dto(Map<String, Object> r) {
    return obj(
        "id",
        r.get("id"),
        "deviceId",
        r.get("device_id"),
        "parameter",
        r.get("parameter"),
        "unit",
        r.get("unit"),
        "calibrationId",
        r.get("calibration_id"),
        "revision",
        r.get("revision"),
        "direction",
        r.get("direction"),
        "warning",
        obj("low", r.get("warning_low"), "high", r.get("warning_high")),
        "alert",
        obj("low", r.get("alert_low"), "high", r.get("alert_high")),
        "recovery",
        obj("low", r.get("recovery_low"), "high", r.get("recovery_high")),
        "enabled",
        r.get("enabled"));
  }

  public Map<String, Object> latest(String device) {
    var d = measurements.device(device);
    var row = measurements.latestRow(device);
    List<String> reasons = new ArrayList<>();
    List<Object> recommendations = new ArrayList<>();
    if (row == null || !d.get("freshness").equals("FRESH"))
      return obj(
          "device",
          d,
          "measurement",
          row == null ? null : measurements.stored(row),
          "condition",
          "UNKNOWN",
          "reasons",
          reasons,
          "recommendations",
          recommendations);
    var p = parse(row.get("canonical_payload").toString());
    boolean unknown = false;
    int severity = 0;
    for (String sensor : List.of("sht30", "mq135", "dust"))
      if (!p.path("quality").path(sensor).asText().equals("VALID")) unknown = true;
    Set<String> covered = new HashSet<>();
    for (var r : rules) {
      if (!r.get("device_id").equals(device) || !Boolean.TRUE.equals(r.get("enabled"))) continue;
      String parameter = r.get("parameter").toString();
      covered.add(parameter);
      var value = p.path(parameter.equals("dustAdcMv") ? "raw" : "values").path(parameter);
      String binding =
          parameter.equals("gasRelative")
              ? "gas_calibration_id"
              : parameter.equals("dustEstimateUgM3")
                  ? "dust_calibration_id"
                  : parameter.equals("dustAdcMv") ? "profile_id" : null;
      if (value.isNull()
          || !validQuality(p, parameter)
          || (binding != null
              && !Objects.equals(
                  row.get(binding),
                  r.get(binding.equals("profile_id") ? "profile_id" : "calibration_id")))) {
        unknown = true;
        continue;
      }
      int level =
          violates(value.asDouble(), r, "alert")
              ? 2
              : violates(value.asDouble(), r, "warning") ? 1 : 0;
      severity = Math.max(severity, level);
      if (level > 0) {
        String reason =
            parameter
                + " is "
                + (level == 2 ? "ALERT" : "WARNING")
                + " at "
                + value
                + " "
                + r.get("unit");
        reasons.add(reason);
        String advice =
            parameter.equals("gasRelative")
                ? "Check nearby sources and ventilation; the sensor does not identify a gas."
                : parameter.startsWith("dust")
                    ? "Check dust sources, filtration and outdoor conditions before ventilating."
                    : "Check room conditions and adjust ventilation or comfort settings.";
        recommendations.add(
            obj(
                "id",
                r.get("id"),
                "message",
                advice,
                "measurementIds",
                List.of(p.path("messageId").asText()),
                "status",
                "ACTIVE"));
      }
    }
    if (!covered.containsAll(List.of("temperatureC", "humidityPct", "gasRelative"))
        || (!covered.contains("dustAdcMv") && !covered.contains("dustEstimateUgM3")))
      unknown = true;
    String condition =
        severity == 2 ? "ALERT" : severity == 1 ? "WARNING" : unknown ? "UNKNOWN" : "NORMAL";
    return obj(
        "device",
        d,
        "measurement",
        measurements.stored(row),
        "condition",
        condition,
        "reasons",
        reasons,
        "recommendations",
        recommendations);
  }

  static boolean violates(double value, Map<String, Object> r, String level) {
    Object low = r.get(level + "_low"), high = r.get(level + "_high");
    return (low != null && value <= ((Number) low).doubleValue())
        || (high != null && value >= ((Number) high).doubleValue());
  }

  private static boolean validQuality(com.fasterxml.jackson.databind.JsonNode p, String parameter) {
    return p.path("quality")
        .path(
            parameter.startsWith("dust")
                ? "dust"
                : parameter.equals("gasRelative") ? "mq135" : "sht30")
        .asText()
        .equals("VALID");
  }
}
