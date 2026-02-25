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

import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndpointGroupResolverTest {

  @Test
  void shouldResolveInlineBearerConfiguration() {
    EndpointGroupResolver resolver = new EndpointGroupResolver();
    AiResponseTransformerPolicyConfiguration configuration =
      new AiResponseTransformerPolicyConfiguration();
    configuration.setLlmSourceMode(
      AiResponseTransformerPolicyConfiguration.LlmSourceMode.INLINE
    );
    configuration.setLlmModel("model-override");

    AiResponseTransformerPolicyConfiguration.Llm llm =
      new AiResponseTransformerPolicyConfiguration.Llm();
    llm.setEndpoint("https://llm.example.com/v1");
    llm.setAuthType(AiResponseTransformerPolicyConfiguration.AuthType.BEARER);
    llm.setAuthValue("token-123");
    llm.setModel("fallback-model");
    configuration.setLlm(llm);

    ResolvedEndpoint endpoint = resolver.resolve(
      Mockito.mock(HttpPlainExecutionContext.class),
      configuration
    );

    assertThat(endpoint).isNotNull();
    assertThat(endpoint.target()).isEqualTo("https://llm.example.com/v1");
    assertThat(endpoint.authHeader()).isEqualTo("Authorization");
    assertThat(endpoint.authValue()).isEqualTo("Bearer token-123");
    assertThat(endpoint.model()).isEqualTo("model-override");
  }

  @Test
  void shouldNotFallbackToInlineWhenModeIsExplicitProxyAndProxyIdMissing() {
    EndpointGroupResolver resolver = new EndpointGroupResolver();
    AiResponseTransformerPolicyConfiguration configuration =
      new AiResponseTransformerPolicyConfiguration();
    configuration.setLlmSourceMode(
      AiResponseTransformerPolicyConfiguration.LlmSourceMode.LLM_PROXY_API
    );

    AiResponseTransformerPolicyConfiguration.Llm llm =
      new AiResponseTransformerPolicyConfiguration.Llm();
    llm.setEndpoint("https://llm.example.com/v1");
    configuration.setLlm(llm);

    ResolvedEndpoint endpoint = resolver.resolve(
      Mockito.mock(HttpPlainExecutionContext.class),
      configuration
    );

    assertThat(endpoint).isNull();
  }
}
