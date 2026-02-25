## Overview

`ai-response-transformer` calls an LLM to transform the upstream response body, then replaces the response body with the LLM output.

## Choosing the LLM source mode

Set `llmSourceMode` explicitly depending on how you want to route LLM calls:

1. `LLM_PROXY_API` (reuse an existing Gravitee LLM Proxy API)
   - Use when provider connectivity/models are centrally managed in Gravitee LLM Proxy.
   - Required: `llmProxyApiId`
   - Optional: `llmModel` (model override)

2. `INLINE` (call a provider endpoint directly from this policy)
   - Use for standalone setup or policy-local configuration.
   - Required: `llm.endpoint`
   - Optional: `llm.model`, `authType`, `authHeader`, `authValue`

## Configuration

### prompt
System instruction sent to the LLM. Supports EL/template rendering.

Example:

```text
Rewrite the upstream response for tenant {#request.headers['X-Tenant']} in plain French.
```

### parseLlmResponseJsonInstructions
When enabled, parse LLM output as JSON instructions and apply them to response status/headers/body.

Expected shape:

```json
{
  "status_code": 202,
  "headers": { "X-AI": "true" },
  "body": "transformed payload"
}
```

### maxResponseBodySize
Maximum original response body size eligible for transformation (`0` = unlimited).

### maxLlmResponseBodySize
Maximum transformed payload accepted from the LLM (`0` = unlimited).

### llmTimeoutMs
HTTP timeout used for the LLM call.

### errorMode
- `FAIL_OPEN`: pass through the original response when transformation cannot be applied.
- `FAIL_CLOSED`: interrupt response flow with HTTP `400`.

## Runtime behavior

- The **full upstream response body** is sent to the LLM as the `user` message.
- The policy does **not** automatically forward all response headers/status/request metadata as `user` content.
- You can still include selected context values explicitly via EL in `prompt`.
- LLM output replaces the response payload.
- If `parseLlmResponseJsonInstructions=true`, returned `{status_code, headers, body}` instructions may override response status/headers/body.
