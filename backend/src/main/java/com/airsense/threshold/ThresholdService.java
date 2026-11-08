package com.airsense.threshold;

import static com.airsense.threshold.ThresholdModels.*;

import com.airsense.common.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThresholdService {
  private final ThresholdRepository repository;
  private final ThresholdValidator validator;

  public ThresholdService(ThresholdRepository repository, ThresholdValidator validator) {
    this.repository = repository;
    this.validator = validator;
  }

  public List<Rule> list(String device) {
    if (!repository.deviceExists(device))
      throw new ApiException(404, "NOT_FOUND", "Device not found.");
    return repository.list(device);
  }

  @Transactional
  public Rule update(String id, Update body, UUID actor) {
    validator.validate(body);
    var result =
        repository
            .update(id, body, actor)
            .orElseThrow(
                () ->
                    repository.find(id).isEmpty()
                        ? new ApiException(404, "NOT_FOUND", "Threshold not found.")
                        : new ApiException(
                            409, "REVISION_CONFLICT", "Threshold has changed; reload it."));
    org.slf4j.LoggerFactory.getLogger(ThresholdService.class)
        .info("threshold_updated actor={} id={} revision={}", actor, id, result.revision());
    return result;
  }
}
