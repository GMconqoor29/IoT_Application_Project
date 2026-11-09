package com.airsense.mqtt;

import com.airsense.common.Json;
import com.airsense.ingestion.Ingestion;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component("mqtt")
public class MqttGateway implements HealthIndicator {
  private final Ingestion ingestion;
  private final MqttAsyncClient client;
  private final MqttConnectOptions options;
  private final boolean enabled;
  private volatile boolean subscribed;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.airsense.analysis.Conditions conditions;

  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          new ThreadPoolExecutor.AbortPolicy());
  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttGateway.class);

  public MqttGateway(
      Ingestion ingestion,
      @Value("${airsense.mqtt.uri}") String uri,
      @Value("${airsense.mqtt.username}") String username,
      @Value("${airsense.mqtt.password}") String password,
      @Value("${airsense.mqtt.enabled}") boolean enabled)
      throws MqttException {
    this.ingestion = ingestion;
    this.enabled = enabled;
    client = new MqttAsyncClient(uri, "airsense-backend", new MemoryPersistence());
    options = new MqttConnectOptions();
    options.setUserName(username);
    options.setPassword(password.toCharArray());
    options.setCleanSession(false);
    options.setAutomaticReconnect(true);
    options.setConnectionTimeout(10);
    options.setKeepAliveInterval(30);
    options.setHttpsHostnameVerificationEnabled(true);
    client.setCallback(
        new MqttCallbackExtended() {
          public void connectComplete(boolean reconnect, String serverURI) {
            subscribed = false;
            try {
              client.subscribe(
                  new String[] {"airsense/+/telemetry", "airsense/+/state", "airsense/+/metadata"},
                  new int[] {1, 1, 1},
                  null,
                  new IMqttActionListener() {
                    public void onSuccess(IMqttToken token) {
                      subscribed =
                          java.util.Arrays.stream(token.getGrantedQos()).allMatch(qos -> qos == 1);
                    }

                    public void onFailure(IMqttToken token, Throwable exception) {
                      log.warn("mqtt_subscribe_failed");
                    }
                  });
            } catch (MqttException e) {
              log.warn("mqtt_subscribe_failed");
            }
          }

          public void connectionLost(Throwable cause) {
            subscribed = false;
            log.warn("mqtt_disconnected");
          }

          public void deliveryComplete(IMqttDeliveryToken token) {}

          public void messageArrived(String topic, MqttMessage message) {
            byte[] bytes = message.getPayload().clone();
            Instant arrival = Instant.now();
            boolean retained = message.isRetained();
            try {
              worker.execute(() -> process(topic, bytes, retained, arrival));
            } catch (RejectedExecutionException e) {
              log.warn("ingestion_capacity_exhausted");
            }
          }
        });
  }

  private void process(String topic, byte[] bytes, boolean retained, Instant arrival) {
    try {
      var result = ingestion.accept(topic, bytes, retained, arrival);
      // accept returns only after TransactionTemplate commits. Publishing failure never undoes
      // storage.
      try {
        if (result.receipt() != null)
          client.publish(
              "airsense/" + result.deviceId() + "/receipts",
              Json.MAPPER.writeValueAsBytes(result.receipt()),
              1,
              false);
      } finally {
        if (result.inserted() && conditions != null)
          try {
            conditions.observe(result.deviceId());
          } catch (Exception e) {
            log.warn("evaluation_failed");
          }
      }
    } catch (Exception e) {
      log.warn("ingestion_pending type={}", e.getClass().getSimpleName());
    }
  }

  @Scheduled(initialDelay = 1000, fixedDelay = 10000)
  public void connect() {
    if (enabled && !client.isConnected())
      try {
        client.connect(options);
      } catch (MqttException e) {
        log.debug("mqtt_connect_pending");
      }
  }

  public Health health() {
    return !enabled
        ? Health.unknown().build()
        : client.isConnected() && subscribed ? Health.up().build() : Health.down().build();
  }

  @PreDestroy
  public void close() throws MqttException {
    worker.shutdown();
    if (client.isConnected()) client.disconnect().waitForCompletion(5000);
    client.close();
  }
}
