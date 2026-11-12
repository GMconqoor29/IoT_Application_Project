package com.airsense.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AdminGuard {
  private final AuthService service;

  public AdminGuard(AuthService service) {
    this.service = service;
  }

  public UUID authorize(HttpServletRequest request) {
    return service.authorize(
        cookie(request), request.getHeader("Origin"), request.getHeader("X-CSRF-Token"));
  }

  public static String cookie(HttpServletRequest request) {
    if (request.getCookies() != null)
      for (var cookie : request.getCookies())
        if (cookie.getName().equals("airsense_session")) return cookie.getValue();
    return "";
  }
}
