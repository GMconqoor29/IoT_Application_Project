package com.airsense.operations;

import com.airsense.common.JsonSupport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Sends one record, waits for its application receipt, then replays the exact bytes. */
public final class Simulator {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Provide a telemetry fixture path.");
    ObjectNode p = (ObjectNode) JsonSupport.parse(Files.readString(Path.of(args[0])));
    long sequence = System.currentTimeMillis();
    p.put("sequence", sequence);
    p.put(
        "messageId",
        p.path("deviceId").asText() + ":" + p.path("bootId").asText() + ":" + sequence);
    p.put("measuredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
    p.put("timeQuality", "SYNCED");
    byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(p);
    String device = p.path("deviceId").asText();
    var receipts = new ArrayBlockingQueue<String>(8);
    try (var client =
        new MqttClient(
            System.getenv().getOrDefault("MQTT_URI", "tcp://localhost:1883"),
            "simulator-" + sequence,
            new MemoryPersistence())) {
      var options = new MqttConnectOptions();
      options.setUserName(device);
      String password = System.getenv("SIMULATOR_MQTT_PASSWORD");
      if (password == null) throw new IllegalArgumentException("Set SIMULATOR_MQTT_PASSWORD.");
      options.setPassword(password.toCharArray());
      options.setHttpsHostnameVerificationEnabled(true);
      client.connect(options);
      client.subscribe(
          "airsense/" + device + "/receipts",
          1,
          (topic, message) -> {
            var r =
                JsonSupport.parse(
                    new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
            if (r.path("messageId").asText().equals(p.path("messageId").asText()))
              receipts.offer(r.toString());
          });
      String first = null;
      for (int i = 0; i < 2; i++) {
        client.publish("airsense/" + device + "/telemetry", bytes, 1, false);
        String receipt = receipts.poll(15, TimeUnit.SECONDS);
        if (receipt == null)
          throw new IllegalStateException("No application receipt; keep this record pending.");
        if (!JsonSupport.parse(receipt).path("status").asText().equals("STORED"))
          throw new IllegalStateException("Record rejected: " + receipt);
        if (first != null
            && !JsonSupport.canonical(JsonSupport.parse(first))
                .equals(JsonSupport.canonical(JsonSupport.parse(receipt))))
          throw new IllegalStateException("Replay receipt changed.");
        first = receipt;
        System.out.println(receipt);
      }
      client.disconnect();
    }
  }
}
