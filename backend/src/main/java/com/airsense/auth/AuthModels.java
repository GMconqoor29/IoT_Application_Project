package com.airsense.auth;

import java.util.UUID;

public final class AuthModels {
  private AuthModels() {}

  public record Login(String username, String password) {
    public String toString() {
      return "Login[credentials=REDACTED]";
    }
  }

  public record SessionResponse(String username, String role, String csrfToken) {
    public String toString() {
      return "SessionResponse[tokens=REDACTED]";
    }
  }

  public record LoginResult(SessionResponse response, String sessionToken) {
    public String toString() {
      return "LoginResult[tokens=REDACTED]";
    }
  }

  public record User(UUID id, String passwordHash, boolean enabled, String role) {
    public String toString() {
      return "User[credentials=REDACTED]";
    }
  }

  public record Session(UUID userId, byte[] csrfHash) {
    public String toString() {
      return "Session[tokens=REDACTED]";
    }
  }
}
