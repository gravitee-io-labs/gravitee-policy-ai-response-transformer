## Configure response transformation

The schema in `src/main/resources/schemas/schema-form.json` defines all constraints and defaults.

Required:
- `prompt`
- `llmSourceMode`

LLM source modes:
- `LLM_PROXY_API`:
  - requires `llmProxyApiId`
  - optional `llmModel` override
- `INLINE`:
  - requires `llm.endpoint`
  - optional `llm.model` and auth settings

Safety controls:
- `maxResponseBodySize` (`0` = unlimited)
- `maxLlmResponseBodySize` (`0` = unlimited)
- `llmTimeoutMs`
- `errorMode` (`FAIL_OPEN` or `FAIL_CLOSED`)

Optional response-instruction mode:
- `parseLlmResponseJsonInstructions=true` to parse `{status_code, headers, body}` from LLM output.

## Template rendering

`prompt` supports Gravitee EL/template expressions.

Examples:
- `{#request.headers['X-Tenant']}`
- `${request.headers['X-Tenant']}`

Example prompt:

```text
Rewrite response for tenant {#request.headers['X-Tenant']} in concise French.
```

## Data sent to the LLM

By default, the policy sends:
- `system`: rendered `prompt`
- `user`: full upstream response body

Response headers/status are not automatically copied into the `user` message; include only what you need through `prompt` templates.
