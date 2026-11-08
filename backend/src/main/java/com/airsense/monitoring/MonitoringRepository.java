package com.airsense.monitoring;

import static com.airsense.common.JsonSupport.*;
import static com.airsense.monitoring.MonitoringModels.*;

import com.airsense.common.ApiException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MonitoringRepository {
  private final JdbcTemplate db;

  public MonitoringRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<String> deviceIds() {
    return db.queryForList("select device_id from devices order by device_id", String.class);
  }

  public DeviceEvidence device(String id) {
    return db
        .query(
            "select d.device_id,d.location,d.firmware_version,s.last_telemetry_received_at,s.state_received_at,s.reported_online from devices d left join device_state s using(device_id) where d.device_id=?",
            (r, n) ->
                new DeviceEvidence(
                    r.getString("device_id"),
                    r.getString("location"),
                    r.getString("firmware_version"),
                    instant(r, "last_telemetry_received_at"),
                    instant(r, "state_received_at"),
                    r.getBoolean("reported_online")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Device not found."));
  }

  public Measurement latest(String id, Instant now) {
    return db
        .query(
            "select canonical_payload,received_at,measured_at,report_interval_sec,profile_id,gas_calibration_id,dust_calibration_id from measurements where device_id=? and measured_at<=? order by measured_at desc,boot_id desc,sequence desc limit 1",
            (r, n) ->
                new Measurement(
                    parse(r.getString("canonical_payload")),
                    instant(r, "received_at"),
                    instant(r, "measured_at"),
                    r.getInt("report_interval_sec"),
                    r.getString("profile_id"),
                    r.getString("gas_calibration_id"),
                    r.getString("dust_calibration_id")),
            id,
            Timestamp.from(now))
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static Instant instant(ResultSet r, String key) throws SQLException {
    var value = r.getTimestamp(key);
    return value == null ? null : value.toInstant();
  }

  public int snapshotCount() {
    return db.queryForObject(
        "select count(*) from history_snapshots where expires_at>now()", Integer.class);
  }

  public void createSnapshot(UUID snapshot, String id, Range range) {
    db.update(
        "insert into history_snapshots values(?,?,?,?,now()+interval '15 minutes')",
        snapshot,
        id,
        Timestamp.from(range.from()),
        Timestamp.from(range.to()));
    // One statement freezes MVCC-visible membership, including deterministic tie ordering.
    db.update(
        "insert into history_snapshot_members select ?,row_number() over(order by measured_at,message_id),id from measurements where device_id=? and measured_at>=? and measured_at<?",
        snapshot,
        id,
        Timestamp.from(range.from()),
        Timestamp.from(range.to()));
  }

  public boolean validSnapshot(UUID snapshot, String id, Range range) {
    return db.queryForObject(
            "select count(*) from history_snapshots where id=? and device_id=? and from_at=? and to_at=? and expires_at>now()",
            Integer.class,
            snapshot,
            id,
            Timestamp.from(range.from()),
            Timestamp.from(range.to()))
        == 1;
  }

  public List<HistoryMember> history(UUID snapshot, long offset, int limit) {
    return db.query(
        "select m.canonical_payload,m.received_at,s.ordinal from history_snapshot_members s join measurements m on m.id=s.measurement_id where s.snapshot_id=? and s.ordinal>? order by s.ordinal limit ?",
        (r, n) ->
            new HistoryMember(
                new StoredMeasurement(
                    parse(r.getString("canonical_payload")), instant(r, "received_at").toString()),
                r.getLong("ordinal")),
        snapshot,
        offset,
        limit);
  }

  public void cleanup() {
    db.update("delete from history_snapshots where expires_at<now()");
  }

  public Map<String, ParameterStatistics> statistics(String id, Instant from, Instant to) {
    var result = new LinkedHashMap<String, ParameterStatistics>();
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
          new ParameterStatistics(
              c[3],
              (java.math.BigDecimal) row.get("min" + i),
              (java.math.BigDecimal) row.get("max" + i),
              (java.math.BigDecimal) row.get("avg" + i),
              ((Number) row.get("count" + i)).longValue(),
              i == 2 || i == 4 ? (String) row.get("binding" + i) : null));
    }
    return result;
  }
}
