package com.airsense.monitoring;

import static com.airsense.monitoring.MonitoringModels.*;

import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class FreshnessPolicy {
  private final Clock clock;
  private final Instant started;

  public FreshnessPolicy(Clock clock) {
    this.clock = clock;
    this.started = clock.instant();
  }

  public Device device(DeviceEvidence evidence, Measurement reading) {
    Instant now = clock.instant(),
        last = evidence.lastReceivedAt(),
        state = evidence.stateReceivedAt();
    long interval = reading == null ? 30 : reading.reportIntervalSec(),
        age = Math.max(90, 3 * interval);
    boolean online = last != null && last.isAfter(started);
    if (state != null && (last == null || state.isAfter(last)))
      online = evidence.reportedOnline() && state.isAfter(started);
    boolean reception = last != null && !last.isBefore(now.minusSeconds(age));
    boolean acquisition =
        reading != null
            && reading.measuredAt() != null
            && !reading.measuredAt().isAfter(now)
            && !reading.measuredAt().isBefore(now.minusSeconds(age));
    return new Device(
        evidence.deviceId(),
        evidence.location(),
        evidence.firmwareVersion(),
        online,
        reading == null ? "UNKNOWN" : acquisition && reception ? "FRESH" : "STALE",
        last == null ? null : last.toString(),
        interval);
  }
}
