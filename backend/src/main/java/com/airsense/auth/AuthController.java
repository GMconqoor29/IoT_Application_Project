package com.airsense.auth;

import static com.airsense.auth.AuthModels.*;

import com.airsense.common.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService service;
  private final ContractValidator contracts;
  private final boolean secure;

  public AuthController(
      AuthService service,
      ContractValidator contracts,
      @Value("${airsense.secure-cookie}") boolean secure) {
    this.service = service;
    this.contracts = contracts;
    this.secure = secure;
  }

  @PostMapping("/login")
  public SessionResponse login(
      @RequestBody String body, HttpServletRequest request, HttpServletResponse response) {
    service.checkOrigin(request.getHeader("Origin"));
    var json = JsonSupport.parse(body);
    contracts.validate("Login", json);
    var result =
        service.login(
            JsonSupport.MAPPER.convertValue(json, Login.class),
            request.getRemoteAddr(),
            AdminGuard.cookie(request),
            request.getHeader("Origin"));
    setCookie(response, result.sessionToken(), 28800);
    return result.response();
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    service.logout(
        AdminGuard.cookie(request), request.getHeader("Origin"), request.getHeader("X-CSRF-Token"));
    setCookie(response, "", 0);
  }

  private void setCookie(HttpServletResponse response, String value, long seconds) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("airsense_session", value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/api")
            .maxAge(seconds)
            .build()
            .toString());
  }
}
