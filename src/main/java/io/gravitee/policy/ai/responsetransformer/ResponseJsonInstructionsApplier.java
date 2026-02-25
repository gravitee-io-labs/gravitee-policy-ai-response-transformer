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
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ResponseJsonInstructionsApplier {

  private static final Logger LOGGER = LoggerFactory.getLogger(
    ResponseJsonInstructionsApplier.class
  );
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  String applyIfPresent(HttpPlainExecutionContext ctx, String transformedBody) {
    try {
      JsonNode instructions = OBJECT_MAPPER.readTree(transformedBody);
      if (!instructions.isObject()) {
        return transformedBody;
      }

      JsonNode statusCodeNode = instructions.get("status_code");
      if (statusCodeNode != null && statusCodeNode.canConvertToInt()) {
        applyStatusCode(ctx, statusCodeNode.asInt());
      }

      JsonNode headersNode = instructions.get("headers");
      if (headersNode != null && headersNode.isObject()) {
        applyHeaders(ctx, headersNode);
      }

      JsonNode bodyNode = instructions.get("body");
      if (bodyNode == null || bodyNode.isNull()) {
        return "";
      }

      if (bodyNode.isTextual()) {
        return bodyNode.asText();
      }

      return bodyNode.toString();
    } catch (Exception e) {
      LOGGER.debug(
        "Could not parse response-transformer JSON instructions, using raw LLM output.",
        e
      );
      return transformedBody;
    }
  }

  private void applyStatusCode(HttpPlainExecutionContext ctx, int statusCode) {
    try {
      Object response = ctx.response();
      Method statusMethod = response.getClass().getMethod("status", int.class);
      statusMethod.invoke(response, statusCode);
    } catch (Exception e) {
      LOGGER.debug("Unable to apply response status from instructions.", e);
    }
  }

  private void applyHeaders(
    HttpPlainExecutionContext ctx,
    JsonNode headersNode
  ) {
    try {
      Object response = ctx.response();
      Method headersMethod = response.getClass().getMethod("headers");
      Object headers = headersMethod.invoke(response);
      if (headers == null) {
        return;
      }

      Method setMethod = findHeaderMethod(headers.getClass(), "set");
      Method addMethod = findHeaderMethod(headers.getClass(), "add");

      headersNode
        .fields()
        .forEachRemaining(entry -> {
          String key = entry.getKey();
          String value = entry.getValue().isTextual()
            ? entry.getValue().asText()
            : entry.getValue().toString();

          try {
            if (setMethod != null) {
              setMethod.invoke(headers, key, value);
            } else if (addMethod != null) {
              addMethod.invoke(headers, key, value);
            }
          } catch (Exception invokeError) {
            LOGGER.debug(
              "Unable to apply response header [{}] from instructions.",
              key,
              invokeError
            );
          }
        });
    } catch (Exception e) {
      LOGGER.debug("Unable to apply response headers from instructions.", e);
    }
  }

  private Method findHeaderMethod(Class<?> type, String methodName) {
    try {
      return type.getMethod(methodName, String.class, String.class);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }
}
