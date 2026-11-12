package com.airsense.ingestion;

import static com.airsense.common.JsonSupport.*;
import static com.airsense.ingestion.IngestionModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRepository {
  private final JdbcTemplate db;

  public IngestionRepository(JdbcTemplate db) {
    this.db = db;
  }

  public boolean enabled(String device) {
    return db.queryForObject(
            "select count(*) from devices where device_id=? and enabled", Integer.class, device)
        > 0;
  }

  public void lock(String device) {
    db.queryForObject(
        "select device_id from devices where device_id=? for update", String.class, device);
  }

  public Optional<Accepted> accepted(String device, String message) {
    return db
        .query(
            "select canonical_payload,received_at from measurements where device_id=? and message_id=?",
            (r, n) ->
                new Accepted(
                    r.getString("canonical_payload"), r.getTimestamp("received_at").toInstant()),
            device,
            message)
        .stream()
        .findFirst();
  }

  public Optional<Receipt> rejected(String topic, JsonNode p, Instant now) {
    var rows =
        db.queryForList(
            "select receipt_payload::text from ingestion_rejections where topic=? and payload_hash=? and receipt_payload is not null order by received_at limit 1",
            String.class,
            topic,
            hash(canonical(p)));
    if (rows.isEmpty()) return Optional.empty();
    db.update(
        "update ingestion_rejections set occurrence_count=occurrence_count+1,last_received_at=greatest(last_received_at,?) where topic=? and payload_hash=?",
        Timestamp.from(now),
        topic,
        hash(canonical(p)));
    return Optional.of(MAPPER.convertValue(parse(rows.getFirst()), Receipt.class));
  }

  public Optional<Profile> profile(String device, String boot) {
    return db
        .query(
            "select a.*,b.location_snapshot from device_boot_profiles b join acquisition_profiles a on a.profile_id=b.profile_id where b.device_id=? and b.boot_id=?",
            (r, n) ->
                new Profile(
                    r.getString("profile_id"),
                    r.getString("location_snapshot"),
                    parse(r.getString("definition")),
                    r.getBigDecimal("gas_usable_min_mv"),
                    r.getBigDecimal("gas_usable_max_mv"),
                    r.getBigDecimal("dust_usable_min_mv"),
                    r.getBigDecimal("dust_usable_max_mv")),
            device,
            boot)
        .stream()
        .findFirst();
  }

  public Calibration calibration(String id, String device, String profile, String sensor) {
    if (id == null) return null;
    return db
        .query(
            "select baseline_adc_mv,definition from calibrations where calibration_id=? and device_id=? and profile_id=? and sensor=?",
            (r, n) ->
                new Calibration(
                    r.getBigDecimal("baseline_adc_mv"), parse(r.getString("definition"))),
            id,
            device,
            profile,
            sensor)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void state(Incoming incoming) {
    var p = incoming.payload();
    db.update(
        "insert into device_state(device_id,boot_id,reported_online,state_payload,state_received_at) values(?,?,?,?::jsonb,?) on conflict(device_id) do update set boot_id=excluded.boot_id,reported_online=excluded.reported_online,state_payload=excluded.state_payload,state_received_at=excluded.state_received_at,updated_at=now()",
        incoming.deviceId(),
        p.path("bootId").asText(),
        p.path("online").asBoolean() && !incoming.retained(),
        p.toString(),
        Timestamp.from(incoming.receivedAt()));
  }

  public void metadata(Incoming incoming) {
    db.update(
        "insert into device_metadata_history values(?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        incoming.deviceId(),
        incoming.original(),
        incoming.payload().toString(),
        Timestamp.from(incoming.receivedAt()));
  }

  public void insert(Incoming incoming, Profile profile, Instant measured) {
    var p = incoming.payload();
    var device = incoming.deviceId();
    var message = incoming.messageId();
    var bytes = incoming.original();
    var now = incoming.receivedAt();
    UUID id = UUID.randomUUID();
    List<Object> values =
        new ArrayList<>(
            Arrays.asList(
                id,
                device,
                message,
                p.path("bootId").asText(),
                p.path("sequence").asLong(),
                1,
                measured == null ? null : Timestamp.from(measured),
                Timestamp.from(now),
                p.path("uptimeMs").asLong(),
                p.path("reportIntervalSec").asInt(),
                p.path("timeQuality").asText()));
    for (String key : List.of("temperatureC", "humidityPct", "gasRelative", "dustEstimateUgM3"))
      values.add(number(p.path("values").path(key)));
    for (String key : List.of("gasAdcCounts", "dustAdcCounts", "gasAdcMv", "dustAdcMv"))
      values.add(number(p.path("raw").path(key)));
    for (String key : List.of("sht30", "mq135", "dust"))
      values.add(p.path("quality").path(key).asText());
    values.addAll(
        Arrays.asList(
            profile.profileId(),
            nullable(p.path("calibration").path("gasId")),
            nullable(p.path("calibration").path("dustId")),
            profile.location(),
            bytes,
            canonical(p),
            hash(canonical(p))));
    db.update(
        "insert into measurements(id,device_id,message_id,boot_id,sequence,schema_version,measured_at,received_at,uptime_ms,report_interval_sec,time_quality,temperature_c,humidity_pct,gas_relative,dust_estimate_ug_m3,gas_adc_counts,dust_adc_counts,gas_adc_mv,dust_adc_mv,sht30_quality,mq135_quality,dust_quality,profile_id,gas_calibration_id,dust_calibration_id,location_snapshot,original_payload,canonical_payload,canonical_payload_hash) values("
            + String.join(",", Collections.nCopies(values.size(), "?"))
            + ")",
        values.toArray());
    db.update(
        "insert into device_state(device_id,last_telemetry_received_at) values(?,?) on conflict(device_id) do update set last_telemetry_received_at=greatest(device_state.last_telemetry_received_at,excluded.last_telemetry_received_at),updated_at=now()",
        device,
        Timestamp.from(now));
    db.update(
        "update device_state set latest_measurement_id=(select id from measurements where device_id=? and measured_at is not null order by measured_at desc,boot_id desc,sequence desc limit 1) where device_id=?",
        device,
        device);
  }

  public Result reject(
      String topic,
      String device,
      String message,
      JsonNode p,
      byte[] bytes,
      Instant now,
      String code) {
    var r = receipt(device, message, now, code);
    String encoded;
    try {
      encoded = MAPPER.writeValueAsString(r);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    db.update(
        "insert into ingestion_rejections(id,device_id,topic,message_id,code,detail,payload_sample,payload_hash,received_at,last_received_at,receipt_payload) values(?,?,?,?,?,?,?,?,?,?,?::jsonb) on conflict(topic,payload_hash,code) do update set occurrence_count=ingestion_rejections.occurrence_count+1,last_received_at=greatest(ingestion_rejections.last_received_at,excluded.last_received_at)",
        UUID.randomUUID(),
        device,
        topic,
        message,
        code,
        "Rejected by contract validation.",
        bytes,
        hash(canonical(p)),
        Timestamp.from(now),
        Timestamp.from(now),
        encoded);
    var saved =
        db.queryForObject(
            "select receipt_payload::text from ingestion_rejections where topic=? and payload_hash=? and code=?",
            String.class,
            topic,
            hash(canonical(p)),
            code);
    Receipt result = MAPPER.convertValue(parse(saved), Receipt.class);
    capDiagnostics();
    return new Result(result, false, device);
  }

  public void diagnostic(String topic, String device, byte[] bytes, Instant now) {
    db.update(
        "insert into ingestion_rejections(id,device_id,topic,code,detail,payload_sample,payload_hash,received_at,last_received_at) values(?,?,?,'INVALID_PAYLOAD','Untrustworthy envelope.',?,?,?,?) on conflict(topic,payload_hash,code) do update set occurrence_count=ingestion_rejections.occurrence_count+1,last_received_at=greatest(ingestion_rejections.last_received_at,excluded.last_received_at)",
        UUID.randomUUID(),
        device,
        topic,
        Arrays.copyOf(bytes, Math.min(bytes.length, 4096)),
        hash(bytes),
        Timestamp.from(now),
        Timestamp.from(now));
    capDiagnostics();
  }

  private void capDiagnostics() {
    db.update(
        "delete from ingestion_rejections where id in(select id from ingestion_rejections order by last_received_at desc offset 10000)");
  }

  private static Object number(JsonNode n) {
    return n.isNull() ? null : n.decimalValue();
  }

  private static String nullable(JsonNode n) {
    return n.isNull() ? null : n.asText();
  }
}
