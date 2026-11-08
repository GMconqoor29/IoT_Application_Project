package com.airsense.monitoring;

import static com.airsense.monitoring.MonitoringModels.*;
import static com.airsense.threshold.ThresholdModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ConditionEvaluator {
  public Evaluation evaluate(Measurement reading, boolean fresh, List<Rule> rules) {
    if (reading == null || !fresh) return new Evaluation("UNKNOWN", List.of(), List.of());
    var p = reading.telemetry();
    List<String> reasons = new ArrayList<>();
    List<Recommendation> recommendations = new ArrayList<>();
    boolean unknown = false;
    int severity = 0;
    Set<String> covered = new HashSet<>();
    for (String sensor : List.of("sht30", "mq135", "dust"))
      if (!p.path("quality").path(sensor).asText().equals("VALID")) unknown = true;
    for (var rule : rules) {
      if (!rule.enabled() || !rule.deviceId().equals(p.path("deviceId").asText())) continue;
      String parameter = rule.parameter();
      covered.add(parameter);
      var value = p.path(parameter.equals("dustAdcMv") ? "raw" : "values").path(parameter);
      String binding =
          switch (parameter) {
            case "gasRelative" -> reading.gasCalibrationId();
            case "dustEstimateUgM3" -> reading.dustCalibrationId();
            case "dustAdcMv" -> reading.profileId();
            default -> null;
          };
      boolean bound = List.of("gasRelative", "dustEstimateUgM3", "dustAdcMv").contains(parameter);
      if (!value.isNumber()
          || !validQuality(p, parameter)
          || (bound
              && !Objects.equals(
                  binding,
                  parameter.equals("dustAdcMv") ? rule.profileId() : rule.calibrationId()))) {
        unknown = true;
        continue;
      }
      int level =
          violates(value.decimalValue(), rule.alert())
              ? 2
              : violates(value.decimalValue(), rule.warning()) ? 1 : 0;
      severity = Math.max(severity, level);
      if (level > 0) {
        reasons.add(
            parameter
                + " is "
                + (level == 2 ? "ALERT" : "WARNING")
                + " at "
                + value
                + " "
                + rule.unit());
        String advice =
            parameter.equals("gasRelative")
                ? "Check nearby sources and ventilation; the sensor does not identify a gas."
                : parameter.startsWith("dust")
                    ? "Check dust sources, filtration and outdoor conditions before ventilating."
                    : "Check room conditions and adjust ventilation or comfort settings.";
        recommendations.add(
            new Recommendation(rule.id(), advice, List.of(p.path("messageId").asText()), "ACTIVE"));
      }
    }
    if (!covered.containsAll(List.of("temperatureC", "humidityPct", "gasRelative"))
        || (!covered.contains("dustAdcMv") && !covered.contains("dustEstimateUgM3")))
      unknown = true;
    return new Evaluation(
        severity == 2 ? "ALERT" : severity == 1 ? "WARNING" : unknown ? "UNKNOWN" : "NORMAL",
        List.copyOf(reasons),
        List.copyOf(recommendations));
  }

  private boolean violates(BigDecimal value, Bounds bounds) {
    return bounds.low() != null && value.compareTo(bounds.low()) <= 0
        || bounds.high() != null && value.compareTo(bounds.high()) >= 0;
  }

  private boolean validQuality(JsonNode p, String parameter) {
    return p.path("quality")
        .path(
            parameter.startsWith("dust")
                ? "dust"
                : parameter.equals("gasRelative") ? "mq135" : "sht30")
        .asText()
        .equals("VALID");
  }
}
