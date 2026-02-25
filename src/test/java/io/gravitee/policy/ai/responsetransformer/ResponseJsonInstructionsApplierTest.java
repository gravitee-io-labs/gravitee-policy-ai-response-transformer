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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResponseJsonInstructionsApplierTest {

  @Mock
  private HttpPlainExecutionContext ctx;

  @Mock
  private HttpPlainResponse response;

  private ResponseJsonInstructionsApplier applier;

  @BeforeEach
  void setUp() {
    applier = new ResponseJsonInstructionsApplier();
  }

  @Test
  void shouldReturnOriginalBodyWhenInputIsNotJsonObject() {
    String transformedBody = "[]";

    String result = applier.applyIfPresent(ctx, transformedBody);

    assertThat(result).isEqualTo(transformedBody);
  }

  @Test
  void shouldApplyStatusCodeAndReturnBodyWhenInstructionsAreValid() {
    when(ctx.response()).thenReturn(response);

    String transformedBody = """
      {
        "status_code": 202,
        "body": "final-response"
      }
      """;

    String result = applier.applyIfPresent(ctx, transformedBody);

    verify(response).status(202);
    assertThat(result).isEqualTo("final-response");
  }

  @Test
  void shouldReturnEmptyStringWhenBodyIsExplicitlyNull() {
    String transformedBody = """
      {
        "body": null
      }
      """;

    String result = applier.applyIfPresent(ctx, transformedBody);

    assertThat(result).isEqualTo("");
  }

  @Test
  void shouldSerializeObjectBodyWhenBodyIsJsonObject() {
    String transformedBody = """
      {
        "body": {
          "message": "ok",
          "count": 2
        }
      }
      """;

    String result = applier.applyIfPresent(ctx, transformedBody);

    assertThat(result).isEqualTo("{\"message\":\"ok\",\"count\":2}");
  }
}
