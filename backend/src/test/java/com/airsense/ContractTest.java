package com.airsense;

import static org.junit.jupiter.api.Assertions.*;

import com.airsense.common.*;
import com.airsense.threshold.ThresholdModels.Update;
import com.airsense.threshold.ThresholdValidator;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ContractTest {
  @Test
  void sharedFixtures() throws Exception {
    var contracts = new ContractValidator();
    for (var entry :
        JsonSupport.parse(Files.readString(Path.of("../contracts/fixtures/manifest.json")))) {
      String schema = entry.path("schema").asText();
      if (schema.startsWith("command")) continue;
      var payload =
          JsonSupport.parse(
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
    assertThrows(ApiException.class, () -> JsonSupport.parse("{\"a\":1,\"a\":2}"));
    assertThrows(ApiException.class, () -> JsonSupport.parse("{} {}"));
    assertThrows(ApiException.class, () -> JsonSupport.parse("{\"a\":NaN}"));
  }

  @Test
  void canonicalComparison() {
    assertEquals(
        JsonSupport.canonical(JsonSupport.parse("{\"a\":1.0,\"b\":[2,3]}")),
        JsonSupport.canonical(JsonSupport.parse("{\"b\":[2.0,3],\"a\":1}")));
    assertNotEquals(
        JsonSupport.canonical(JsonSupport.parse("[2,3]")),
        JsonSupport.canonical(JsonSupport.parse("[3,2]")));
  }

  @Test
  void crossLanguageCanonicalFixtures() throws Exception {
    for (var fixture :
        JsonSupport.parse(Files.readString(Path.of("../contracts/fixtures/canonical.json"))))
      assertEquals(fixture.path("expected").asText(), JsonSupport.canonical(fixture.path("input")));
  }

  @Test
  void exponentCannotExpandToUnboundedMemory() {
    assertThrows(ApiException.class, () -> JsonSupport.canonical(JsonSupport.parse("1e-99999999")));
    assertThrows(ApiException.class, () -> JsonSupport.canonical(JsonSupport.parse("1e99999999")));
  }

  @Test
  void strictRecoveryOrdering() {
    var p =
        JsonSupport.parse(
            "{\"direction\":\"RANGE\",\"alert\":{\"low\":0,\"high\":10},\"warning\":{\"low\":1,\"high\":9},\"recovery\":{\"low\":2,\"high\":8}}");
    assertDoesNotThrow(
        () -> new ThresholdValidator().validate(JsonSupport.MAPPER.convertValue(p, Update.class)));
    ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("recovery")).put("high", 9);
    assertThrows(
        ApiException.class,
        () -> new ThresholdValidator().validate(JsonSupport.MAPPER.convertValue(p, Update.class)));
  }
}
