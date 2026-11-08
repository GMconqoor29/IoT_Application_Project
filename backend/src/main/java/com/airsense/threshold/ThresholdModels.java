package com.airsense.threshold;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.List;

public final class ThresholdModels {
  private ThresholdModels() {}

  public record Bounds(BigDecimal low, BigDecimal high) {}

  public record Rule(
      String id,
      String deviceId,
      String parameter,
      String unit,
      String calibrationId,
      long revision,
      String direction,
      Bounds warning,
      Bounds alert,
      Bounds recovery,
      boolean enabled,
      @JsonIgnore String profileId) {}

  public record Update(
      long expectedRevision,
      String direction,
      Bounds warning,
      Bounds alert,
      Bounds recovery,
      boolean enabled) {}

  public record RuleList(List<Rule> items) {}
}
