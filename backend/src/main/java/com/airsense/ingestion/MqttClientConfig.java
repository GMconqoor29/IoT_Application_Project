package com.airsense.ingestion;

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
public class MqttClientConfig implements HealthIndicator {
  private final MqttListener listener;
  private final java.time.Clock clock;
  private final MqttAsyncClient client;
  private final MqttConnectOptions options;
  private final boolean enabled;
  private volatile boolean subscribed;

  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          new ThreadPoolExecutor.AbortPolicy());
  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttClientConfig.class);

  public MqttClientConfig(
      MqttListener listener,
      java.time.Clock clock,
      @Value("${airsense.mqtt.uri}") String uri,
      @Value("${airsense.mqtt.username}") String username,
      @Value("${airsense.mqtt.password}") String password,
      @Value("${airsense.mqtt.enabled}") boolean enabled)
      throws MqttException {
    this.listener = listener;
    this.clock = clock;
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
                          token.getGrantedQos().length == 3
                              && java.util.Arrays.stream(token.getGrantedQos())
                                  .allMatch(qos -> qos == 1);
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
            if (message.getPayload().length > 4096) {
              log.warn("mqtt_payload_too_large");
              return;
            }
            byte[] bytes = message.getPayload().clone();
            Instant arrival = clock.instant();
            boolean retained = message.isRetained();
            try {
              worker.execute(() -> listener.process(client, topic, bytes, retained, arrival));
            } catch (RejectedExecutionException e) {
              log.warn("ingestion_capacity_exhausted");
            }
          }
        });
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
    try {
      if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException e) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
    if (client.isConnected()) client.disconnect().waitForCompletion(5000);
    client.close();
  }
}
