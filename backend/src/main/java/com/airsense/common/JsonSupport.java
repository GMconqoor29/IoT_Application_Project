package com.airsense.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class JsonSupport {
  public static final ObjectMapper MAPPER =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxNumberLength(128)
                          .build())
                  .build())
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private JsonSupport() {}

  public static JsonNode parse(String s) {
    try {
      JsonNode result = MAPPER.readTree(s);
      if (result == null) throw new IllegalArgumentException();
      return result;
    } catch (Exception e) {
      throw new ApiException(400, "INVALID_PAYLOAD", "Malformed JSON.");
    }
  }

  public static String canonical(JsonNode n) {
    if (n.isObject()) {
      TreeMap<String, String> fields = new TreeMap<>();
      n.fields().forEachRemaining(e -> fields.put(e.getKey(), canonical(e.getValue())));
      return "{"
          + String.join(
              ",",
              fields.entrySet().stream().map(e -> quote(e.getKey()) + ":" + e.getValue()).toList())
          + "}";
    }
    if (n.isArray()) {
      List<String> a = new ArrayList<>();
      n.forEach(v -> a.add(canonical(v)));
      return "[" + String.join(",", a) + "]";
    }
    if (n.isNumber()) {
      var decimal = n.decimalValue().stripTrailingZeros();
      if (!Double.isFinite(n.doubleValue()) || Math.abs((long) decimal.scale()) > 4096)
        throw new ApiException(400, "INVALID_PAYLOAD", "Number exceeds parser limits.");
      return decimal.toPlainString();
    }
    return n.toString();
  }

  public static String quote(String s) {
    try {
      return MAPPER.writeValueAsString(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] hash(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] hash(String s) {
    return hash(s.getBytes(StandardCharsets.UTF_8));
  }
}
