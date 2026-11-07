package com.airsense.ingestion;

import static com.airsense.common.Json.*;

import com.airsense.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.*;
import java.nio.charset.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Ingestion {
  private final JdbcTemplate db;
  private final Contracts contracts;
  private final TransactionTemplate tx;

  public Ingestion(JdbcTemplate db, Contracts contracts, PlatformTransactionManager manager) {
    this.db = db;
    this.contracts = contracts;
    this.tx = new TransactionTemplate(manager);
  }

  public record Result(Map<String, Object> receipt, boolean inserted, String deviceId) {}

  public Result accept(String topic, byte[] bytes, boolean retained, Instant arrival) {
    String[] parts = topic.split("/", -1);
    if (parts.length != 3
        || !parts[0].equals("airsense")
        || !parts[1].matches("[A-Z0-9][A-Z0-9_-]{0,63}")) return new Result(null, false, null);
    String device = parts[1], kind = parts[2];
    if (!List.of("telemetry", "state", "metadata").contains(kind))
      return new Result(null, false, device);
    if (db.queryForObject(
            "select count(*) from devices where device_id=? and enabled", Integer.class, device)
        == 0) return new Result(null, false, device);
    JsonNode p;
    try {
      if (bytes.length > 4096)
        throw new ApiException(400, "PAYLOAD_TOO_LARGE", "Maximum payload size exceeded.");
      p =
          parse(
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(bytes))
                  .toString());
    } catch (Exception e) {
      diagnostic(topic, device, bytes, arrival);
      return new Result(null, false, device);
    }
    String message = p.path("messageId").asText("");
    boolean identity =
        message.matches(
                java.util.regex.Pattern.quote(device) + ":[a-f0-9]{8,32}:(0|[1-9][0-9]{0,15})")
            && message.length() <= 114;
    final Instant now = arrival.truncatedTo(ChronoUnit.MILLIS);
    // Serialize transactions for a device, including duplicate comparison and projections.
    return tx.execute(
        status -> {
          db.queryForObject(
              "select device_id from devices where device_id=? for update", String.class, device);
          if (kind.equals("telemetry") && identity) {
            var existing =
                db.queryForList(
                    "select canonical_payload,received_at from measurements where device_id=? and message_id=?",
                    device,
                    message);
            if (!existing.isEmpty()) {
              var row = existing.getFirst();
              if (row.get("canonical_payload").equals(canonical(p)))
                return new Result(
                    receipt(
                        device,
                        message,
                        ((Timestamp) row.get("received_at")).toInstant(),
                        "COMMITTED"),
                    false,
                    device);
              return reject(topic, device, message, p, bytes, now, "ID_CONFLICT");
            }
            var rejected =
                db.queryForList(
                    "select receipt_payload::text as receipt from ingestion_rejections where topic=? and payload_hash=? and receipt_payload is not null order by received_at limit 1",
                    topic,
                    hash(canonical(p)));
            if (!rejected.isEmpty()) {
              @SuppressWarnings("unchecked")
              Map<String, Object> saved =
                  MAPPER.convertValue(
                      parse(rejected.getFirst().get("receipt").toString()), Map.class);
              db.update(
                  "update ingestion_rejections set occurrence_count=occurrence_count+1,last_received_at=greatest(last_received_at,?) where topic=? and payload_hash=?",
                  Timestamp.from(now),
                  topic,
                  hash(canonical(p)));
              return new Result(saved, false, device);
            }
          }
          try {
            if (!p.path("deviceId").asText().equals(device)) throw bad("DEVICE_MISMATCH");
            if (p.path("schemaVersion").asInt(-1) != 1) throw bad("UNSUPPORTED_VERSION");
            contracts.validate(kind, p);
            if (kind.equals("state")) {
              if (p.path("queueDepth").asLong() > p.path("queueCapacity").asLong())
                throw bad("INVALID_PAYLOAD");
              db.update(
                  "insert into device_state(device_id,boot_id,reported_online,state_payload,state_received_at) values(?,?,?,?::jsonb,?) on conflict(device_id) do update set boot_id=excluded.boot_id,reported_online=excluded.reported_online,state_payload=excluded.state_payload,state_received_at=excluded.state_received_at,updated_at=now()",
                  device,
                  p.path("bootId").asText(),
                  p.path("online").asBoolean() && !retained,
                  p.toString(),
                  Timestamp.from(now));
              return new Result(null, false, device);
            }
            if (kind.equals("metadata")) {
              db.update(
                  "insert into device_metadata_history values(?,?,?,?::jsonb,?)",
                  UUID.randomUUID(),
                  device,
                  bytes,
                  p.toString(),
                  Timestamp.from(now));
              return new Result(null, false, device);
            }
            if (!message.equals(
                device + ":" + p.path("bootId").asText() + ":" + p.path("sequence").asLong()))
              throw bad("INVALID_PAYLOAD");
            var profiles =
                db.queryForList(
                    "select a.*,b.location_snapshot from device_boot_profiles b join acquisition_profiles a on a.profile_id=b.profile_id where b.device_id=? and b.boot_id=?",
                    device,
                    p.path("bootId").asText());
            if (profiles.isEmpty())
              throw new ApiException(
                  503, "CONFIGURATION_ERROR", "Boot profile is not provisioned.");
            var profile = profiles.getFirst();
            JsonNode definition = parse(profile.get("definition").toString());
            try {
              contracts.validate("adc-profile", definition);
            } catch (ApiException e) {
              throw new ApiException(503, "CONFIGURATION_ERROR", "Invalid trusted profile.");
            }
            for (String channel : List.of("gas", "dust"))
              for (String side : List.of("Min", "Max"))
                if (definition
                        .path(channel + "Usable" + side + "Mv")
                        .decimalValue()
                        .compareTo(
                            (java.math.BigDecimal)
                                profile.get(
                                    channel + "_usable_" + side.toLowerCase(Locale.ROOT) + "_mv"))
                    != 0)
                  throw new ApiException(
                      503, "CONFIGURATION_ERROR", "Profile definition and bounds disagree.");
            if (!definition.path("profileId").asText().equals(profile.get("profile_id")))
              throw new ApiException(503, "CONFIGURATION_ERROR", "Profile identity disagrees.");
            Instant measured =
                p.path("measuredAt").isNull() ? null : Instant.parse(p.path("measuredAt").asText());
            if (measured != null && measured.isAfter(now.plusSeconds(120)))
              throw bad("FUTURE_TIMESTAMP");
            validateHardware(p, profile);
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
            for (String key :
                List.of("temperatureC", "humidityPct", "gasRelative", "dustEstimateUgM3"))
              values.add(number(p.path("values").path(key)));
            for (String key : List.of("gasAdcCounts", "dustAdcCounts", "gasAdcMv", "dustAdcMv"))
              values.add(number(p.path("raw").path(key)));
            for (String key : List.of("sht30", "mq135", "dust"))
              values.add(p.path("quality").path(key).asText());
            values.addAll(
                Arrays.asList(
                    profile.get("profile_id"),
                    nullable(p.path("calibration").path("gasId")),
                    nullable(p.path("calibration").path("dustId")),
                    profile.get("location_snapshot"),
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
            return new Result(receipt(device, message, now, "COMMITTED"), true, device);
          } catch (ApiException e) {
            if (e.status == 503) throw e;
            if (kind.equals("telemetry") && identity)
              return reject(topic, device, message, p, bytes, now, e.code);
            return new Result(null, false, device);
          }
        });
  }

  private void validateHardware(JsonNode p, Map<String, Object> profile) {
    for (String prefix : List.of("gas", "dust")) {
      String sensor = prefix.equals("gas") ? "mq135" : "dust";
      var count = p.path("raw").path(prefix + "AdcCounts");
      var mv = p.path("raw").path(prefix + "AdcMv");
      boolean valid = p.path("quality").path(sensor).asText().equals("VALID");
      if (!count.isNull() && count.asInt() > 4095) throw bad("OUT_OF_RANGE");
      if (valid
          && (count.asInt() == 0
              || count.asInt() == 4095
              || mv.decimalValue()
                      .compareTo((java.math.BigDecimal) profile.get(prefix + "_usable_min_mv"))
                  < 0
              || mv.decimalValue()
                      .compareTo((java.math.BigDecimal) profile.get(prefix + "_usable_max_mv"))
                  > 0)) throw bad("OUT_OF_RANGE");
      var cal = p.path("calibration").path(prefix + "Id");
      if (!cal.isNull()) {
        var rows =
            db.queryForList(
                "select * from calibrations where calibration_id=? and device_id=? and profile_id=? and sensor=?",
                cal.asText(),
                p.path("deviceId").asText(),
                profile.get("profile_id"),
                sensor);
        if (rows.isEmpty()) throw bad("INVALID_CALIBRATION");
        var c = rows.getFirst();
        if (prefix.equals("gas") && valid) {
          double baseline = ((Number) c.get("baseline_adc_mv")).doubleValue();
          if (!(baseline > 0)
              || Math.abs(
                      p.path("values").path("gasRelative").asDouble() - mv.asDouble() / baseline)
                  > 0.00100000001) throw bad("INVALID_CALIBRATION");
        }
        if (prefix.equals("dust") && !p.path("values").path("dustEstimateUgM3").isNull()) {
          var d = parse(c.get("definition").toString());
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

  private Result reject(
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
    @SuppressWarnings("unchecked")
    Map<String, Object> result = MAPPER.convertValue(parse(saved), Map.class);
    capDiagnostics();
    return new Result(result, false, device);
  }

  private void diagnostic(String topic, String device, byte[] bytes, Instant now) {
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

  public static Map<String, Object> receipt(
      String device, String message, Instant now, String code) {
    return obj(
        "schemaVersion",
        1,
        "deviceId",
        device,
        "messageId",
        message,
        "status",
        code.equals("COMMITTED") ? "STORED" : "REJECTED",
        "receivedAt",
        now.toString(),
        "code",
        code,
        "retryable",
        false,
        "details",
        code.equals("COMMITTED") ? "Measurement committed." : "Rejected by contract validation.");
  }

  private static ApiException bad(String code) {
    return new ApiException(400, code, "Telemetry validation failed.");
  }

  private static Object number(JsonNode n) {
    return n.isNull() ? null : n.decimalValue();
  }

  private static String nullable(JsonNode n) {
    return n.isNull() ? null : n.asText();
  }
}
