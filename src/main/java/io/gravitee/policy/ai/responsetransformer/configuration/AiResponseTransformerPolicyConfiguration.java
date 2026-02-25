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

import io.gravitee.policy.api.PolicyConfiguration;

public class AiResponseTransformerPolicyConfiguration
  implements PolicyConfiguration {

  public static final int DEFAULT_MAX_RESPONSE_BODY_SIZE = 1024 * 1024;
  public static final int DEFAULT_MAX_LLM_RESPONSE_BODY_SIZE = 1024 * 1024;
  public static final int DEFAULT_LLM_TIMEOUT_MS = 30000;

  private String prompt;

  private String llmProxyApiId;

  private String llmModel;

  private LlmSourceMode llmSourceMode;

  private Llm llm = new Llm();

  private int maxResponseBodySize = DEFAULT_MAX_RESPONSE_BODY_SIZE;

  private int maxLlmResponseBodySize = DEFAULT_MAX_LLM_RESPONSE_BODY_SIZE;

  private int llmTimeoutMs = DEFAULT_LLM_TIMEOUT_MS;

  private boolean parseLlmResponseJsonInstructions;

  private ErrorMode errorMode = ErrorMode.FAIL_OPEN;

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public String getLlmProxyApiId() {
    return llmProxyApiId;
  }

  public void setLlmProxyApiId(String llmProxyApiId) {
    this.llmProxyApiId = llmProxyApiId;
  }

  public String getLlmModel() {
    return llmModel;
  }

  public void setLlmModel(String llmModel) {
    this.llmModel = llmModel;
  }

  public LlmSourceMode getLlmSourceMode() {
    return llmSourceMode;
  }

  public void setLlmSourceMode(LlmSourceMode llmSourceMode) {
    this.llmSourceMode = llmSourceMode;
  }

  public Llm getLlm() {
    return llm;
  }

  public void setLlm(Llm llm) {
    this.llm = llm;
  }

  public int getMaxResponseBodySize() {
    return maxResponseBodySize;
  }

  public void setMaxResponseBodySize(int maxResponseBodySize) {
    this.maxResponseBodySize = maxResponseBodySize;
  }

  public int getMaxLlmResponseBodySize() {
    return maxLlmResponseBodySize;
  }

  public void setMaxLlmResponseBodySize(int maxLlmResponseBodySize) {
    this.maxLlmResponseBodySize = maxLlmResponseBodySize;
  }

  public int getLlmTimeoutMs() {
    return llmTimeoutMs;
  }

  public void setLlmTimeoutMs(int llmTimeoutMs) {
    this.llmTimeoutMs = llmTimeoutMs;
  }

  public boolean isParseLlmResponseJsonInstructions() {
    return parseLlmResponseJsonInstructions;
  }

  public void setParseLlmResponseJsonInstructions(
    boolean parseLlmResponseJsonInstructions
  ) {
    this.parseLlmResponseJsonInstructions = parseLlmResponseJsonInstructions;
  }

  public ErrorMode getErrorMode() {
    return errorMode;
  }

  public void setErrorMode(ErrorMode errorMode) {
    this.errorMode = errorMode;
  }

  public static class Llm {

    private String endpoint;

    private String model;

    private AuthType authType = AuthType.NONE;

    private String authHeader = "Authorization";

    private String authValue;

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public AuthType getAuthType() {
      return authType;
    }

    public void setAuthType(AuthType authType) {
      this.authType = authType;
    }

    public String getAuthHeader() {
      return authHeader;
    }

    public void setAuthHeader(String authHeader) {
      this.authHeader = authHeader;
    }

    public String getAuthValue() {
      return authValue;
    }

    public void setAuthValue(String authValue) {
      this.authValue = authValue;
    }
  }

  public enum AuthType {
    NONE,
    BEARER,
    HEADER,
  }

  public enum LlmSourceMode {
    LLM_PROXY_API,
    INLINE,
  }
}
