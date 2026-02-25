/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.ai.responsetransformer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AiResponseTransformerSchemaTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void shouldExposeTransformerSettings() throws Exception {
    JsonNode schema = loadSchema();

    assertThat(schema.at("/required/0").asText()).isEqualTo("prompt");
    assertThat(schema.at("/required/1").asText()).isEqualTo("llmSourceMode");

    assertThat(schema.at("/properties/prompt/type").asText()).isEqualTo(
      "string"
    );

    assertThat(
      schema.at("/properties/llmSourceMode/enum/0").asText()
    ).isEqualTo("LLM_PROXY_API");
    assertThat(
      schema.at("/properties/llmSourceMode/enum/1").asText()
    ).isEqualTo("INLINE");

    assertThat(
      schema.at("/properties/maxResponseBodySize/default").asInt()
    ).isEqualTo(1048576);
    assertThat(
      schema.at("/properties/maxLlmResponseBodySize/default").asInt()
    ).isEqualTo(1048576);
    assertThat(schema.at("/properties/llmTimeoutMs/default").asInt()).isEqualTo(
      30000
    );

    assertThat(
      schema.at("/properties/parseLlmResponseJsonInstructions/type").asText()
    ).isEqualTo("boolean");
    assertThat(
      schema
        .at("/properties/parseLlmResponseJsonInstructions/default")
        .asBoolean()
    ).isFalse();

    assertThat(
      schema.at("/properties/llm/properties/authType/enum/0").asText()
    ).isEqualTo("NONE");
    assertThat(
      schema.at("/properties/llm/properties/authType/enum/1").asText()
    ).isEqualTo("BEARER");
    assertThat(
      schema.at("/properties/llm/properties/authType/enum/2").asText()
    ).isEqualTo("HEADER");

    assertThat(
      schema.at("/properties/errorMode/description").asText()
    ).contains("response flow");

    assertThat(schema.at("/allOf/0/then/required/0").asText()).isEqualTo(
      "llmProxyApiId"
    );
    assertThat(
      schema.at("/allOf/0/then/properties/llmProxyApiId/minLength").asInt()
    ).isEqualTo(1);

    assertThat(schema.at("/allOf/1/then/required/0").asText()).isEqualTo("llm");
    assertThat(
      schema.at("/allOf/1/then/properties/llm/required/0").asText()
    ).isEqualTo("endpoint");
    assertThat(
      schema
        .at("/allOf/1/then/properties/llm/properties/endpoint/minLength")
        .asInt()
    ).isEqualTo(1);

    assertThat(schema.at("/properties/prompts").isMissingNode()).isTrue();
  }

  private JsonNode loadSchema() throws Exception {
    try (
      InputStream inputStream = getClass().getResourceAsStream(
        "/schemas/schema-form.json"
      )
    ) {
      assertThat(inputStream).isNotNull();
      return OBJECT_MAPPER.readTree(inputStream);
    }
  }
}
