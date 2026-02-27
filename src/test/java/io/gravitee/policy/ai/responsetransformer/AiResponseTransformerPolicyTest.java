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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainResponse;
import io.gravitee.policy.ai.responsetransformer.configuration.AiResponseTransformerPolicyConfiguration;
import io.gravitee.policy.ai.responsetransformer.configuration.ErrorMode;
import io.gravitee.policy.ai.responsetransformer.llm.EndpointGroupResolver;
import io.gravitee.policy.ai.responsetransformer.llm.ResolvedEndpoint;
import io.gravitee.policy.ai.responsetransformer.llm.TransformerLlmClient;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeTransformer;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiResponseTransformerPolicyTest {

  @Mock
  private HttpPlainExecutionContext ctx;

  @Mock
  private HttpPlainResponse response;

  @Mock
  private Metrics metrics;

  @Mock
  private TemplateEngine templateEngine;

  @Mock
  private EndpointGroupResolver endpointResolver;

  @Mock
  private TransformerLlmClient llmClient;

  @BeforeEach
  void setUp() {
    lenient().when(ctx.response()).thenReturn(response);
    lenient().when(ctx.metrics()).thenReturn(metrics);
    lenient().when(ctx.getTemplateEngine()).thenReturn(templateEngine);
    lenient()
      .when(ctx.interruptWith(any(ExecutionFailure.class)))
      .thenReturn(Completable.complete());
  }

  @Test
  void shouldTransformResponseBody() throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );

    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(
      llmClient.transform(any(), eq("rewrite"), eq("hello"), eq(30000))
    ).thenReturn("transformed");

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("transformed");
    verify(metrics).putAdditionalMetric(
      AiResponseTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      1L
    );
    verify(metrics).putAdditionalMetric(
      eq(AiResponseTransformerPolicy.METRIC_TRANSFORM_TIME_MS),
      anyLong()
    );
  }

  @Test
  void shouldParseJsonInstructionsWhenEnabled() throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setParseLlmResponseJsonInstructions(true);

    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(
      llmClient.transform(any(), eq("rewrite"), eq("raw-response"), eq(30000))
    ).thenReturn(
      "{\"status_code\":202,\"headers\":{\"X-AI\":\"true\"},\"body\":\"final-body\"}"
    );

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "raw-response");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("final-body");
  }

  @Test
  void shouldFallbackToOriginalBodyInFailOpenWhenTargetedOutputIsInvalidJson()
    throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setJsonTargetingEnabled(true);
    configuration.setTargetPath("$.message");

    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(
      llmClient.transform(any(), eq("rewrite"), eq("hello"), eq(30000))
    ).thenReturn("plain text");

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "{\"message\":\"hello\"}");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo(
      "{\"message\":\"hello\"}"
    );
  }

  @Test
  void shouldFallbackToOriginalBodyInFailOpenWhenTargetPathIsInvalid()
    throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setJsonTargetingEnabled(true);
    configuration.setTargetPath("message");

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "{\"message\":\"hello\"}");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo(
      "{\"message\":\"hello\"}"
    );
    verify(llmClient, never()).transform(any(), any(), any(), anyInt());
  }

  @Test
  void shouldFallbackToOriginalBodyInFailOpenWhenLlmCallFails()
    throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(llmClient.transform(any(), any(), any(), anyInt())).thenThrow(
      new IllegalStateException("status 401")
    );

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("hello");
    verify(ctx, never()).interruptWith(any(ExecutionFailure.class));
    verify(metrics).putAdditionalMetric(
      AiResponseTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      0L
    );
  }

  @Test
  void shouldInterruptInFailClosedWhenLlmCallFails() throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_CLOSED
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(llmClient.transform(any(), any(), any(), anyInt())).thenThrow(
      new IllegalStateException("status 401")
    );

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    ArgumentCaptor<ExecutionFailure> captor = ArgumentCaptor.forClass(
      ExecutionFailure.class
    );
    verify(ctx).interruptWith(captor.capture());
    assertThat(captor.getValue().statusCode()).isEqualTo(
      HttpStatusCode.BAD_REQUEST_400
    );
    assertThat(captor.getValue().message()).contains("LLM call failed");
    verify(metrics).putAdditionalMetric(
      AiResponseTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      0L
    );
  }

  @Test
  void shouldInterruptInFailClosedWhenEndpointCannotBeResolved() {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_CLOSED
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(null);

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    ArgumentCaptor<ExecutionFailure> captor = ArgumentCaptor.forClass(
      ExecutionFailure.class
    );
    verify(ctx).interruptWith(captor.capture());
    assertThat(captor.getValue().statusCode()).isEqualTo(
      HttpStatusCode.BAD_REQUEST_400
    );
    assertThat(captor.getValue().message()).contains("No LLM endpoint");
    verify(metrics).putAdditionalMetric(
      AiResponseTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      0L
    );
  }

  @Test
  void shouldRenderTemplateBeforeCallingLlm() throws Exception {
    AiResponseTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setPrompt("Prompt {#ctx.id}");

    when(templateEngine.convert("Prompt {#ctx.id}")).thenReturn("Prompt 42");
    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(
      llmClient.transform(any(), eq("Prompt 42"), eq("hello"), eq(30000))
    ).thenReturn("ok");

    AiResponseTransformerPolicy policy = new AiResponseTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("ok");
    verify(templateEngine).convert("Prompt {#ctx.id}");
  }

  private PolicyResult execute(
    AiResponseTransformerPolicy policy,
    String body
  ) {
    AtomicReference<Buffer> transformedBodyRef = new AtomicReference<>();
    lenient()
      .when(response.onBody(any()))
      .thenAnswer(invocation -> {
        @SuppressWarnings("unchecked")
        MaybeTransformer<Buffer, Buffer> transformer = invocation.getArgument(
          0
        );

        return Maybe.wrap(transformer.apply(Maybe.just(Buffer.buffer(body))))
          .doOnSuccess(transformedBodyRef::set)
          .ignoreElement();
      });

    TestObserver<Void> observer = policy.onResponse(ctx).test();
    observer.awaitDone(5, TimeUnit.SECONDS);

    if (transformedBodyRef.get() == null) {
      transformedBodyRef.set(Buffer.buffer(body));
    }

    return new PolicyResult(observer, transformedBodyRef.get());
  }

  private AiResponseTransformerPolicyConfiguration baseConfiguration(
    ErrorMode errorMode
  ) {
    AiResponseTransformerPolicyConfiguration configuration =
      new AiResponseTransformerPolicyConfiguration();
    configuration.setErrorMode(errorMode);
    configuration.setPrompt("rewrite");
    configuration.setLlmTimeoutMs(30000);
    configuration.setMaxResponseBodySize(1024 * 1024);
    configuration.setMaxLlmResponseBodySize(1024 * 1024);
    return configuration;
  }

  private record PolicyResult(
    TestObserver<Void> observer,
    Buffer transformedBody
  ) {}
}
