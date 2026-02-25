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
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration.AuthType;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration.LlmSourceMode;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the target LLM endpoint using three modes:
 * 1) Selected external LLM Proxy API id (llmProxyApiId) via gateway ApiManager.
 * 2) LLM Proxy endpoint-group metadata exposed by the current API definition.
 * 3) Direct policy configuration fallback (llm.endpoint / llm.auth / llm.model).
 */
public class EndpointGroupResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(
    EndpointGroupResolver.class
  );
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String API_MANAGER_CLASS =
    "io.gravitee.gateway.handlers.api.manager.ApiManager";

  private static final String MAPI_URL_ENV =
    "GRAVITEE_POLICY_LLM_PROXY_MAPI_URL";
  private static final String MAPI_BASIC_AUTH_ENV =
    "GRAVITEE_POLICY_LLM_PROXY_MAPI_BASIC_AUTH";
  private static final String DEFAULT_MAPI_URL =
    "http://management_api:8083/management";
  private static final String DEFAULT_MAPI_BASIC_AUTH = "admin:admin";

  private final HttpClient managementHttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .build();

  private static final List<String> API_CLASS_CANDIDATES = List.of(
    "io.gravitee.definition.model.v4.Api",
    "io.gravitee.definition.model.Api"
  );

  public ResolvedEndpoint resolve(
    HttpPlainExecutionContext ctx,
    AiResponseTransformerPolicyConfiguration configuration
  ) {
    LlmSourceMode mode = configuration.getLlmSourceMode();

    if (mode == LlmSourceMode.INLINE) {
      return resolveFromDirectConfiguration(configuration);
    }

    if (mode == LlmSourceMode.LLM_PROXY_API) {
      return resolveFromSelectedOrManagementApi(
        ctx,
        configuration.getLlmProxyApiId(),
        configuration.getLlmModel()
      );
    }

    // Backward-compatible fallback when mode is absent.
    ResolvedEndpoint fromSelectedOrManagementApi =
      resolveFromSelectedOrManagementApi(
        ctx,
        configuration.getLlmProxyApiId(),
        configuration.getLlmModel()
      );
    if (fromSelectedOrManagementApi != null) {
      return fromSelectedOrManagementApi;
    }

    ResolvedEndpoint fromCurrentApi = resolveFromCurrentApiComponent(
      ctx,
      configuration.getLlmModel()
    );
    if (fromCurrentApi != null) {
      return fromCurrentApi;
    }

    return resolveFromDirectConfiguration(configuration);
  }

  private ResolvedEndpoint resolveFromSelectedOrManagementApi(
    HttpPlainExecutionContext ctx,
    String llmProxyApiIdRaw,
    String modelOverride
  ) {
    String llmProxyApiId = blankToNull(llmProxyApiIdRaw);
    if (llmProxyApiId == null) {
      return null;
    }

    ResolvedEndpoint fromSelectedApi = resolveFromSelectedLlmProxyApi(
      ctx,
      llmProxyApiId,
      modelOverride
    );
    if (fromSelectedApi != null) {
      return fromSelectedApi;
    }

    return resolveFromManagementApi(llmProxyApiId, modelOverride);
  }

  private ResolvedEndpoint resolveFromSelectedLlmProxyApi(
    HttpPlainExecutionContext ctx,
    String llmProxyApiId,
    String modelOverride
  ) {
    try {
      Class<?> apiManagerClass = Class.forName(API_MANAGER_CLASS);
      @SuppressWarnings("unchecked")
      Object apiManager = ctx.getComponent((Class<Object>) apiManagerClass);

      if (apiManager == null) {
        return null;
      }

      Method getMethod = apiManagerClass.getMethod("get", String.class);
      Object reactableApi = getMethod.invoke(apiManager, llmProxyApiId);
      if (reactableApi == null) {
        LOGGER.debug(
          "Selected llmProxyApiId [{}] is not deployed in gateway ApiManager.",
          llmProxyApiId
        );
        return null;
      }

      Method getDefinitionMethod = reactableApi
        .getClass()
        .getMethod("getDefinition");
      Object apiDefinition = getDefinitionMethod.invoke(reactableApi);
      JsonNode apiNode = OBJECT_MAPPER.valueToTree(apiDefinition);

      return resolveFromApiNode(apiNode, modelOverride);
    } catch (ClassNotFoundException ignored) {
      return null;
    } catch (Exception e) {
      LOGGER.debug(
        "Unable to resolve selected llmProxyApiId [{}] from ApiManager.",
        llmProxyApiId,
        e
      );
      return null;
    }
  }

  private ResolvedEndpoint resolveFromManagementApi(
    String llmProxyApiId,
    String modelOverride
  ) {
    try {
      String baseUrl = firstNonBlank(
        blankToNull(System.getenv(MAPI_URL_ENV)),
        DEFAULT_MAPI_URL
      );
      String basicAuth = firstNonBlank(
        blankToNull(System.getenv(MAPI_BASIC_AUTH_ENV)),
        DEFAULT_MAPI_BASIC_AUTH
      );

      HttpRequest request = HttpRequest.newBuilder()
        .uri(
          URI.create(baseUrl + "/v2/environments/DEFAULT/apis/" + llmProxyApiId)
        )
        .timeout(Duration.ofSeconds(3))
        .header("Authorization", "Basic " + encodeBasicAuth(basicAuth))
        .GET()
        .build();

      HttpResponse<String> response = managementHttpClient.send(
        request,
        HttpResponse.BodyHandlers.ofString()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        LOGGER.debug(
          "Unable to resolve llmProxyApiId [{}] from management API, status [{}].",
          llmProxyApiId,
          response.statusCode()
        );
        return null;
      }

      JsonNode apiNode = OBJECT_MAPPER.readTree(response.body());
      return resolveFromApiNode(apiNode, modelOverride);
    } catch (Exception e) {
      LOGGER.debug(
        "Unable to resolve llmProxyApiId [{}] from management API.",
        llmProxyApiId,
        e
      );
      return null;
    }
  }

  private ResolvedEndpoint resolveFromCurrentApiComponent(
    HttpPlainExecutionContext ctx,
    String modelOverride
  ) {
    JsonNode apiNode = extractApiNode(ctx);
    return resolveFromApiNode(apiNode, modelOverride);
  }

  private ResolvedEndpoint resolveFromApiNode(
    JsonNode apiNode,
    String modelOverride
  ) {
    if (apiNode == null) {
      return null;
    }

    JsonNode endpointGroups = apiNode.path("endpointGroups");
    if (!endpointGroups.isArray()) {
      return null;
    }

    for (JsonNode endpointGroup : endpointGroups) {
      if (!"llm-proxy".equals(endpointGroup.path("type").asText())) {
        continue;
      }

      ResolvedEndpoint fromEndpoint = resolveFromEndpointGroup(
        endpointGroup,
        modelOverride
      );
      if (fromEndpoint != null) {
        return fromEndpoint;
      }
    }

    return null;
  }

  private ResolvedEndpoint resolveFromEndpointGroup(
    JsonNode endpointGroup,
    String modelOverride
  ) {
    JsonNode endpoints = endpointGroup.path("endpoints");
    if (endpoints.isArray()) {
      for (JsonNode endpoint : endpoints) {
        ResolvedEndpoint resolved = resolveFromConfigurationNode(
          endpoint.path("configuration"),
          modelOverride
        );
        if (resolved != null) {
          return resolved;
        }
      }
    }

    return resolveFromConfigurationNode(
      endpointGroup.path("sharedConfiguration"),
      modelOverride
    );
  }

  private ResolvedEndpoint resolveFromConfigurationNode(
    JsonNode configurationNode,
    String modelOverride
  ) {
    if (configurationNode == null || configurationNode.isMissingNode()) {
      return null;
    }

    String target = blankToNull(configurationNode.path("target").asText());
    if (target == null) {
      return null;
    }

    JsonNode authentication = configurationNode.path("authentication");
    String authType = blankToNull(authentication.path("type").asText());
    String authHeader = null;
    String authValue = null;

    if ("API_KEY".equals(authType) || "HEADER".equals(authType)) {
      authHeader = firstNonBlank(
        blankToNull(authentication.path("header").asText()),
        blankToNull(authentication.path("headerName").asText()),
        "Authorization"
      );
      authValue = firstNonBlank(
        blankToNull(authentication.path("value").asText()),
        blankToNull(authentication.path("apiKey").asText())
      );
    } else if ("BEARER".equals(authType)) {
      authHeader = "Authorization";
      String token = firstNonBlank(
        blankToNull(authentication.path("token").asText()),
        blankToNull(authentication.path("bearer").asText()),
        blankToNull(authentication.path("value").asText())
      );
      authValue = token == null ? null : "Bearer " + token;
    }

    String model = firstNonBlank(
      blankToNull(modelOverride),
      readFirstModelName(configurationNode),
      blankToNull(configurationNode.path("model").asText())
    );

    return new ResolvedEndpoint(target, authHeader, authValue, model);
  }

  private JsonNode extractApiNode(HttpPlainExecutionContext ctx) {
    for (String className : API_CLASS_CANDIDATES) {
      try {
        Class<?> apiClass = Class.forName(className);
        @SuppressWarnings("unchecked")
        Object api = ctx.getComponent((Class<Object>) apiClass);
        if (api != null) {
          return OBJECT_MAPPER.valueToTree(api);
        }
      } catch (ClassNotFoundException ignored) {
        // Try next candidate.
      } catch (Exception e) {
        LOGGER.debug("Unable to extract API component [{}]", className, e);
      }
    }

    return null;
  }

  private ResolvedEndpoint resolveFromDirectConfiguration(
    AiResponseTransformerPolicyConfiguration configuration
  ) {
    AiResponseTransformerPolicyConfiguration.Llm llm = configuration.getLlm();
    if (llm == null || isBlank(llm.getEndpoint())) {
      return null;
    }

    String authHeader = null;
    String authValue = null;

    AuthType authType = llm.getAuthType() == null
      ? AuthType.NONE
      : llm.getAuthType();
    switch (authType) {
      case HEADER -> {
        authHeader = firstNonBlank(llm.getAuthHeader(), "Authorization");
        authValue = blankToNull(llm.getAuthValue());
      }
      case BEARER -> {
        authHeader = "Authorization";
        String token = blankToNull(llm.getAuthValue());
        authValue = token == null ? null : "Bearer " + token;
      }
      case NONE -> {
        // No auth header.
      }
    }

    String model = firstNonBlank(configuration.getLlmModel(), llm.getModel());
    return new ResolvedEndpoint(
      llm.getEndpoint(),
      authHeader,
      authValue,
      model
    );
  }

  private String readFirstModelName(JsonNode configurationNode) {
    JsonNode models = configurationNode.path("models");
    if (models.isArray() && !models.isEmpty()) {
      JsonNode first = models.get(0);
      String name = blankToNull(first.path("name").asText());
      if (name != null) {
        return name;
      }

      if (first.isTextual()) {
        return blankToNull(first.asText());
      }
    }

    return null;
  }

  private String encodeBasicAuth(String auth) {
    return Base64.getEncoder().encodeToString(auth.getBytes());
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }

    for (String value : values) {
      if (!isBlank(value)) {
        return value;
      }
    }

    return null;
  }

  private String blankToNull(String value) {
    return isBlank(value) ? null : value;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
