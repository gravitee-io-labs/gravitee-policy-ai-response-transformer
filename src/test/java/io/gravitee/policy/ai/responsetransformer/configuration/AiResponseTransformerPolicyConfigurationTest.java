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
package io.gravitee.policy.ai.responsetransformer.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiResponseTransformerPolicyConfigurationTest {

  @Test
  void shouldExposeExpectedDefaults() {
    AiResponseTransformerPolicyConfiguration configuration =
      new AiResponseTransformerPolicyConfiguration();

    assertThat(configuration.getErrorMode()).isEqualTo(ErrorMode.FAIL_OPEN);
    assertThat(configuration.getMaxResponseBodySize()).isEqualTo(1024 * 1024);
    assertThat(configuration.getMaxLlmResponseBodySize()).isEqualTo(
      1024 * 1024
    );
    assertThat(configuration.getLlmTimeoutMs()).isEqualTo(30000);
    assertThat(configuration.getLlmSourceMode()).isNull();
    assertThat(configuration.isParseLlmResponseJsonInstructions()).isFalse();
    assertThat(configuration.getLlm()).isNotNull();
    assertThat(configuration.getLlm().getAuthType()).isEqualTo(
      AiResponseTransformerPolicyConfiguration.AuthType.NONE
    );
  }

  @Test
  void shouldStoreDirectLlmConfiguration() {
    AiResponseTransformerPolicyConfiguration configuration =
      new AiResponseTransformerPolicyConfiguration();

    configuration.setPrompt("rewrite");
    configuration.setLlmProxyApiId("api-2");
    configuration.setLlmModel("gpt-4.1");
    configuration.setParseLlmResponseJsonInstructions(true);

    AiResponseTransformerPolicyConfiguration.Llm llm =
      new AiResponseTransformerPolicyConfiguration.Llm();
    llm.setEndpoint("https://llm.example.com/v1");
    llm.setAuthType(AiResponseTransformerPolicyConfiguration.AuthType.HEADER);
    llm.setAuthHeader("X-API-Key");
    llm.setAuthValue("secret");
    configuration.setLlm(llm);

    assertThat(configuration.getPrompt()).isEqualTo("rewrite");
    assertThat(configuration.getLlmProxyApiId()).isEqualTo("api-2");
    assertThat(configuration.getLlmModel()).isEqualTo("gpt-4.1");
    assertThat(configuration.isParseLlmResponseJsonInstructions()).isTrue();
    assertThat(configuration.getLlm().getEndpoint()).isEqualTo(
      "https://llm.example.com/v1"
    );
    assertThat(configuration.getLlm().getAuthHeader()).isEqualTo("X-API-Key");
    assertThat(configuration.getLlm().getAuthValue()).isEqualTo("secret");
  }
}
