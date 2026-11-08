package com.airsense.threshold;

import static com.airsense.threshold.ThresholdModels.*;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ThresholdRepository {
  private final JdbcTemplate db;

  public ThresholdRepository(JdbcTemplate db) {
    this.db = db;
  }

  public boolean deviceExists(String id) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "select exists(select 1 from devices where device_id=?)", Boolean.class, id));
  }

  public List<Rule> list(String device) {
    return db.query(
        "select * from thresholds where device_id=? order by id", (r, n) -> map(r), device);
  }

  public Optional<Rule> find(String id) {
    return db.query("select * from thresholds where id=?", (r, n) -> map(r), id).stream()
        .findFirst();
  }

  public Optional<Rule> update(String id, Update p, UUID actor) {
    return db
        .query(
            "update thresholds set revision=revision+1,direction=?,warning_low=?,warning_high=?,alert_low=?,alert_high=?,recovery_low=?,recovery_high=?,enabled=?,updated_by=?,updated_at=now() where id=? and revision=? returning *",
            (r, n) -> map(r),
            p.direction(),
            p.warning().low(),
            p.warning().high(),
            p.alert().low(),
            p.alert().high(),
            p.recovery().low(),
            p.recovery().high(),
            p.enabled(),
            actor,
            id,
            p.expectedRevision())
        .stream()
        .findFirst();
  }

  private static Rule map(ResultSet r) throws SQLException {
    return new Rule(
        r.getString("id"),
        r.getString("device_id"),
        r.getString("parameter"),
        r.getString("unit"),
        r.getString("calibration_id"),
        r.getLong("revision"),
        r.getString("direction"),
        bounds(r, "warning"),
        bounds(r, "alert"),
        bounds(r, "recovery"),
        r.getBoolean("enabled"),
        r.getString("profile_id"));
  }

  private static Bounds bounds(ResultSet r, String prefix) throws SQLException {
    return new Bounds(r.getBigDecimal(prefix + "_low"), r.getBigDecimal(prefix + "_high"));
  }
}
