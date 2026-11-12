package com.airsense.monitoring;

import static com.airsense.monitoring.MonitoringModels.*;

import com.airsense.common.ApiException;
import com.airsense.threshold.ThresholdService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringService {
  private final MonitoringRepository repository;
  private final ThresholdService thresholds;
  private final FreshnessPolicy freshness;
  private final ConditionEvaluator evaluator;
  private final Clock clock;

  public MonitoringService(
      MonitoringRepository repository,
      ThresholdService thresholds,
      FreshnessPolicy freshness,
      ConditionEvaluator evaluator,
      Clock clock) {
    this.repository = repository;
    this.thresholds = thresholds;
    this.freshness = freshness;
    this.evaluator = evaluator;
    this.clock = clock;
  }

  public DeviceList devices() {
    return new DeviceList(
        repository.deviceIds().stream()
            .map(
                id ->
                    freshness.device(repository.device(id), repository.latest(id, clock.instant())))
            .toList());
  }

  public Latest latest(String id) {
    var evidence = repository.device(id);
    var row = repository.latest(id, clock.instant());
    var device = freshness.device(evidence, row);
    var result = evaluator.evaluate(row, device.freshness().equals("FRESH"), thresholds.list(id));
    return new Latest(
        device,
        row == null ? null : new StoredMeasurement(row.telemetry(), row.receivedAt().toString()),
        result.condition(),
        result.reasons(),
        result.recommendations());
  }

  public static Instant utc(String value) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z"))
      throw new ApiException(
          400, "INVALID_REQUEST", "Use UTC timestamps with millisecond precision or less.");
    try {
      return Instant.parse(value);
    } catch (DateTimeException e) {
      throw new ApiException(400, "INVALID_REQUEST", "Invalid UTC timestamp.");
    }
  }

  private Range range(String id, String from, String to) {
    repository.device(id);
    var range = new Range(utc(from), utc(to));
    if (!range.from().isBefore(range.to())
        || Duration.between(range.from(), range.to()).compareTo(Duration.ofDays(31)) > 0)
      throw new ApiException(400, "INVALID_REQUEST", "Range must be positive and at most 31 days.");
    return range;
  }

  @Transactional
  public HistoryPage history(String id, String from, String to, int limit, String cursor) {
    var range = range(id, from, to);
    if (limit < 1 || limit > 500)
      throw new ApiException(400, "INVALID_REQUEST", "Limit must be 1 through 500.");
    UUID snapshot;
    long offset = 0;
    if (cursor == null) {
      if (repository.snapshotCount() >= 1000)
        throw new ApiException(503, "SERVICE_UNAVAILABLE", "History snapshot capacity reached.");
      snapshot = UUID.randomUUID();
      repository.createSnapshot(snapshot, id, range);
    } else {
      try {
        var parts =
            new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        snapshot = UUID.fromString(parts[0]);
        offset = Long.parseLong(parts[1]);
        if (offset < 0) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new ApiException(400, "INVALID_REQUEST", "Invalid cursor.");
      }
      if (!repository.validSnapshot(snapshot, id, range))
        throw new ApiException(400, "INVALID_REQUEST", "Expired or incompatible cursor.");
    }
    var rows = repository.history(snapshot, offset, limit + 1);
    boolean more = rows.size() > limit;
    if (more) rows = rows.subList(0, limit);
    String next =
        more
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (snapshot + ":" + rows.getLast().ordinal()).getBytes(StandardCharsets.UTF_8))
            : null;
    return new HistoryPage(rows.stream().map(HistoryMember::measurement).toList(), next);
  }

  public Statistics stats(String id, String from, String to) {
    var range = range(id, from, to);
    return new Statistics(id, from, to, repository.statistics(id, range.from(), range.to()));
  }
}
