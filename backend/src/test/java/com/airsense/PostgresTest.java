package com.airsense;

import static org.junit.jupiter.api.Assertions.*;

import com.airsense.admin.Admin;
import com.airsense.analysis.Conditions;
import com.airsense.common.*;
import com.airsense.ingestion.Ingestion;
import com.airsense.measurement.Measurements;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.*;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTest {
  JdbcTemplate db;
  Ingestion ingestion;
  Measurements measurements;
  Conditions conditions;
  Admin admin;
  Contracts contracts;
  long sequence = 1000;
  String testSchema;
  DriverManagerDataSource source;

  @BeforeAll
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            System.getenv("TEST_DATABASE_URL"),
            System.getenv().getOrDefault("TEST_DATABASE_USER", "airsense"),
            System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
    testSchema = "test_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(ds).execute("create schema " + testSchema);
    ds.setUrl(
        System.getenv("TEST_DATABASE_URL")
            + (System.getenv("TEST_DATABASE_URL").contains("?") ? "&" : "?")
            + "currentSchema="
            + testSchema);
    source = ds;
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    if (db.queryForObject("select count(*) from devices", Integer.class) == 0)
      try (var connection = ds.getConnection()) {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("provision/simulator.sql"));
      }
    contracts = new Contracts();
    var manager = new DataSourceTransactionManager(ds);
    ingestion = new Ingestion(db, contracts, manager);
    measurements = new Measurements(db);
    conditions = new Conditions(db, measurements);
    conditions.refresh();
    admin = new Admin(db, contracts, conditions, manager, "http://localhost:8080", false);
  }

  @AfterAll
  void cleanup() {
    if (source != null && testSchema.matches("test_[a-f0-9]{32}"))
      new JdbcTemplate(source).execute("drop schema " + testSchema + " cascade");
  }

  ObjectNode payload() throws Exception {
    var p =
        (ObjectNode)
            Json.parse(
                Files.readString(Path.of("../contracts/fixtures/valid/telemetry-normal.json")));
    long seq = ++sequence;
    p.put("sequence", seq);
    p.put("messageId", "AIRSENSE-001:b7f3a901:" + seq);
    p.put(
        "measuredAt",
        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(1).toString());
    return p;
  }

  Ingestion.Result ingest(ObjectNode p) {
    return ingestion.accept(
        "airsense/AIRSENSE-001/telemetry",
        p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        false,
        Instant.now());
  }

  @Test
  void commitReplayConflictAndImmutableEvidence() throws Exception {
    var p = payload();
    var first = ingest(p);
    assertTrue(first.inserted());
    contracts.validate("receipt", Json.MAPPER.valueToTree(first.receipt()));
    assertEquals(first.receipt(), ingest(p).receipt());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from measurements where message_id=?",
            Integer.class,
            p.path("messageId").asText()));
    ((ObjectNode) p.path("values")).put("temperatureC", 28);
    assertEquals("ID_CONFLICT", ingest(p).receipt().get("code"));
    assertEquals("ID_CONFLICT", ingest(p).receipt().get("code"));
    assertThrows(
        Exception.class,
        () ->
            db.update(
                "update measurements set temperature_c=0 where message_id=?",
                p.path("messageId").asText()));
  }

  @Test
  void concurrentDuplicatesCommitOnce() throws Exception {
    var p = payload();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Callable<Ingestion.Result>>();
      for (int i = 0; i < 8; i++) calls.add(() -> ingest(p));
      var results = pool.invokeAll(calls);
      int inserted = 0;
      for (var future : results) if (future.get().inserted()) inserted++;
      assertEquals(1, inserted);
    }
  }

  @Test
  void missingProfileRemainsRetryable() throws Exception {
    var p = payload();
    p.put("bootId", "abcdef00");
    p.put("messageId", "AIRSENSE-001:abcdef00:" + p.path("sequence").asLong());
    var error = assertThrows(ApiException.class, () -> ingest(p));
    assertEquals("CONFIGURATION_ERROR", error.code);
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from measurements where message_id=?",
            Integer.class,
            p.path("messageId").asText()));
  }

  @Test
  void rejectsRailsRatiosAndFutureClock() throws Exception {
    var p = payload();
    ((ObjectNode) p.path("raw")).put("gasAdcCounts", 4095);
    assertEquals("OUT_OF_RANGE", ingest(p).receipt().get("code"));
    p = payload();
    ((ObjectNode) p.path("values")).put("gasRelative", 9);
    assertEquals("INVALID_CALIBRATION", ingest(p).receipt().get("code"));
    p = payload();
    p.put(
        "measuredAt",
        Instant.now()
            .plusSeconds(300)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            .toString());
    assertEquals("FUTURE_TIMESTAMP", ingest(p).receipt().get("code"));
  }

  @Test
  void unsyncedStoredButExcluded() throws Exception {
    var p = payload();
    p.putNull("measuredAt");
    p.put("timeQuality", "UNSYNCED");
    assertTrue(ingest(p).inserted());
    assertNotEquals(
        p.path("messageId").asText(),
        measurements.latestRow("AIRSENSE-001") == null
            ? null
            : measurements.latestRow("AIRSENSE-001").get("message_id"));
  }

  @Test
  void historySnapshotExcludesLaterInsert() throws Exception {
    var a = payload();
    a.put("measuredAt", "2026-10-01T01:00:00Z");
    ingest(a);
    var b = payload();
    b.put("measuredAt", "2026-10-01T02:00:00Z");
    ingest(b);
    var first =
        measurements.history(
            "AIRSENSE-001", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", 1, null);
    assertNotNull(first.get("nextCursor"));
    var late = payload();
    late.put("measuredAt", "2026-10-01T01:30:00Z");
    ingest(late);
    var second =
        measurements.history(
            "AIRSENSE-001",
            "2026-10-01T00:00:00Z",
            "2026-10-02T00:00:00Z",
            1,
            first.get("nextCursor").toString());
    assertNull(second.get("nextCursor"));
    assertTrue(
        Json.MAPPER
            .valueToTree(second)
            .path("items")
            .get(0)
            .path("telemetry")
            .path("messageId")
            .asText()
            .equals(b.path("messageId").asText()));
    assertThrows(
        ApiException.class,
        () ->
            measurements.history(
                "AIRSENSE-001",
                "2026-10-01T00:00:01Z",
                "2026-10-02T00:00:00Z",
                1,
                first.get("nextCursor").toString()));
  }

  @Test
  void emptyStatisticsAndImmediateCondition() throws Exception {
    var stats =
        Json.MAPPER.valueToTree(
            measurements.stats("AIRSENSE-001", "2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z"));
    assertEquals(0, stats.path("parameters").path("gasRelative").path("validCount").asInt());
    assertTrue(stats.path("parameters").path("gasRelative").path("mean").isNull());
    var p = payload();
    p.put("measuredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
    ((ObjectNode) p.path("raw")).put("gasAdcMv", 1400);
    ((ObjectNode) p.path("values")).put("gasRelative", 1.4);
    ingest(p);
    assertEquals("ALERT", conditions.latest("AIRSENSE-001").get("condition"));
  }

  @Test
  void invalidDustNeverTriggersEnvironmentalAlert() throws Exception {
    var p = payload();
    p.put("measuredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
    ((ObjectNode) p.path("raw")).put("dustAdcMv", 3100);
    ((ObjectNode) p.path("raw")).put("dustAdcCounts", 4095);
    ((ObjectNode) p.path("quality")).put("dust", "INVALID");
    assertTrue(ingest(p).inserted());
    assertEquals("UNKNOWN", conditions.latest("AIRSENSE-001").get("condition"));
  }

  @Test
  void mixedBaselinesRejectStatistics() throws Exception {
    db.update(
        "insert into calibrations(calibration_id,device_id,profile_id,sensor,baseline_adc_mv,method,definition,evidence_reference) values('gas-test-2','AIRSENSE-001','simulator-s3-001','mq135',1000,'test','{}','synthetic test')");
    var a = payload();
    a.put("measuredAt", "2026-09-01T01:00:00Z");
    ingest(a);
    var b = payload();
    b.put("measuredAt", "2026-09-01T02:00:00Z");
    ((ObjectNode) b.path("calibration")).put("gasId", "gas-test-2");
    ingest(b);
    assertEquals(
        "CALIBRATION_CONFLICT",
        assertThrows(
                ApiException.class,
                () ->
                    measurements.stats(
                        "AIRSENSE-001", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z"))
            .code);
  }

  @Test
  void persistedSessionCsrfRevisionAndLogout() throws Exception {
    String username = "test-" + UUID.randomUUID();
    db.update(
        "insert into users(id,username,password_hash,role) values(?,?,?,'ADMIN')",
        UUID.randomUUID(),
        username,
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
            .encode("long-test-password"));
    var request = new MockHttpServletRequest();
    request.addHeader("Origin", "http://localhost:8080");
    var response = new MockHttpServletResponse();
    var session =
        admin.login(
            Json.MAPPER.valueToTree(
                Json.obj("username", username, "password", "long-test-password")),
            request,
            response);
    String token = response.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
    request.setCookies(new jakarta.servlet.http.Cookie("airsense_session", token));
    assertEquals(403, assertThrows(ApiException.class, () -> admin.authorize(request)).status);
    request.addHeader("X-CSRF-Token", session.get("csrfToken"));
    assertNotNull(admin.authorize(request));
    long revision =
        db.queryForObject("select revision from thresholds where id='gas-rule-001'", Long.class);
    var update =
        Json.parse(
            "{\"expectedRevision\":"
                + revision
                + ",\"direction\":\"UPPER\",\"warning\":{\"low\":null,\"high\":1.2},\"alert\":{\"low\":null,\"high\":1.3},\"recovery\":{\"low\":null,\"high\":1.15},\"enabled\":true}");
    assertEquals(
        revision + 1,
        ((Number) admin.update("gas-rule-001", update, request).get("revision")).longValue());
    assertEquals(
        409,
        assertThrows(ApiException.class, () -> admin.update("gas-rule-001", update, request))
            .status);
    admin.logout(request, response);
    assertEquals(401, assertThrows(ApiException.class, () -> admin.authorize(request)).status);
  }
}
