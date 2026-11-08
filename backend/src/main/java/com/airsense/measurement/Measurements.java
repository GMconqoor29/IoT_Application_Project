package com.airsense.measurement;

import static com.airsense.common.Json.*;

import com.airsense.common.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Measurements {
  public final JdbcTemplate db;
  private final Instant started = Instant.now();

  public Measurements(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> registered(String id) {
    var rows = db.queryForList("select * from devices where device_id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Device not found.");
    return rows.getFirst();
  }

  public Map<String, Object> latestRow(String id) {
    var rows =
        db.queryForList(
            "select * from measurements where device_id=? and measured_at<=now() order by measured_at desc,boot_id desc,sequence desc limit 1",
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> stored(Map<String, Object> row) {
    return obj(
        "telemetry",
        parse(row.get("canonical_payload").toString()),
        "receivedAt",
        ((Timestamp) row.get("received_at")).toInstant().toString());
  }

  public boolean fresh(Map<String, Object> row) {
    if (row == null) return false;
    Instant now = Instant.now(), measured = ((Timestamp) row.get("measured_at")).toInstant();
    long age = Math.max(90, 3L * ((Number) row.get("report_interval_sec")).longValue());
    return !measured.isAfter(now) && !measured.isBefore(now.minusSeconds(age));
  }

  public Map<String, Object> device(String id) {
    var d = registered(id);
    var row = latestRow(id);
    var states = db.queryForList("select * from device_state where device_id=?", id);
    var state = states.isEmpty() ? Map.<String, Object>of() : states.getFirst();
    Timestamp last = (Timestamp) state.get("last_telemetry_received_at");
    Timestamp stateTime = (Timestamp) state.get("state_received_at");
    boolean online = last != null && last.toInstant().isAfter(started);
    if (stateTime != null && (last == null || stateTime.after(last)))
      online =
          Boolean.TRUE.equals(state.get("reported_online"))
              && stateTime.toInstant().isAfter(started);
    long interval = row == null ? 30 : ((Number) row.get("report_interval_sec")).longValue();
    boolean reception =
        last != null
            && !last.toInstant().isBefore(Instant.now().minusSeconds(Math.max(90, 3 * interval)));
    return obj(
        "deviceId",
        id,
        "location",
        d.get("location"),
        "firmwareVersion",
        d.get("firmware_version"),
        "online",
        online,
        "freshness",
        row == null ? "UNKNOWN" : fresh(row) && reception ? "FRESH" : "STALE",
        "lastReceivedAt",
        last == null ? null : last.toInstant().toString(),
        "reportIntervalSec",
        interval);
  }

  public List<Map<String, Object>> devices() {
    return db.queryForList("select device_id from devices order by device_id").stream()
        .map(r -> device(r.get("device_id").toString()))
        .toList();
  }

  public static Instant utc(String value) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z"))
      throw new ApiException(
          400, "INVALID_REQUEST", "Use UTC timestamps with millisecond precision or less.");
    return Instant.parse(value);
  }

  private void range(String id, Instant from, Instant to) {
    registered(id);
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
      throw new ApiException(400, "INVALID_REQUEST", "Range must be positive and at most 31 days.");
  }

  @Transactional
  public Map<String, Object> history(
      String id, String fromText, String toText, int limit, String cursor) {
    Instant from = utc(fromText), to = utc(toText);
    range(id, from, to);
    if (limit < 1 || limit > 500)
      throw new ApiException(400, "INVALID_REQUEST", "Limit must be 1 through 500.");
    UUID snapshot;
    long offset = 0;
    if (cursor == null) {
      if (db.queryForObject(
              "select count(*) from history_snapshots where expires_at>now()", Integer.class)
          >= 1000)
        throw new ApiException(503, "SERVICE_UNAVAILABLE", "History snapshot capacity reached.");
      snapshot = UUID.randomUUID();
      db.update(
          "insert into history_snapshots values(?,?,?,?,now()+interval '15 minutes')",
          snapshot,
          id,
          Timestamp.from(from),
          Timestamp.from(to));
      // A single statement captures exact MVCC-visible membership; later commits cannot enter this
      // snapshot.
      db.update(
          "insert into history_snapshot_members select ?,row_number() over(order by measured_at,message_id),id from measurements where device_id=? and measured_at>=? and measured_at<?",
          snapshot,
          id,
          Timestamp.from(from),
          Timestamp.from(to));
    } else {
      try {
        String[] parts =
            new String(
                    Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
                .split(":");
        snapshot = UUID.fromString(parts[0]);
        offset = Long.parseLong(parts[1]);
        if (parts.length != 2 || offset < 0) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new ApiException(400, "INVALID_REQUEST", "Invalid cursor.");
      }
      if (db.queryForObject(
              "select count(*) from history_snapshots where id=? and device_id=? and from_at=? and to_at=? and expires_at>now()",
              Integer.class,
              snapshot,
              id,
              Timestamp.from(from),
              Timestamp.from(to))
          != 1) throw new ApiException(400, "INVALID_REQUEST", "Expired or incompatible cursor.");
    }
    var rows =
        db.queryForList(
            "select m.*,s.ordinal from history_snapshot_members s join measurements m on m.id=s.measurement_id where s.snapshot_id=? and s.ordinal>? order by s.ordinal limit ?",
            snapshot,
            offset,
            limit + 1);
    boolean more = rows.size() > limit;
    if (more) rows = rows.subList(0, limit);
    String next =
        more
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (snapshot + ":" + rows.getLast().get("ordinal"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            : null;
    return obj("items", rows.stream().map(this::stored).toList(), "nextCursor", next);
  }

  public Map<String, Object> stats(String id, String fromText, String toText) {
    Instant from = utc(fromText), to = utc(toText);
    range(id, from, to);
    var result = new LinkedHashMap<String, Object>();
    String[][] channels = {
      {"temperatureC", "temperature_c", "sht30_quality", "degC", ""},
      {"humidityPct", "humidity_pct", "sht30_quality", "%RH", ""},
      {"gasRelative", "gas_relative", "mq135_quality", "ratio", "gas_calibration_id"},
      {"dustAdcMv", "dust_adc_mv", "dust_quality", "mV", "profile_id"},
      {"dustEstimateUgM3", "dust_estimate_ug_m3", "dust_quality", "ug/m3", "dust_calibration_id"}
    };
    // One read-only repeatable-read transaction would also work; aggregate all channels from one
    // SQL statement snapshot.
    StringJoiner select = new StringJoiner(",");
    for (int i = 0; i < channels.length; i++) {
      var c = channels[i];
      String filter = " filter(where " + c[2] + "='VALID' and " + c[1] + " is not null)";
      for (String op : List.of("min", "max", "avg", "count"))
        select.add(op + "(" + c[1] + ")" + filter + " as " + op + i);
      if (!c[4].isEmpty()) {
        select.add("count(distinct " + c[4] + ")" + filter + " as bindings" + i);
        select.add("min(" + c[4] + ")" + filter + " as binding" + i);
      }
    }
    var row =
        db.queryForMap(
            "select "
                + select
                + " from measurements where device_id=? and measured_at>=? and measured_at<?",
            id,
            Timestamp.from(from),
            Timestamp.from(to));
    for (int i = 0; i < channels.length; i++) {
      var c = channels[i];
      if (!c[4].isEmpty() && ((Number) row.get("bindings" + i)).intValue() > 1)
        throw new ApiException(
            409,
            "CALIBRATION_CONFLICT",
            "Choose a range containing one calibration/acquisition profile.");
      result.put(
          c[0],
          obj(
              "unit",
              c[3],
              "min",
              row.get("min" + i),
              "max",
              row.get("max" + i),
              "mean",
              row.get("avg" + i),
              "validCount",
              row.get("count" + i),
              "calibrationId",
              i == 2 || i == 4 ? row.get("binding" + i) : null));
    }
    return obj("deviceId", id, "from", fromText, "to", toText, "parameters", result);
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    db.update("delete from history_snapshots where expires_at<now()");
    db.update(
        "delete from admin_sessions where expires_at<now() or invalidated_at<now()-interval '1 day'");
  }
}
