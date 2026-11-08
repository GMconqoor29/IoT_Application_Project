package com.airsense;

import static org.junit.jupiter.api.Assertions.*;

import com.airsense.common.*;
import com.networknt.schema.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpTest {
  static String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
  static DriverManagerDataSource source;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String url = System.getenv("TEST_DATABASE_URL"),
        user = System.getenv().getOrDefault("TEST_DATABASE_USER", "airsense"),
        password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
    source = new DriverManagerDataSource(url, user, password);
    new JdbcTemplate(source).execute("create schema " + schema);
    registry.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    registry.add("spring.datasource.username", () -> user);
    registry.add("spring.datasource.password", () -> password);
    registry.add("airsense.mqtt.enabled", () -> false);
    registry.add("airsense.mqtt.password", () -> "test");
    registry.add("airsense.origin", () -> "http://localhost:8080");
    registry.add("airsense.secure-cookie", () -> false);
  }

  @Autowired TestRestTemplate http;
  @Autowired javax.sql.DataSource database;

  @BeforeAll
  void provision() throws Exception {
    try (var connection = database.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new FileSystemResource("provision/simulator.sql"));
    }
  }

  @AfterAll
  void cleanup() {
    if (source != null) new JdbcTemplate(source).execute("drop schema " + schema + " cascade");
  }

  @Test
  void publicQueriesMatchOpenApiAndWritesRequireAuthentication() throws Exception {
    var devices = http.getForEntity("/api/devices", String.class);
    assertEquals(200, devices.getStatusCode().value());
    assertEquals(
        "AIRSENSE-001",
        JsonSupport.parse(devices.getBody()).path("items").get(0).path("deviceId").asText());
    var rules = http.getForEntity("/api/thresholds?deviceId=AIRSENSE-001", String.class);
    assertEquals(200, rules.getStatusCode().value());
    assertFalse(JsonSupport.parse(rules.getBody()).path("items").isEmpty());
    for (var rule : JsonSupport.parse(rules.getBody()).path("items"))
      validate("Threshold", rule.toString());
    var latest = http.getForEntity("/api/devices/AIRSENSE-001/latest", String.class);
    assertEquals(200, latest.getStatusCode().value());
    validate("Latest", latest.getBody());
    assertTrue(JsonSupport.parse(latest.getBody()).path("measurement").isNull());
    var history =
        http.getForEntity(
            "/api/devices/AIRSENSE-001/measurements?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z",
            String.class);
    assertEquals(200, history.getStatusCode().value());
    validate("MeasurementPage", history.getBody());
    var stats =
        http.getForEntity(
            "/api/devices/AIRSENSE-001/stats?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z",
            String.class);
    assertEquals(200, stats.getStatusCode().value());
    validate("Statistics", stats.getBody());
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var unauthorized =
        http.exchange(
            "/api/thresholds/gas-rule-001",
            HttpMethod.PUT,
            new HttpEntity<>("{}", headers),
            String.class);
    assertEquals(401, unauthorized.getStatusCode().value());
    validate("Error", unauthorized.getBody());
    assertEquals(
        404,
        http.getForEntity("/api/devices/UNKNOWN/latest", String.class).getStatusCode().value());
    assertEquals(
        400,
        http.getForEntity("/api/devices/AIRSENSE-001/stats", String.class).getStatusCode().value());
  }

  private void validate(String name, String body) throws Exception {
    var root =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JsonSupport.MAPPER.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    root.put("$ref", "#/components/schemas/" + name);
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                root, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    assertTrue(schema.validate(JsonSupport.parse(body)).isEmpty(), body);
  }
}
