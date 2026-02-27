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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.ExecutionWarn;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration.TargetMode;
import io.gravitee.policy.ai.responsetransformer.configuration.ErrorMode;
import io.gravitee.policy.ai.responsetransformer.llm.EndpointGroupResolver;
import io.gravitee.policy.ai.responsetransformer.llm.ResolvedEndpoint;
import io.gravitee.policy.ai.responsetransformer.llm.TransformerLlmClient;
import io.gravitee.policy.api.annotations.OnResponse;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiResponseTransformerPolicy implements HttpPolicy {

  static final String METRIC_TRANSFORMED_COUNT =
    "long_ai-response-transformer_transformed-count";
  static final String METRIC_TRANSFORM_TIME_MS =
    "long_ai-response-transformer_processing-time-ms";

  private static final String WARN_KEY_FAIL_OPEN =
    "AI_RESPONSE_TRANSFORMER_FAIL_OPEN";
  private static final String FAILURE_KEY =
    "AI_RESPONSE_TRANSFORMER_BAD_REQUEST";
  private static final String TEMPLATE_MARKER_OPEN = "{#";
  private static final String TEMPLATE_MARKER_OPEN_ALT = "${";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Logger LOGGER = LoggerFactory.getLogger(
    AiResponseTransformerPolicy.class
  );

  private final AiResponseTransformerPolicyConfiguration configuration;
  private final EndpointGroupResolver endpointResolver;
  private final TransformerLlmClient llmClient;
  private final ResponseJsonInstructionsApplier jsonInstructionsApplier;

  public AiResponseTransformerPolicy(
    AiResponseTransformerPolicyConfiguration configuration
  ) {
    this(
      configuration,
      new EndpointGroupResolver(),
      new TransformerLlmClient()
    );
  }

  AiResponseTransformerPolicy(
    AiResponseTransformerPolicyConfiguration configuration,
    EndpointGroupResolver endpointResolver,
    TransformerLlmClient llmClient
  ) {
    this.configuration = configuration == null
      ? new AiResponseTransformerPolicyConfiguration()
      : configuration;
    this.endpointResolver = endpointResolver;
    this.llmClient = llmClient;
    this.jsonInstructionsApplier = new ResponseJsonInstructionsApplier();
  }

  @Override
  public String id() {
    return "ai-response-transformer";
  }

  @OnResponse
  @Override
  public Completable onResponse(HttpPlainExecutionContext ctx) {
    return ctx
      .response()
      .onBody(onBody ->
        onBody
          .switchIfEmpty(Maybe.just(Buffer.buffer()))
          .flatMap(body -> Maybe.fromCallable(() -> transformBody(ctx, body)))
      )
      .onErrorResumeNext(throwable -> {
        if (throwable instanceof TransformationFailureException e) {
          return ctx.interruptWith(
            new ExecutionFailure(HttpStatusCode.BAD_REQUEST_400)
              .key(FAILURE_KEY)
              .message(e.getMessage())
          );
        }

        return Completable.error(throwable);
      });
  }

  private Buffer transformBody(
    HttpPlainExecutionContext ctx,
    Buffer originalBody
  ) throws Exception {
    long startedAt = System.nanoTime();
    boolean transformed = false;

    try {
      int maxBodySize = configuration.getMaxResponseBodySize();
      if (maxBodySize > 0 && originalBody.length() > maxBodySize) {
        handleUntransformable(
          ctx,
          "Response body size exceeds configured maxResponseBodySize."
        );
        return originalBody;
      }

      String originalPayload = originalBody.toString();
      TargetingContext targeting = resolveTargeting(ctx, originalPayload);
      if (targeting.skipTransformation()) {
        return originalBody;
      }

      ResolvedEndpoint endpoint = endpointResolver.resolve(ctx, configuration);
      if (endpoint == null) {
        handleUntransformable(ctx, "No LLM endpoint could be resolved.");
        return originalBody;
      }

      String prompt = renderTemplate(ctx, configuration.getPrompt());
      String transformedBody;
      try {
        transformedBody = configuration.isUseOpenAiJsonResponseFormat()
          ? llmClient.transform(
            endpoint,
            prompt,
            targeting.inputForLlm(),
            configuration.getLlmTimeoutMs(),
            true
          )
          : llmClient.transform(
            endpoint,
            prompt,
            targeting.inputForLlm(),
            configuration.getLlmTimeoutMs()
          );
      } catch (Exception e) {
        handleUntransformable(
          ctx,
          "LLM call failed: " +
            (e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage())
        );
        return originalBody;
      }

      if (transformedBody == null || transformedBody.isBlank()) {
        handleUntransformable(ctx, "LLM returned an empty transformation.");
        return originalBody;
      }

      byte[] transformedBytes = transformedBody.getBytes(
        StandardCharsets.UTF_8
      );
      int maxLlmResponseBodySize = configuration.getMaxLlmResponseBodySize();
      if (
        maxLlmResponseBodySize > 0 &&
        transformedBytes.length > maxLlmResponseBodySize
      ) {
        handleUntransformable(
          ctx,
          "LLM response exceeds configured maxLlmResponseBodySize."
        );
        return originalBody;
      }

      String finalBody;
      try {
        finalBody = targeting.targetingEnabled()
          ? applyTargeting(targeting, transformedBody)
          : transformedBody;
      } catch (TransformationFailureException e) {
        handleUntransformable(ctx, e.getMessage());
        return originalBody;
      }

      if (configuration.isParseLlmResponseJsonInstructions()) {
        finalBody = jsonInstructionsApplier.applyIfPresent(ctx, finalBody);
      }

      transformed = true;
      return Buffer.buffer(finalBody);
    } finally {
      recordMetrics(ctx, startedAt, transformed);
    }
  }

  private TargetingContext resolveTargeting(
    HttpPlainExecutionContext ctx,
    String originalPayload
  ) {
    String targetPath = sanitizeTargetPath(configuration.getTargetPath());
    TargetMode targetMode = resolveTargetMode();
    boolean targetingEnabled = configuration.isJsonTargetingEnabled();

    if (!targetingEnabled) {
      return TargetingContext.noTargeting(originalPayload);
    }

    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(originalPayload);
    } catch (Exception e) {
      handleUntransformable(
        ctx,
        "Targeted transformation requires a valid JSON payload."
      );
      return TargetingContext.skip();
    }

    List<String> segments;
    try {
      segments = parsePath(targetPath);
    } catch (TransformationFailureException e) {
      handleUntransformable(ctx, e.getMessage());
      return TargetingContext.skip();
    }

    JsonNode selected = selectNode(root, segments);

    if (selected == null) {
      if (configuration.isTargetRequired()) {
        handleUntransformable(
          ctx,
          "Target path '" + targetPath + "' was not found in JSON payload."
        );
      }
      return TargetingContext.skip();
    }

    String llmInput = selected.isTextual()
      ? selected.asText()
      : selected.toString();
    return TargetingContext.targeting(root, segments, targetMode, llmInput);
  }

  private String applyTargeting(
    TargetingContext targeting,
    String transformedBody
  ) throws Exception {
    JsonNode transformedNode;
    try {
      transformedNode = OBJECT_MAPPER.readTree(transformedBody);
    } catch (Exception e) {
      throw new TransformationFailureException(
        "LLM output is not valid JSON for targeted transformation."
      );
    }

    if (targeting.targetMode() == TargetMode.MERGE_OBJECT_AT_ROOT) {
      if (!targeting.rootNode().isObject() || !transformedNode.isObject()) {
        throw new TransformationFailureException(
          "MERGE_OBJECT_AT_ROOT requires both original root and LLM output to be JSON objects."
        );
      }
      ObjectNode merged = ((ObjectNode) targeting.rootNode()).deepCopy();
      merged.setAll((ObjectNode) transformedNode);
      return OBJECT_MAPPER.writeValueAsString(merged);
    }

    if (targeting.pathSegments().isEmpty()) {
      return OBJECT_MAPPER.writeValueAsString(transformedNode);
    }

    JsonNode replaced = replaceAtPath(
      targeting.rootNode(),
      targeting.pathSegments(),
      transformedNode
    );
    return OBJECT_MAPPER.writeValueAsString(replaced);
  }

  private JsonNode replaceAtPath(
    JsonNode root,
    List<String> segments,
    JsonNode replacement
  ) {
    JsonNode copy = root.deepCopy();
    JsonNode cursor = copy;
    for (int i = 0; i < segments.size() - 1; i++) {
      cursor = cursor.get(segments.get(i));
      if (cursor == null || !cursor.isObject()) {
        throw new TransformationFailureException(
          "Target path points to an invalid parent node."
        );
      }
    }

    if (!cursor.isObject()) {
      throw new TransformationFailureException(
        "Target path points to a non-object parent node."
      );
    }

    ((ObjectNode) cursor).set(segments.get(segments.size() - 1), replacement);
    return copy;
  }

  private JsonNode selectNode(JsonNode root, List<String> segments) {
    JsonNode cursor = root;
    for (String segment : segments) {
      if (cursor == null || !cursor.isObject()) {
        return null;
      }
      cursor = cursor.get(segment);
    }
    return cursor;
  }

  private List<String> parsePath(String targetPath) {
    if ("$".equals(targetPath)) {
      return List.of();
    }

    if (!targetPath.startsWith("$.")) {
      throw new TransformationFailureException(
        "Unsupported target path format. Expected '$' or '$.field[.subField]'."
      );
    }

    String withoutPrefix = targetPath.substring(2);
    if (withoutPrefix.isBlank()) {
      throw new TransformationFailureException("Target path cannot be empty.");
    }
    if (withoutPrefix.contains("[") || withoutPrefix.contains("]")) {
      throw new TransformationFailureException(
        "Array targeting is not supported in this version."
      );
    }

    return List.of(withoutPrefix.split("\\."));
  }

  private String sanitizeTargetPath(String targetPath) {
    return targetPath == null || targetPath.isBlank() ? "$" : targetPath.trim();
  }

  private TargetMode resolveTargetMode() {
    return configuration.getTargetMode() == null
      ? TargetMode.REPLACE_TARGET
      : configuration.getTargetMode();
  }

  private void recordMetrics(
    HttpPlainExecutionContext ctx,
    long startedAtNanos,
    boolean transformed
  ) {
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
      System.nanoTime() - startedAtNanos
    );
    ctx
      .metrics()
      .putAdditionalMetric(METRIC_TRANSFORMED_COUNT, transformed ? 1L : 0L);
    ctx.metrics().putAdditionalMetric(METRIC_TRANSFORM_TIME_MS, elapsedMs);
  }

  private String renderTemplate(
    HttpPlainExecutionContext ctx,
    String rawTemplate
  ) {
    if (rawTemplate == null || !hasTemplateMarker(rawTemplate)) {
      return rawTemplate;
    }

    TemplateEngine templateEngine = ctx.getTemplateEngine();
    if (templateEngine == null) {
      throw new IllegalStateException("Template engine is not available.");
    }

    return templateEngine.convert(rawTemplate);
  }

  private boolean hasTemplateMarker(String value) {
    return (
      value != null &&
      (value.contains(TEMPLATE_MARKER_OPEN) ||
        value.contains(TEMPLATE_MARKER_OPEN_ALT))
    );
  }

  private void handleUntransformable(
    HttpPlainExecutionContext ctx,
    String message
  ) {
    if (resolveErrorMode() == ErrorMode.FAIL_CLOSED) {
      throw new TransformationFailureException(message);
    }

    LOGGER.warn(
      "Response transformation skipped in FAIL_OPEN mode: {}",
      message
    );
    ctx.warnWith(new ExecutionWarn(WARN_KEY_FAIL_OPEN).message(message));
  }

  private ErrorMode resolveErrorMode() {
    return configuration.getErrorMode() == null
      ? ErrorMode.FAIL_OPEN
      : configuration.getErrorMode();
  }

  private record TargetingContext(
    JsonNode rootNode,
    List<String> pathSegments,
    TargetMode targetMode,
    String inputForLlm,
    boolean targetingEnabled,
    boolean skipTransformation
  ) {
    static TargetingContext noTargeting(String originalPayload) {
      return new TargetingContext(
        null,
        List.of(),
        TargetMode.REPLACE_TARGET,
        originalPayload,
        false,
        false
      );
    }

    static TargetingContext targeting(
      JsonNode rootNode,
      List<String> pathSegments,
      TargetMode targetMode,
      String inputForLlm
    ) {
      return new TargetingContext(
        rootNode,
        pathSegments,
        targetMode,
        inputForLlm,
        true,
        false
      );
    }

    static TargetingContext skip() {
      return new TargetingContext(
        null,
        List.of(),
        TargetMode.REPLACE_TARGET,
        null,
        false,
        true
      );
    }
  }

  private static final class TransformationFailureException
    extends RuntimeException {

    private TransformationFailureException(String message) {
      super(message);
    }
  }
}
