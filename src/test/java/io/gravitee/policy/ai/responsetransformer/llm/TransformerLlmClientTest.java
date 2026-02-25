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
package io.gravitee.policy.ai.responsetransformer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TransformerLlmClientTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void shouldNormalizeTargetUrlForChatCompletions() {
    TransformerLlmClient client = new TransformerLlmClient();

    assertThat(
      client.normalizeTargetUrl(
        new ResolvedEndpoint("https://api.openai.com/v1", null, null, null)
      )
    ).isEqualTo("https://api.openai.com/v1/chat/completions");

    assertThat(
      client.normalizeTargetUrl(
        new ResolvedEndpoint(
          "https://api.openai.com/v1/chat/completions",
          null,
          null,
          null
        )
      )
    ).isEqualTo("https://api.openai.com/v1/chat/completions");
  }

  @Test
  void shouldBuildPayloadAndExtractAssistantContent() throws Exception {
    TransformerLlmClient client = new TransformerLlmClient();

    var payload = client.buildChatCompletionPayload(
      "gpt-4.1-mini",
      "system prompt",
      "user content"
    );

    assertThat(payload.path("model").asText()).isEqualTo("gpt-4.1-mini");
    assertThat(payload.path("messages").size()).isEqualTo(2);

    var response = OBJECT_MAPPER.readTree(
      """
      {
        "choices": [
          {
            "message": {
              "content": "transformed-response"
            }
          }
        ]
      }
      """
    );

    assertThat(client.extractAssistantContent(response)).isEqualTo(
      "transformed-response"
    );
  }
}
