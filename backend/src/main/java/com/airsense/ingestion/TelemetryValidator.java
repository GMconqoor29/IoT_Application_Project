package com.airsense.ingestion;

import static com.airsense.ingestion.IngestionModels.*;

import com.airsense.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TelemetryValidator {
  private final ContractValidator contracts;

  public TelemetryValidator(ContractValidator contracts) {
    this.contracts = contracts;
  }

  public boolean trustworthyIdentity(String device, String message) {
    return message.matches(
            java.util.regex.Pattern.quote(device) + ":[a-f0-9]{8,32}:(0|[1-9][0-9]{0,15})")
        && message.length() <= 114;
  }

  public void envelope(Incoming incoming) {
    var p = incoming.payload();
    if (!p.path("deviceId").asText().equals(incoming.deviceId())) throw bad("DEVICE_MISMATCH");
    if (p.path("schemaVersion").asInt(-1) != 1) throw bad("UNSUPPORTED_VERSION");
    contracts.validate(incoming.kind(), p);
    if (incoming.kind().equals("state")
        && p.path("queueDepth").asLong() > p.path("queueCapacity").asLong())
      throw bad("INVALID_PAYLOAD");
  }

  public void identity(Incoming incoming) {
    var p = incoming.payload();
    if (!incoming
        .messageId()
        .equals(
            incoming.deviceId()
                + ":"
                + p.path("bootId").asText()
                + ":"
                + p.path("sequence").asLong())) throw bad("INVALID_PAYLOAD");
  }

  public Instant validate(Incoming incoming, TrustedContext context) {
    var profile = context.profile();
    var definition = profile.definition();
    try {
      contracts.validate("adc-profile", definition);
    } catch (ApiException e) {
      throw configuration("Invalid trusted profile.");
    }
    for (String channel : List.of("gas", "dust")) {
      if (definition
                  .path(channel + "UsableMinMv")
                  .decimalValue()
                  .compareTo(profile.minimum(channel))
              != 0
          || definition
                  .path(channel + "UsableMaxMv")
                  .decimalValue()
                  .compareTo(profile.maximum(channel))
              != 0) throw configuration("Profile definition and bounds disagree.");
    }
    if (!definition.path("profileId").asText().equals(profile.profileId()))
      throw configuration("Profile identity disagrees.");
    var p = incoming.payload();
    Instant measured =
        p.path("measuredAt").isNull() ? null : Instant.parse(p.path("measuredAt").asText());
    if (measured != null && measured.isAfter(incoming.receivedAt().plusSeconds(120)))
      throw bad("FUTURE_TIMESTAMP");
    validateHardware(p, context);
    return measured;
  }

  private void validateHardware(JsonNode p, TrustedContext context) {
    var profile = context.profile();
    for (String prefix : List.of("gas", "dust")) {
      String sensor = prefix.equals("gas") ? "mq135" : "dust";
      var count = p.path("raw").path(prefix + "AdcCounts");
      var mv = p.path("raw").path(prefix + "AdcMv");
      boolean valid = p.path("quality").path(sensor).asText().equals("VALID");
      if (!count.isNull() && count.asInt() > 4095) throw bad("OUT_OF_RANGE");
      if (valid
          && (count.asInt() == 0
              || count.asInt() == 4095
              || mv.decimalValue().compareTo(profile.minimum(prefix)) < 0
              || mv.decimalValue().compareTo(profile.maximum(prefix)) > 0))
        throw bad("OUT_OF_RANGE");
      var cal = p.path("calibration").path(prefix + "Id");
      if (!cal.isNull()) {
        var c = prefix.equals("gas") ? context.gas() : context.dust();
        if (c == null) throw bad("INVALID_CALIBRATION");
        if (prefix.equals("gas") && valid) {
          double baseline = c.baseline() == null ? 0 : c.baseline().doubleValue();
          if (!(baseline > 0)
              || Math.abs(
                      p.path("values").path("gasRelative").asDouble() - mv.asDouble() / baseline)
                  > 0.00100000001) throw bad("INVALID_CALIBRATION");
        }
        if (prefix.equals("dust") && !p.path("values").path("dustEstimateUgM3").isNull()) {
          var d = c.definition();
          if (!d.path("equation").asText().equals("linear-adc-mv")
              || !d.has("slope")
              || !d.has("intercept")
              || !d.has("minMv")
              || !d.has("maxMv")
              || !d.has("maxUgM3")) throw bad("INVALID_CALIBRATION");
          double expected =
              mv.asDouble() * d.path("slope").asDouble() + d.path("intercept").asDouble();
          for (String key : List.of("slope", "intercept", "minMv", "maxMv", "maxUgM3"))
            if (!d.path(key).isNumber() || !Double.isFinite(d.path(key).asDouble()))
              throw bad("INVALID_CALIBRATION");
          if (!Double.isFinite(expected)
              || d.path("minMv").asDouble() >= d.path("maxMv").asDouble()
              || d.path("maxUgM3").asDouble() < 0) throw bad("INVALID_CALIBRATION");
          if (mv.asDouble() < d.path("minMv").asDouble()
              || mv.asDouble() > d.path("maxMv").asDouble()
              || expected < 0
              || expected > d.path("maxUgM3").asDouble()
              || Math.abs(expected - p.path("values").path("dustEstimateUgM3").asDouble())
                  > 0.00100000001) throw bad("INVALID_CALIBRATION");
        }
      }
    }
  }

  private static ApiException configuration(String message) {
    return new ApiException(503, "CONFIGURATION_ERROR", message);
  }

  private static ApiException bad(String code) {
    return new ApiException(400, code, "Telemetry validation failed.");
  }
}
