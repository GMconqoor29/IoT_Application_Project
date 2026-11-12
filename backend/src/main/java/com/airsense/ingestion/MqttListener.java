package com.airsense.ingestion;

import com.airsense.common.JsonSupport;
import java.time.Instant;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.springframework.stereotype.Component;

@Component
public class MqttListener {
  private final IngestionService service;
  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttListener.class);

  public MqttListener(IngestionService service) {
    this.service = service;
  }

  public void process(
      MqttAsyncClient client, String topic, byte[] bytes, boolean retained, Instant arrival) {
    try {
      var result = service.accept(topic, bytes, retained, arrival);
      // The durable result is available only after commit; publish failure leaves it replayable.
      if (result.receipt() != null)
        client.publish(
            "airsense/" + result.deviceId() + "/receipts",
            JsonSupport.MAPPER.writeValueAsBytes(result.receipt()),
            1,
            false);
    } catch (Exception e) {
      log.warn("ingestion_pending type={}", e.getClass().getSimpleName());
    }
  }
}
