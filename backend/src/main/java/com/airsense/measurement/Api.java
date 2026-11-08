package com.airsense.measurement;

import com.airsense.admin.Admin;
import com.airsense.analysis.Conditions;
import com.airsense.common.Json;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Api {
  private final Measurements measurements;
  private final Conditions conditions;
  private final Admin admin;

  public Api(Measurements measurements, Conditions conditions, Admin admin) {
    this.measurements = measurements;
    this.conditions = conditions;
    this.admin = admin;
  }

  @GetMapping("/devices")
  Object devices() {
    return measurements.devices();
  }

  @GetMapping("/devices/{id}/latest")
  Object latest(@PathVariable String id) {
    return conditions.latest(id);
  }

  @GetMapping("/devices/{id}/measurements")
  Object history(
      @PathVariable String id,
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String cursor) {
    return measurements.history(id, from, to, limit, cursor);
  }

  @GetMapping("/devices/{id}/stats")
  Object stats(@PathVariable String id, @RequestParam String from, @RequestParam String to) {
    return measurements.stats(id, from, to);
  }

  @GetMapping("/thresholds")
  Object thresholds(@RequestParam String deviceId) {
    return conditions.thresholds(deviceId);
  }

  @PutMapping("/thresholds/{id}")
  Object threshold(@PathVariable String id, @RequestBody String body, HttpServletRequest request) {
    return admin.update(id, Json.parse(body), request);
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody String body, HttpServletRequest request, HttpServletResponse response) {
    return admin.login(Json.parse(body), request, response);
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest request, HttpServletResponse response) {
    admin.logout(request, response);
  }
}
