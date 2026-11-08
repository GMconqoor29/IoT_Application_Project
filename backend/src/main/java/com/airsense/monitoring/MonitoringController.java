package com.airsense.monitoring;

import static com.airsense.monitoring.MonitoringModels.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices")
public class MonitoringController {
  private final MonitoringService service;

  public MonitoringController(MonitoringService service) {
    this.service = service;
  }

  @GetMapping
  public DeviceList devices() {
    return service.devices();
  }

  @GetMapping("/{id}/latest")
  public Latest latest(@PathVariable String id) {
    return service.latest(id);
  }

  @GetMapping("/{id}/measurements")
  public HistoryPage history(
      @PathVariable String id,
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String cursor) {
    return service.history(id, from, to, limit, cursor);
  }

  @GetMapping("/{id}/stats")
  public Statistics stats(
      @PathVariable String id, @RequestParam String from, @RequestParam String to) {
    return service.stats(id, from, to);
  }
}
