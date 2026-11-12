package com.airsense.ingestion;

import static com.airsense.common.JsonSupport.*;
import static com.airsense.ingestion.IngestionModels.*;

import com.airsense.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionService {
  private final IngestionRepository repository;
  private final TelemetryValidator validator;
  private final TransactionTemplate tx;

  public IngestionService(
      IngestionRepository repository,
      TelemetryValidator validator,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.validator = validator;
    this.tx = new TransactionTemplate(manager);
  }

  public Result accept(String topic, byte[] bytes, boolean retained, Instant arrival) {
    var parts = topic.split("/", -1);
    if (parts.length != 3
        || !parts[0].equals("airsense")
        || !parts[1].matches("[A-Z0-9][A-Z0-9_-]{0,63}")) return new Result(null, false, null);
    String device = parts[1], kind = parts[2];
    if (!List.of("telemetry", "state", "metadata").contains(kind) || !repository.enabled(device))
      return new Result(null, false, device);
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
      canonical(p);
    } catch (Exception e) {
      repository.diagnostic(topic, device, bytes, arrival);
      return new Result(null, false, device);
    }
    var incoming =
        new Incoming(
            topic,
            device,
            kind,
            p.path("messageId").asText(""),
            p,
            bytes,
            arrival.truncatedTo(ChronoUnit.MILLIS),
            retained);
    // No receipt escapes this method until the transaction has successfully committed.
    return tx.execute(status -> persist(incoming));
  }

  private Result persist(Incoming in) {
    repository.lock(in.deviceId());
    var p = in.payload();
    boolean identity =
        in.kind().equals("telemetry")
            && validator.trustworthyIdentity(in.deviceId(), in.messageId());
    if (identity) {
      var existing = repository.accepted(in.deviceId(), in.messageId());
      if (existing.isPresent()) {
        var row = existing.get();
        if (row.canonicalPayload().equals(canonical(p)))
          return new Result(
              receipt(in.deviceId(), in.messageId(), row.receivedAt(), "COMMITTED"),
              false,
              in.deviceId());
        return reject(in, "ID_CONFLICT");
      }
      var rejected = repository.rejected(in.topic(), p, in.receivedAt());
      if (rejected.isPresent()) return new Result(rejected.get(), false, in.deviceId());
    }
    try {
      validator.envelope(in);
      if (in.kind().equals("state")) {
        repository.state(in);
        return new Result(null, false, in.deviceId());
      }
      if (in.kind().equals("metadata")) {
        repository.metadata(in);
        return new Result(null, false, in.deviceId());
      }
      validator.identity(in);
      var profile =
          repository
              .profile(in.deviceId(), p.path("bootId").asText())
              .orElseThrow(
                  () ->
                      new ApiException(
                          503, "CONFIGURATION_ERROR", "Boot profile is not provisioned."));
      var context =
          new TrustedContext(
              profile,
              repository.calibration(
                  nullable(p.path("calibration").path("gasId")),
                  in.deviceId(),
                  profile.profileId(),
                  "mq135"),
              repository.calibration(
                  nullable(p.path("calibration").path("dustId")),
                  in.deviceId(),
                  profile.profileId(),
                  "dust"));
      var measured = validator.validate(in, context);
      repository.insert(in, profile, measured);
      return new Result(
          receipt(in.deviceId(), in.messageId(), in.receivedAt(), "COMMITTED"),
          true,
          in.deviceId());
    } catch (ApiException e) {
      if (e.status == 503) throw e;
      if (identity) return reject(in, e.code);
      return new Result(null, false, in.deviceId());
    }
  }

  private Result reject(Incoming in, String code) {
    return repository.reject(
        in.topic(),
        in.deviceId(),
        in.messageId(),
        in.payload(),
        in.original(),
        in.receivedAt(),
        code);
  }

  private String nullable(JsonNode node) {
    return node.isNull() ? null : node.asText();
  }
}
