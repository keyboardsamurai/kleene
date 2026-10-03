# 5. Judges

Previous: [4. Policy and Evidence](04-policy-and-evidence.md) | [Guide index](README.md) | Next: [6. Checking outputs](06-checking-outputs.md)

A Judge answers Questions with probability distributions read from model scores, never with generated text.
Kleene ships one HTTP Judge, `SystemOneJudge`. It talks to any System One server: TypeSafe's cloud API, or a
local Kev, Laya or openjev server. One ask is one `POST {baseUrl}/v1/systemone`.

## Create a SystemOneJudge

From the environment (see [1. Getting started](01-getting-started.md#configure-a-judge)):

```kotlin
val judge = SystemOneJudge.fromEnv()                                    // defaults: 10 s per attempt, 2 retries
val slow = SystemOneJudge.fromEnv(timeout = 5.minutes, maxRetries = 0)  // for a local Judge
```

Or with the constructor:

```kotlin
val cloud = SystemOneJudge(
    baseUrl = "https://api.typesafe.ai",
    model = "jev-1.13.0",
    apiKey = System.getenv("KLEENE_API_KEY"),
)
cloud.id   // "api.typesafe.ai/jev-1.13.0"
```

| Parameter | Default | Meaning |
|---|---|---|
| `baseUrl` | (required) | Server URL |
| `model` | (required) | Model name sent with each request |
| `apiKey` | `null` | Sent as `Authorization: Bearer`; no header when null |
| `timeout` | `10.seconds` | Bound on each attempt, headers and body, in real time |
| `maxRetries` | `2` | Extra attempts after the first |
| `httpClient` | `HttpClient.newHttpClient()` | The `java.net.http.HttpClient` to use |
| `id` | `"<host>/<model>"` | Tag recorded in every `Evidence`, `Rating` and `Answers` |

There is no fallback from one server to another: a local URL that does not answer throws, it never goes to the
cloud. To swap the Judge, create a new `Kleene`.

## Choose a server

| Server | Base URL | Status |
|---|---|---|
| TypeSafe cloud | `https://api.typesafe.ai` | Tested with `jev-1.13.0`. Pin a version: `jev-latest` moves |
| [Kev](https://github.com/jaredpalmer/kev) | `http://127.0.0.1:8009` | Tested. Echoes any model name |
| [openjev](https://github.com/razorback16/openjev) | your server | Wire-compatible, untested |
| [Laya](https://pypi.org/project/laya-mlx/) via [laya-server](https://github.com/phaser/laya-server) | `http://127.0.0.1:8010` | Wire-compatible, untested; see the warning below |

`confidence` values differ in meaning across servers. Kev, for example, computes its own formulas. Compare
`acceptAt` and `margin` across Judges, never `confidence`.

## Run a local Judge safely

A local Judge runs a model on your machine and can use a lot of memory. In this repository, follow these rules:

- Start a local Judge only with `scripts/kev.sh` (Kev, port 8009) or `scripts/laya.sh` (Laya, port 8010).
  Both run the server under `scripts/memguard.py`, which kills the whole server process tree when it uses more
  than `MEMGUARD_MAX_GB` (Kev 40, Laya 16) or when the system has less than `MEMGUARD_MIN_FREE_GB` free
  (default 16). Do not remove the guard.
- Run one local Judge server at a time. The scripts refuse to start when port 8009 or 8010 already listens.
- Give the client a timeout much longer than one request, for example
  `SystemOneJudge.fromEnv(timeout = 5.minutes, maxRetries = 0)`. After a timeout with retries left, the client
  sends the request again, but the server still computes the first one.
- To stop a run, stop the server (Ctrl-C or SIGTERM to its script), not only the client. Then check with `ps`
  that no server process is left.
- If the guard or a SIGKILL stops the server, do not restart it. Find out why first.

### Kev

Run `scripts/kev.sh` from a Kev checkout, after `uv sync --extra serve`. Then:

```sh
export KLEENE_BASE_URL=http://127.0.0.1:8009
export KLEENE_MODEL=kev-4b
```

### Laya (Apple Silicon)

`scripts/laya.sh` needs `uv` and macOS 14 or later, but no checkout. The first start downloads about 680 MB of
model files, plus Python 3.11 and the packages if uv has not cached them. Then:

```sh
export KLEENE_BASE_URL=http://127.0.0.1:8010
export KLEENE_MODEL=laya-mlx
```

Laya answers on the right wire, but its verdicts are not reliable
([ADR-0006](../adr/0006-laya-runs-behind-a-third-party-bridge.md)):

- The default multilingual checkpoint reads at most 1024 tokens of State, instructions and options. It drops the
  rest without an error.
- That checkpoint is not calibrated.
- In the demo it was wrong on most cells it decided ([demo/README.md](../../demo/README.md#laya)). Upstream Laya
  gives the same answers, so the cause is the model, not the MLX port
  ([demo/kalah.md](../../demo/kalah.md#laya-mlx-port-against-upstream)).

Do not use Laya for `check` without your own evaluation. The server logs two uvicorn warnings for each request
(`Unsupported upgrade request.` and `No supported WebSocket library detected`). They are harmless: they come from
the HTTP/2 upgrade header that `java.net.http.HttpClient` sends.

## Retries and timeouts

`SystemOneJudge` repeats an attempt, up to `maxRetries` times, on:

- HTTP 408, 429 and 5xx (including 529),
- a per-attempt timeout,
- an I/O error (connection refused, DNS, reset).

It never retries HTTP 400, 401, 403, 422 or a malformed response, and it never retries because an answer was
UNKNOWN. Before each retry it waits what the server asks for (`retry-after-ms`, else `Retry-After`). Without a
hint it waits 0.5 s x 2^n, capped at 5 s, with ±25 % jitter.

Cancelling the calling coroutine cancels the HTTP request or the wait. `CancellationException` is never wrapped.

## Handle errors

A failure means the Judge did not answer. It always throws a `KleeneException` and never becomes `UNKNOWN`:

| Exception | Cause |
|---|---|
| `Authentication` | HTTP 401 or 403. `status` holds the code |
| `InvalidRequest` | A client-side check failed, or HTTP 400 or 422 (for example an unknown model name) |
| `RateLimited` | HTTP 429 after the last retry. `retryAfter` holds the last delay the server asked for |
| `Overloaded` | HTTP 5xx after the last retry. `status` holds the code |
| `Unavailable` | The connection failed after the last retry ([ADR-0004](../adr/0004-unreachable-judge-is-unavailable.md)) |
| `Timeout` | Every attempt timed out |
| `Malformed` | The response is unusable: not JSON, wrong ids or kinds, numbers out of [0, 1], bad sums |
| `Unsupported` | Refused before sending: choose with fewer than 2 or more than 255 options, score with fewer than 2 or more than 10 levels |

```kotlin
val label = try {
    when (urgent(message).truth) {
        TRUE -> "urgent"
        FALSE -> "normal"
        UNKNOWN -> "review"          // the Judge answered, not clearly enough
    }
} catch (e: KleeneException.Timeout) {
    "retry later"                    // the Judge did not answer
}
```

## Write your own Judge

`Judge` is a `fun interface`: `suspend fun evaluate(request: Request): Response`. A Judge must return
distributions from model scores (ADR-0001). A chat model that writes its own confidence as text is not a Judge.
Kleene validates every `Response` from any Judge before it builds `Answers`, so a Judge never repairs its output.
For tests, use `ScriptedJudge` instead; see [7. Testing](07-testing.md).

## Next

[6. Checking outputs](06-checking-outputs.md) uses a Judge to test text against plain-language requirements.

## Reference

Spec [§1.8 Errors](../spec.md#18-errors), [§1.9 Judge SPI](../spec.md#19-judge-spi),
[§3.1 Adapter](../spec.md#31-adapter), [§3.3 Transport policy](../spec.md#33-transport-policy),
[§3.5 Support tiers](../spec.md#35-support-tiers).
