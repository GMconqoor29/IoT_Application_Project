package com.airsense.operations;

import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit offline administration: never invoked by application startup or MQTT. */
public final class Provision {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !args[0].equals("admin"))
      throw new IllegalArgumentException(
          "Usage: Provision admin; provide DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD, ADMIN_USERNAME, ADMIN_PASSWORD.");
    String username = required("ADMIN_USERNAME").strip().toLowerCase(Locale.ROOT),
        password = required("ADMIN_PASSWORD");
    if (username.isEmpty()
        || username.length() > 128
        || password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Use a username <=128 characters and password >=12 characters / <=72 UTF-8 bytes.");
    try (var connection =
            DriverManager.getConnection(
                required("DATABASE_URL"),
                required("DATABASE_USER"),
                required("DATABASE_PASSWORD"));
        var statement =
            connection.prepareStatement(
                "insert into users(id,username,password_hash,role) values(?,?,?,'ADMIN')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, username);
      statement.setString(3, new BCryptPasswordEncoder(12).encode(password));
      statement.executeUpdate();
    }
    System.out.println("Administrator created.");
  }

  private static String required(String key) {
    return Objects.requireNonNull(System.getenv(key), "Missing " + key);
  }
}
