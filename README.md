# AI Response Transformer Policy

## Phases

| onRequest | onResponse | onMessageRequest | onMessageResponse |
| :-------: | :--------: | :--------------: | :---------------: |
|           |     X      |                  |                   |

## Description

`ai-response-transformer` transforms the upstream response payload by calling an LLM and replacing the response body with the LLM output.

Runtime flow:

1. Read upstream response body.
2. Resolve LLM endpoint from `llmSourceMode`:
   - `LLM_PROXY_API`: resolve from selected `llmProxyApiId`.
   - `INLINE`: use direct `llm.*` configuration.
3. Send OpenAI-compatible `/chat/completions` request:
   - `system` message: `prompt` (EL/template rendered when applicable).
   - `user` message: full original response body.
4. Replace response body with transformed content.

Optional mode: `parseLlmResponseJsonInstructions`

- Parse LLM output as JSON `{ "status_code": ..., "headers": {...}, "body": ... }`.
- Apply status and headers (best effort), and return `body` as final payload.

## Error handling

- `FAIL_OPEN` (default): pass through original response on transformation failure.
- `FAIL_CLOSED`: interrupt flow with HTTP `400`.

Applies to endpoint resolution failures, LLM failures, size-limit violations, and invalid transformed output.

## Compatibility matrix

| Plugin version | APIM version |
| :------------: | :----------: |
|      1.x       |    4.10.x    |

## Runtime prerequisites

- For `llmSourceMode=LLM_PROXY_API`, use APIM from the enhanced LLM Proxy branch:
  - https://github.com/gravitee-io/gravitee-api-management/tree/feat/enhanced-llm-proxy
- `llmSourceMode=INLINE` can be used without that branch-specific LLM Proxy API dependency.

## Configuration

> Source of truth: `src/main/resources/schemas/schema-form.json`

| Property | Required | Description | Type | Default |
| --- | --- | --- | --- | --- |
| `prompt` | Yes | System instructions sent to the LLM. | string | - |
| `llmSourceMode` | Yes | LLM source selector (`LLM_PROXY_API` or `INLINE`). | string | `INLINE` |
| `llmProxyApiId` | Cond. | Required when `llmSourceMode=LLM_PROXY_API`. | string | - |
| `llmModel` | No | Optional model override. | string | - |
| `llm.endpoint` | Cond. | Required when `llmSourceMode=INLINE`. | string(uri) | - |
| `llm.model` | No | Inline endpoint model. | string | - |
| `llm.authType` | No | `NONE`, `BEARER`, or `HEADER`. | string | `NONE` |
| `llm.authHeader` | No | Header name when `authType=HEADER`. | string | `Authorization` |
| `llm.authValue` | No | Bearer token or header value, depending on auth type. | string | - |
| `maxResponseBodySize` | No | Maximum response body size inspected (`0` = unlimited). | integer | `1048576` |
| `maxLlmResponseBodySize` | No | Maximum accepted transformed payload size (`0` = unlimited). | integer | `1048576` |
| `llmTimeoutMs` | No | LLM HTTP timeout in ms. | integer | `30000` |
| `parseLlmResponseJsonInstructions` | No | Parse LLM output into `status_code`, `headers`, and `body`. | boolean | `false` |
| `errorMode` | No | `FAIL_OPEN` or `FAIL_CLOSED`. | string | `FAIL_OPEN` |

## Example configuration

### LLM Proxy API mode

```json
{
  "prompt": "Rewrite this backend payload for end users.",
  "llmSourceMode": "LLM_PROXY_API",
  "llmProxyApiId": "<your-llm-proxy-api-id>",
  "llmModel": "meta-llama/llama-4-scout-17b-16e-instruct",
  "errorMode": "FAIL_OPEN"
}
```

### Inline mode

```json
{
  "prompt": "Rewrite this backend payload for end users.",
  "llmSourceMode": "INLINE",
  "parseLlmResponseJsonInstructions": true,
  "llm": {
    "endpoint": "https://api.openai.com/v1",
    "authType": "BEARER",
    "authValue": "${#secrets['OPENAI_API_KEY']}"
  },
  "llmModel": "gpt-4.1-mini",
  "maxResponseBodySize": 1048576,
  "maxLlmResponseBodySize": 1048576,
  "llmTimeoutMs": 30000,
  "errorMode": "FAIL_OPEN"
}
```

## Metrics

- `long_ai-response-transformer_transformed-count`
- `long_ai-response-transformer_processing-time-ms`
