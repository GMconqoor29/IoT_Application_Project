package com.airsense.admin;

import static com.airsense.common.Json.*;

import com.airsense.analysis.Conditions;
import com.airsense.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Admin {
  private final JdbcTemplate db;
  private final Contracts contracts;
  private final Conditions conditions;
  private final TransactionTemplate tx;
  private final String origin;
  private final boolean secure;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final String dummy = passwords.encode(UUID.randomUUID().toString());
  private final SecureRandom random = new SecureRandom();
  private final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

  private record Attempt(long window, int count) {}

  public Admin(
      JdbcTemplate db,
      Contracts contracts,
      Conditions conditions,
      PlatformTransactionManager manager,
      @Value("${airsense.origin}") String origin,
      @Value("${airsense.secure-cookie}") boolean secure) {
    this.db = db;
    this.contracts = contracts;
    this.conditions = conditions;
    this.tx = new TransactionTemplate(manager);
    this.origin = origin;
    this.secure = secure;
  }

  private void origin(HttpServletRequest request) {
    if (!origin.equals(request.getHeader("Origin")))
      throw new ApiException(403, "FORBIDDEN", "Origin is not allowed.");
  }

  private synchronized void throttle(String key) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> now - e.getValue().window() > 60000);
    var previous = attempts.get(key);
    if (previous != null && previous.count() >= 10)
      throw new ApiException(429, "RATE_LIMITED", "Try again later.");
    if (previous == null && attempts.size() >= 10000)
      throw new ApiException(429, "RATE_LIMITED", "Try again later.");
    attempts.put(
        key,
        new Attempt(
            previous == null ? now : previous.window(),
            previous == null ? 1 : previous.count() + 1));
  }

  private String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String cookie(HttpServletRequest request) {
    if (request.getCookies() != null)
      for (var cookie : request.getCookies())
        if (cookie.getName().equals("airsense_session")) return cookie.getValue();
    return "";
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

  public Map<String, Object> login(
      JsonNode body, HttpServletRequest request, HttpServletResponse response) {
    origin(request);
    throttle(request.getRemoteAddr());
    contracts.validate("Login", body);
    String username = body.path("username").asText().strip().toLowerCase(Locale.ROOT),
        password = body.path("password").asText();
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(400, "INVALID_REQUEST", "Password exceeds 72 UTF-8 bytes.");
    var rows = db.queryForList("select * from users where username=?", username);
    var user = rows.isEmpty() ? null : rows.getFirst();
    boolean match =
        passwords.matches(password, user == null ? dummy : user.get("password_hash").toString());
    if (!match
        || user == null
        || !Boolean.TRUE.equals(user.get("enabled"))
        || !user.get("role").equals("ADMIN"))
      throw new ApiException(401, "UNAUTHORIZED", "Invalid credentials.");
    String session = token(), csrf = token();
    tx.executeWithoutResult(
        status -> {
          db.update(
              "update admin_sessions set invalidated_at=now() where session_id_hash=?",
              hash(cookie(request)));
          db.update(
              "insert into admin_sessions(session_id_hash,user_id,csrf_token_hash,created_at,expires_at) values(?,?,?,now(),now()+interval '8 hours')",
              hash(session),
              user.get("id"),
              hash(csrf));
        });
    setCookie(response, session, 28800);
    return obj("username", username, "role", "ADMIN", "csrfToken", csrf);
  }

  public UUID authorize(HttpServletRequest request) {
    var rows =
        db.queryForList(
            "select u.id,s.csrf_token_hash from admin_sessions s join users u on u.id=s.user_id where s.session_id_hash=? and s.expires_at>now() and s.invalidated_at is null and u.enabled and u.role='ADMIN'",
            hash(cookie(request)));
    if (rows.isEmpty())
      throw new ApiException(401, "UNAUTHORIZED", "Administrator session required.");
    origin(request);
    String csrf = request.getHeader("X-CSRF-Token");
    if (csrf == null
        || !MessageDigest.isEqual((byte[]) rows.getFirst().get("csrf_token_hash"), hash(csrf)))
      throw new ApiException(403, "FORBIDDEN", "CSRF verification failed.");
    return (UUID) rows.getFirst().get("id");
  }

  public void logout(HttpServletRequest request, HttpServletResponse response) {
    authorize(request);
    db.update(
        "update admin_sessions set invalidated_at=now() where session_id_hash=?",
        hash(cookie(request)));
    setCookie(response, "", 0);
  }

  public synchronized Map<String, Object> update(
      String id, JsonNode body, HttpServletRequest request) {
    UUID actor = authorize(request);
    contracts.validate("ThresholdUpdate", body);
    validateBounds(body);
    var result =
        tx.execute(
            status -> {
              var rows = db.queryForList("select * from thresholds where id=? for update", id);
              if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Threshold not found.");
              var rule = rows.getFirst();
              if (((Number) rule.get("revision")).longValue()
                  != body.path("expectedRevision").asLong())
                throw new ApiException(
                    409, "REVISION_CONFLICT", "Threshold has changed; reload it.");
              db.update(
                  "update thresholds set revision=revision+1,direction=?,warning_low=?,warning_high=?,alert_low=?,alert_high=?,recovery_low=?,recovery_high=?,enabled=?,updated_by=?,updated_at=now() where id=?",
                  body.path("direction").asText(),
                  bound(body, "warning", "low"),
                  bound(body, "warning", "high"),
                  bound(body, "alert", "low"),
                  bound(body, "alert", "high"),
                  bound(body, "recovery", "low"),
                  bound(body, "recovery", "high"),
                  body.path("enabled").asBoolean(),
                  actor,
                  id);
              return conditions.dto(db.queryForMap("select * from thresholds where id=?", id));
            });
    conditions.refresh();
    org.slf4j.LoggerFactory.getLogger(Admin.class)
        .info("threshold_updated actor={} id={} revision={}", actor, id, result.get("revision"));
    return result;
  }

  private static Object bound(JsonNode p, String level, String side) {
    var n = p.path(level).path(side);
    return n.isNull() ? null : n.decimalValue();
  }

  public static void validateBounds(JsonNode p) {
    String direction = p.path("direction").asText();
    List<java.math.BigDecimal> ordered = new ArrayList<>();
    if (!direction.equals("UPPER"))
      for (String key : List.of("alert", "warning", "recovery")) {
        var n = p.path(key).path("low");
        if (!n.isNumber()) throw invalid();
        ordered.add(n.decimalValue());
      }
    else
      for (String key : List.of("alert", "warning", "recovery"))
        if (!p.path(key).path("low").isNull()) throw invalid();
    if (!direction.equals("LOWER"))
      for (String key : List.of("recovery", "warning", "alert")) {
        var n = p.path(key).path("high");
        if (!n.isNumber()) throw invalid();
        ordered.add(n.decimalValue());
      }
    else
      for (String key : List.of("alert", "warning", "recovery"))
        if (!p.path(key).path("high").isNull()) throw invalid();
    for (int i = 1; i < ordered.size(); i++)
      if (ordered.get(i - 1).compareTo(ordered.get(i)) >= 0) throw invalid();
  }

  private static ApiException invalid() {
    return new ApiException(
        400,
        "INVALID_REQUEST",
        "Threshold boundaries are not strictly ordered for this direction.");
  }
}
