package com.airsense;

import static org.junit.jupiter.api.Assertions.*;

import com.airsense.admin.Admin;
import com.airsense.common.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ContractTest {
  @Test
  void sharedFixtures() throws Exception {
    var contracts = new Contracts();
    for (var entry : Json.parse(Files.readString(Path.of("../contracts/fixtures/manifest.json")))) {
      String schema = entry.path("schema").asText();
      if (schema.startsWith("command")) continue;
      var payload =
          Json.parse(
              Files.readString(Path.of("../contracts/fixtures/" + entry.path("file").asText())));
      if (entry.path("valid").asBoolean())
        assertDoesNotThrow(() -> contracts.validate(schema, payload), entry.toString());
      else
        assertThrows(
            ApiException.class, () -> contracts.validate(schema, payload), entry.toString());
    }
  }

  @Test
  void rejectsAmbiguousJson() {
    assertThrows(ApiException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
    assertThrows(ApiException.class, () -> Json.parse("{} {}"));
    assertThrows(ApiException.class, () -> Json.parse("{\"a\":NaN}"));
  }

  @Test
  void canonicalComparison() {
    assertEquals(
        Json.canonical(Json.parse("{\"a\":1.0,\"b\":[2,3]}")),
        Json.canonical(Json.parse("{\"b\":[2.0,3],\"a\":1}")));
    assertNotEquals(Json.canonical(Json.parse("[2,3]")), Json.canonical(Json.parse("[3,2]")));
  }

  @Test
  void crossLanguageCanonicalFixtures() throws Exception {
    for (var fixture :
        Json.parse(Files.readString(Path.of("../contracts/fixtures/canonical.json"))))
      assertEquals(fixture.path("expected").asText(), Json.canonical(fixture.path("input")));
  }

  @Test
  void exponentCannotExpandToUnboundedMemory() {
    assertThrows(ApiException.class, () -> Json.canonical(Json.parse("1e-99999999")));
    assertThrows(ApiException.class, () -> Json.canonical(Json.parse("1e99999999")));
  }

  @Test
  void strictRecoveryOrdering() {
    var p =
        Json.parse(
            "{\"direction\":\"RANGE\",\"alert\":{\"low\":0,\"high\":10},\"warning\":{\"low\":1,\"high\":9},\"recovery\":{\"low\":2,\"high\":8}}");
    assertDoesNotThrow(() -> Admin.validateBounds(p));
    ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("recovery")).put("high", 9);
    assertThrows(ApiException.class, () -> Admin.validateBounds(p));
  }
}
