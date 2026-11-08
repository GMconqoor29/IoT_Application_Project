package com.airsense.threshold;

import static com.airsense.threshold.ThresholdModels.*;

import com.airsense.auth.AdminGuard;
import com.airsense.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/thresholds")
public class ThresholdController {
  private final ThresholdService service;
  private final AdminGuard guard;
  private final ContractValidator contracts;

  public ThresholdController(
      ThresholdService service, AdminGuard guard, ContractValidator contracts) {
    this.service = service;
    this.guard = guard;
    this.contracts = contracts;
  }

  @GetMapping
  public RuleList list(@RequestParam String deviceId) {
    return new RuleList(service.list(deviceId));
  }

  @PutMapping("/{id}")
  public Rule update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
    var actor = guard.authorize(request);
    var json = JsonSupport.parse(body);
    contracts.validate("ThresholdUpdate", json);
    return service.update(id, JsonSupport.MAPPER.convertValue(json, Update.class), actor);
  }
}
