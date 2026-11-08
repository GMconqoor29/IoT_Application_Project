package com.airsense.auth;

import static com.airsense.auth.AuthModels.*;
import static com.airsense.common.JsonSupport.*;

import com.airsense.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
  private final AuthRepository repository;
  private final TransactionTemplate tx;
  private final String origin;
  private final Clock clock;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final String dummy = passwords.encode(UUID.randomUUID().toString());
  private final SecureRandom random = new SecureRandom();
  private final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

  private record Attempt(long window, int count) {}

  public AuthService(
      AuthRepository repository,
      PlatformTransactionManager manager,
      @Value("${airsense.origin}") String origin,
      Clock clock) {
    this.repository = repository;
    this.tx = new TransactionTemplate(manager);
    this.origin = origin;
    this.clock = clock;
  }

  public void checkOrigin(String supplied) {
    if (!origin.equals(supplied))
      throw new ApiException(403, "FORBIDDEN", "Origin is not allowed.");
  }

  public LoginResult login(
      Login body, String remoteAddress, String previousToken, String suppliedOrigin) {
    checkOrigin(suppliedOrigin);
    throttle(remoteAddress);
    String username = body.username().strip().toLowerCase(Locale.ROOT), password = body.password();
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(400, "INVALID_REQUEST", "Password exceeds 72 UTF-8 bytes.");
    var user = repository.user(username).orElse(null);
    boolean match = passwords.matches(password, user == null ? dummy : user.passwordHash());
    if (!match || user == null || !user.enabled() || !user.role().equals("ADMIN"))
      throw new ApiException(401, "UNAUTHORIZED", "Invalid credentials.");
    String session = token(), csrf = token();
    tx.executeWithoutResult(
        status -> {
          repository.invalidate(hash(previousToken));
          repository.create(hash(session), user.id(), hash(csrf));
        });
    return new LoginResult(new SessionResponse(username, "ADMIN", csrf), session);
  }

  public UUID authorize(String token, String suppliedOrigin, String csrf) {
    var session =
        repository
            .session(hash(token))
            .orElseThrow(
                () -> new ApiException(401, "UNAUTHORIZED", "Administrator session required."));
    checkOrigin(suppliedOrigin);
    if (csrf == null || !MessageDigest.isEqual(session.csrfHash(), hash(csrf)))
      throw new ApiException(403, "FORBIDDEN", "CSRF verification failed.");
    return session.userId();
  }

  public void logout(String token, String suppliedOrigin, String csrf) {
    authorize(token, suppliedOrigin, csrf);
    repository.invalidate(hash(token));
  }

  private synchronized void throttle(String key) {
    long now = clock.millis();
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
}
