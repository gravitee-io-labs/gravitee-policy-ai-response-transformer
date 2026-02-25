# Changelog

## 1.0.0-SNAPSHOT

- Initial implementation of `ai-response-transformer` for APIM 4.10.x.
- Response-phase payload transformation through OpenAI-compatible `/chat/completions`.
- Explicit LLM source mode support:
  - `LLM_PROXY_API` (selected `llmProxyApiId`)
  - `INLINE` (direct `llm.*` endpoint configuration)
- Resolver support for real-world LLM proxy auth shape variants:
  - `header` / `headerName`
  - `value` / `apiKey`
  - bearer aliases (`token`, `bearer`, `value`)
- Optional JSON instruction mode:
  - `parseLlmResponseJsonInstructions` parses `{status_code, headers, body}`
- Configurable safety controls:
  - `maxResponseBodySize`
  - `maxLlmResponseBodySize`
  - `llmTimeoutMs`
  - `errorMode` (`FAIL_OPEN` / `FAIL_CLOSED`)
- EL/template rendering support for `prompt`.
- Runtime metrics:
  - `long_ai-response-transformer_transformed-count`
  - `long_ai-response-transformer_processing-time-ms`
