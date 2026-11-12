package com.airsense.threshold;

import static com.airsense.threshold.ThresholdModels.*;

import com.airsense.common.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ThresholdValidator {
  public void validate(Update p) {
    if (!List.of("UPPER", "LOWER", "RANGE").contains(p.direction())) throw invalid();
    List<BigDecimal> ordered = new ArrayList<>();
    for (Bounds b : List.of(p.alert(), p.warning(), p.recovery())) {
      if (p.direction().equals("UPPER")) {
        if (b.low() != null) throw invalid();
      } else {
        if (b.low() == null) throw invalid();
        ordered.add(b.low());
      }
    }
    for (Bounds b : List.of(p.recovery(), p.warning(), p.alert())) {
      if (p.direction().equals("LOWER")) {
        if (b.high() != null) throw invalid();
      } else {
        if (b.high() == null) throw invalid();
        ordered.add(b.high());
      }
    }
    for (BigDecimal b : ordered) if (!Double.isFinite(b.doubleValue())) throw invalid();
    for (int i = 1; i < ordered.size(); i++)
      if (ordered.get(i - 1).compareTo(ordered.get(i)) >= 0) throw invalid();
  }

  private static ApiException invalid() {
    return new ApiException(
        400,
        "INVALID_REQUEST",
        "Threshold boundaries are not strictly ordered for this direction.");
  }
}
