package com.airsense.auth;

import static com.airsense.auth.AuthModels.*;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
  private final JdbcTemplate db;

  public AuthRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<User> user(String username) {
    return db
        .query(
            "select id,password_hash,enabled,role from users where username=?",
            (r, n) ->
                new User(
                    r.getObject("id", UUID.class),
                    r.getString("password_hash"),
                    r.getBoolean("enabled"),
                    r.getString("role")),
            username)
        .stream()
        .findFirst();
  }

  public Optional<Session> session(byte[] hash) {
    return db
        .query(
            "select u.id,s.csrf_token_hash from admin_sessions s join users u on u.id=s.user_id where s.session_id_hash=? and s.expires_at>now() and s.invalidated_at is null and u.enabled and u.role='ADMIN'",
            (r, n) -> new Session(r.getObject("id", UUID.class), r.getBytes("csrf_token_hash")),
            hash)
        .stream()
        .findFirst();
  }

  public void invalidate(byte[] hash) {
    db.update("update admin_sessions set invalidated_at=now() where session_id_hash=?", hash);
  }

  public void create(byte[] session, UUID user, byte[] csrf) {
    db.update(
        "insert into admin_sessions(session_id_hash,user_id,csrf_token_hash,created_at,expires_at) values(?,?,?,now(),now()+interval '8 hours')",
        session,
        user,
        csrf);
  }

  public void cleanup() {
    db.update(
        "delete from admin_sessions where expires_at<now() or invalidated_at<now()-interval '1 day'");
  }
}
