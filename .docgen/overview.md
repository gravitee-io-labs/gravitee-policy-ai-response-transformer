Transforms upstream response payloads by calling an LLM and replacing the response body with the transformed result.

Choose LLM endpoint routing with `llmSourceMode`:
- `LLM_PROXY_API`: reuse an existing Gravitee LLM Proxy API (`llmProxyApiId` required, optional `llmModel` override).
- `INLINE`: call a direct endpoint from policy config (`llm.endpoint` required, optional `llm.model` + auth settings).

Request sent to LLM:
- `system`: configured `prompt` (EL/template rendering supported)
- `user`: full original response body

Optional `parseLlmResponseJsonInstructions` mode parses LLM output as `{status_code, headers, body}` and applies those instructions.

If transformation fails, behavior follows `errorMode`:
- **FAIL_OPEN**: pass-through original response
- **FAIL_CLOSED**: interrupt with HTTP `400`

Reported metrics:
- `long_ai-response-transformer_transformed-count`
- `long_ai-response-transformer_processing-time-ms`
