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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI-compatible LLM HTTP client used by transformer policies.
 */
public class TransformerLlmClient {

  public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final HttpClient httpClient;

  public TransformerLlmClient() {
    this(HttpClient.newBuilder().build());
  }

  public TransformerLlmClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public String transform(
    ResolvedEndpoint endpoint,
    String systemPrompt,
    String userContent,
    int timeoutMs
  ) throws Exception {
    if (
      endpoint == null ||
      endpoint.target() == null ||
      endpoint.target().isBlank()
    ) {
      throw new IllegalArgumentException(
        "Resolved endpoint target is required."
      );
    }

    ObjectNode payload = buildChatCompletionPayload(
      endpoint.model(),
      systemPrompt,
      userContent
    );

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
      .uri(URI.create(normalizeTargetUrl(endpoint)))
      .timeout(Duration.ofMillis(Math.max(timeoutMs, 1)))
      .header("Content-Type", "application/json")
      .POST(
        HttpRequest.BodyPublishers.ofString(
          OBJECT_MAPPER.writeValueAsString(payload),
          StandardCharsets.UTF_8
        )
      );

    if (endpoint.authHeader() != null && !endpoint.authHeader().isBlank()) {
      String authValue = endpoint.authValue() == null
        ? ""
        : endpoint.authValue();
      requestBuilder.header(endpoint.authHeader(), authValue);
    }

    HttpResponse<String> response = httpClient.send(
      requestBuilder.build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    );

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
        "LLM call failed with status " + response.statusCode()
      );
    }

    JsonNode llmResponse = OBJECT_MAPPER.readTree(response.body());
    return extractAssistantContent(llmResponse);
  }

  public ObjectNode buildChatCompletionPayload(
    String model,
    String systemPrompt,
    String userContent
  ) {
    ObjectNode payload = OBJECT_MAPPER.createObjectNode();

    if (model != null && !model.isBlank()) {
      payload.put("model", model);
    }

    ArrayNode messages = payload.putArray("messages");
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      messages.add(createMessage("system", systemPrompt));
    }
    messages.add(createMessage("user", userContent == null ? "" : userContent));

    return payload;
  }

  public String extractAssistantContent(JsonNode llmResponse) {
    if (llmResponse == null || llmResponse.isNull()) {
      return null;
    }

    JsonNode messageContent = llmResponse
      .path("choices")
      .path(0)
      .path("message")
      .path("content");
    if (messageContent.isTextual()) {
      return messageContent.asText();
    }

    JsonNode outputText = llmResponse.path("output_text");
    if (outputText.isTextual()) {
      return outputText.asText();
    }

    return null;
  }

  public String normalizeTargetUrl(ResolvedEndpoint endpoint) {
    if (endpoint == null || endpoint.target() == null) {
      return null;
    }

    String target = endpoint.target().trim();
    if (target.endsWith(CHAT_COMPLETIONS_PATH)) {
      return target;
    }

    if (target.endsWith("/")) {
      return target.substring(0, target.length() - 1) + CHAT_COMPLETIONS_PATH;
    }

    return target + CHAT_COMPLETIONS_PATH;
  }

  private ObjectNode createMessage(String role, String content) {
    ObjectNode message = OBJECT_MAPPER.createObjectNode();
    message.put("role", role);
    message.put("content", content);
    return message;
  }
}
