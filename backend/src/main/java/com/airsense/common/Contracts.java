package com.airsense.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Contracts {
  private final Map<String, JsonSchema> schemas = new HashMap<>();

  public Contracts() throws Exception {
    var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    for (String name : List.of("telemetry", "state", "metadata", "adc-profile", "receipt"))
      schemas.put(
          name,
          factory.getSchema(
              Json.MAPPER.readTree(
                  getClass().getResourceAsStream("/contracts/schemas/" + name + ".schema.json")),
              config));
    var api = Json.MAPPER.readTree(getClass().getResourceAsStream("/contracts/openapi.json"));
    for (String name : List.of("ThresholdUpdate", "Login"))
      schemas.put(
          name, factory.getSchema(api.path("components").path("schemas").path(name), config));
  }

  public void validate(String name, JsonNode p) {
    if (!schemas.get(name).validate(p).isEmpty())
      throw new ApiException(
          400, "INVALID_PAYLOAD", "Payload does not match the " + name + " contract.");
  }
}
