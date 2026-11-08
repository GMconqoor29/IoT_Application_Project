package com.airsense;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.airsense.common.ContractValidator;
import com.airsense.common.JsonSupport;
import com.airsense.ingestion.MqttClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Real authenticated MQTT, the running application, and a disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "RUN_MQTT_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MqttTest {
  private PostgreSQLContainer<?> postgres;
  private GenericContainer<?> broker;
  private ConfigurableApplicationContext application;
  private JdbcTemplate db;
  private ContractValidator contracts;
  private String uri;
  private String http;
  private long sequence = System.currentTimeMillis();

  @BeforeAll
  void startServices() throws Exception {
    postgres = new PostgreSQLContainer<>("postgres:17.6");
    postgres.start();
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new FileSystemResource("provision/simulator.sql"));
    }
    db = new JdbcTemplate(source);
    broker =
        new GenericContainer<>("eclipse-mosquitto:2.0.22")
            .withExposedPorts(1883)
            .withCopyFileToContainer(
                MountableFile.forHostPath("src/test/resources/mosquitto.conf"),
                "/mosquitto/config/mosquitto.conf")
            .withCopyFileToContainer(
                MountableFile.forHostPath("deploy/acl"), "/mosquitto/config/acl")
            .withCommand(
                "sh",
                "-c",
                "mosquitto_passwd -b -c /tmp/passwords backend test-backend && mosquitto_passwd -b /tmp/passwords AIRSENSE-001 test-device && exec mosquitto -c /mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*\\n", 1));
    broker.start();
    uri = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
    application =
        new SpringApplicationBuilder(Application.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--airsense.mqtt.uri=" + uri,
                "--airsense.mqtt.username=backend",
                "--airsense.mqtt.password=test-backend",
                "--airsense.mqtt.enabled=true");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                application
                    .getBean(MqttClientConfig.class)
                    .health()
                    .getStatus()
                    .getCode()
                    .equals("UP"));
    http = "http://localhost:" + application.getEnvironment().getProperty("local.server.port");
    contracts = new ContractValidator();
  }

  @AfterAll
  void stopServices() {
    if (application != null) application.close();
    if (broker != null) broker.stop();
    if (postgres != null) postgres.stop();
  }

  @Test
  void committedReceiptAndIdenticalReplayPreserveOneRow() throws Exception {
    var p = telemetry();
    try (var client = device()) {
      var receipts = subscribe(client);
      publish(client, p);
      JsonNode first = receipt(receipts, p);
      assertEquals("STORED", first.path("status").asText());
      assertEquals("COMMITTED", first.path("code").asText());
      assertEquals(1, count(p));
      // A separate connection sees committed data before the receipt is used.
      assertEquals(
          first.path("receivedAt").asText(),
          db.queryForObject(
                  "select received_at from measurements where message_id=?",
                  java.sql.Timestamp.class,
                  p.path("messageId").asText())
              .toInstant()
              .toString());
      publish(client, p);
      assertEquals(first, receipt(receipts, p));
      var reordered = JsonSupport.MAPPER.createObjectNode();
      var names = new java.util.ArrayList<String>();
      p.fieldNames().forEachRemaining(names::add);
      java.util.Collections.reverse(names);
      for (String name : names) reordered.set(name, p.get(name));
      client.publish(
          "airsense/AIRSENSE-001/telemetry",
          JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(reordered),
          1,
          false);
      assertEquals(first, receipt(receipts, p));
      assertEquals(1, count(p));
      var latest =
          JsonSupport.parse(
              new RestTemplate()
                  .getForObject(http + "/api/devices/AIRSENSE-001/latest", String.class));
      assertEquals(
          p.path("messageId"), latest.path("measurement").path("telemetry").path("messageId"));
      client.disconnect();
    }
  }

  @Test
  void conflictingDuplicateIsRejectedWithoutOverwritingOriginal() throws Exception {
    var p = telemetry();
    try (var client = device()) {
      var receipts = subscribe(client);
      publish(client, p);
      receipt(receipts, p);
      ((ObjectNode) p.path("values")).put("temperatureC", 99);
      publish(client, p);
      var rejection = receipt(receipts, p);
      assertEquals("REJECTED", rejection.path("status").asText());
      assertEquals("ID_CONFLICT", rejection.path("code").asText());
      assertEquals(1, count(p));
      assertEquals(
          27.1,
          db.queryForObject(
              "select temperature_c from measurements where message_id=?",
              Double.class,
              p.path("messageId").asText()));
      publish(client, p);
      assertEquals(rejection, receipt(receipts, p));
      client.disconnect();
    }
  }

  @Test
  void missedReceiptIsRecoveredByReplay() throws Exception {
    var p = telemetry();
    try (var client = device()) {
      // Publish without a receipt subscription to simulate a lost application receipt.
      publish(client, p);
      await().atMost(Duration.ofSeconds(10)).until(() -> count(p) == 1);
      var receipts = subscribe(client);
      publish(client, p);
      var recovered = receipt(receipts, p);
      assertEquals("STORED", recovered.path("status").asText());
      assertEquals(1, count(p));
      client.disconnect();
    }
  }

  @Test
  void brokerRequiresCredentials() throws Exception {
    try (var anonymous =
        new MqttClient(uri, "unauthorized-" + UUID.randomUUID(), new MemoryPersistence())) {
      assertThrows(org.eclipse.paho.client.mqttv3.MqttException.class, anonymous::connect);
    }
  }

  private ObjectNode telemetry() throws Exception {
    var p =
        (ObjectNode)
            JsonSupport.parse(
                Files.readString(Path.of("../contracts/fixtures/valid/telemetry-normal.json")));
    p.put("sequence", ++sequence);
    p.put("messageId", "AIRSENSE-001:b7f3a901:" + sequence);
    p.put("measuredAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
    return p;
  }

  private MqttClient device() throws Exception {
    var client = new MqttClient(uri, "device-test-" + UUID.randomUUID(), new MemoryPersistence());
    var options = new MqttConnectOptions();
    options.setUserName("AIRSENSE-001");
    options.setPassword("test-device".toCharArray());
    client.connect(options);
    return client;
  }

  private ArrayBlockingQueue<JsonNode> subscribe(MqttClient client) throws Exception {
    var receipts = new ArrayBlockingQueue<JsonNode>(16);
    client.subscribe(
        "airsense/AIRSENSE-001/receipts",
        1,
        (topic, message) -> {
          assertFalse(message.isRetained());
          receipts.offer(
              JsonSupport.parse(new String(message.getPayload(), StandardCharsets.UTF_8)));
        });
    return receipts;
  }

  private void publish(MqttClient client, ObjectNode p) throws Exception {
    client.publish(
        "airsense/AIRSENSE-001/telemetry", JsonSupport.MAPPER.writeValueAsBytes(p), 1, false);
  }

  private JsonNode receipt(ArrayBlockingQueue<JsonNode> receipts, ObjectNode p) throws Exception {
    JsonNode receipt = receipts.poll(10, TimeUnit.SECONDS);
    assertNotNull(receipt, "Missing application receipt");
    contracts.validate("receipt", receipt);
    assertEquals(p.path("messageId"), receipt.path("messageId"));
    return receipt;
  }

  private int count(ObjectNode p) {
    return db.queryForObject(
        "select count(*) from measurements where message_id=?",
        Integer.class,
        p.path("messageId").asText());
  }
}
