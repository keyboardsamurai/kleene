# 7. Testing

Previous: [6. Checking outputs](06-checking-outputs.md) | [Guide index](README.md) | Next: [8. Picking a Policy with Sweep](08-picking-a-policy-with-sweep.md)

You can test code that uses Kleene without a model. Kleene ships two test Judges in the main artifact:

- `ScriptedJudge` answers from a fixed script. Use it for unit tests of your branching logic.
- `ReplayJudge` returns real model answers that you recorded once. Use it to keep real answers in the default
  `mvn test`.

Kleene validates the answers of both as for any Judge.

## Script answers with ScriptedJudge

`ScriptedJudge` answers each Question by its **name** and records every request in `requests`:

```kotlin
val judge = ScriptedJudge {
    feels("urgent", 0.93)                                  // p(true)
    choose("route", "billing" to 0.8, "technical" to 0.2)  // a probability per label
    choose("team", "billing", p = 0.88)                    // the rest spread evenly over the other labels as asked
    score("clarity", 0.1, 0.3, 0.6)                        // a probability per level; expected is computed
    requirements(uploadError, "Says the upload failed" to 0.97, "Tells the user to retry" to 0.05)
}
val ai = Kleene(judge)
val urgent by ai.feels("Needs a response today")

assertEquals(TRUE, urgent("Checkout is down").truth)
assertEquals(1, judge.requests.size)   // one ask, one request
```

Both forms of `choose` and `score` take an optional `confidence`. The judge id is `scripted` and the model is
`scripted`.

`requirements(contract, ...)` scripts the Requirements of a Contract by their text, so you do not need to know the
names that `check` gives them. Requirements you leave out have no answer, so script every Requirement that `check`
asks.

Errors in a script:

| Mistake | Result |
|---|---|
| A Question name with no scripted answer | `IllegalStateException: no scripted answer for question "..."`, at ask time |
| `choose(name, label, ...)` with a label the Question does not ask | `IllegalStateException` that names the label, at ask time |
| `requirements` with a text the Contract holds zero times or twice, or the same text scripted twice | `IllegalArgumentException`, when the script is built |
| The same Question name scripted twice, by any of `feels`, `choose`, `score` or `requirements` | `IllegalArgumentException: question "..." scripted twice`, when the script is built |
| Probabilities that do not sum to 1 | `KleeneException.Malformed` from validation, as for a real Judge |

Use `judge.requests` to assert what was sent: the State and the wire Questions of each ask.

## Record real answers once, replay them in every build

`recordingTo` wraps any Judge. It appends each answered request and its response to a JSONL file, one line per
ask, together with the Judge id. A Judge that throws records nothing. A response that core rejects as
`KleeneException.Malformed` is recorded, and its replay throws `Malformed` again. `recordingTo` creates missing
parent directories of the file when it wraps the Judge. A line that it can not write (the path is a directory or
a read-only file, or its parent was removed later; a State text or a label is not valid Unicode) throws
`IOException` from `ask` after the model answered: that model call is spent.

`ReplayJudge` reads that file and returns the recorded response for an equal request. It never calls a model.

A Question belongs to the `Kleene` that created it, so define your Questions in a class that takes the `Kleene`.
Then the recording run and the replay run use the same definitions:

```kotlin
class Triage(ai: Kleene) {
    val urgent by ai.feels("Needs a response today")
}

// Once, with a model:
val live = Kleene(SystemOneJudge.fromEnv().recordingTo(Path.of("src/test/resources/triage.jsonl")))
Triage(live).urgent("Checkout is down")

// In unit tests:
val replay = Kleene(ReplayJudge(Path.of("src/test/resources/triage.jsonl")))
assertEquals(TRUE, Triage(replay).urgent("Checkout is down").truth)
```

Each recorded line looks like this:

```json
{"judge":"scripted","request":{"state":{"text":"Checkout is down"},"questions":[{"id":"urgent.3173dac6eec83f01","name":"urgent","kind":"FEELS","instructions":"Needs a response today","labels":[]}]},"response":{"model":"scripted","answers":{"urgent.3173dac6eec83f01":{"kind":"FEELS","p":0.93}},"usage":null,"requestId":null}}
```

Replay is strict ([ADR-0008](../adr/0008-strict-record-and-replay-are-in-kleene.md)):

- A request matches when its State (kind and value) and its wire Questions (id, name, kind, instructions, labels)
  are equal, in the same order.
- A request with no recording throws `IllegalStateException`. It never falls through to a live call. A changed
  message, instruction or option label needs a new recording.
- A missing or unreadable file, an empty file, a line that is not a recording (the message names the line), more
  than one Judge id, or one request with two different models or answers throws `IllegalArgumentException` when
  you build the `ReplayJudge`. One request recorded twice with the same model and answers replays the first
  recording.
- The replayed `Evidence`, `judge`, `model`, `usage` and `requestId` equal the recorded ones.

Replay is not reapply. To decide stored Evidence under another Policy, use `Verdict.at` or `Evidence.decide`
([4. Policy and Evidence](04-policy-and-evidence.md#reapply-a-policy-with-zero-model-calls)).

## Run the tests

```sh
mvn test                                         # unit tests; `live` excluded
mvn test -pl kleene -Dtest=EvidenceTest#name     # one test
```

Live smoke tests call a real Judge and run only on demand. For a local Judge, follow the safety rules in
[5. Judges](05-judges.md#run-a-local-judge-safely):

```sh
KLEENE_BASE_URL=http://127.0.0.1:8009 KLEENE_MODEL=kev-4b mvn test -Dgroups=live
```

## Next

[8. Picking a Policy with Sweep](08-picking-a-policy-with-sweep.md) uses stored, labeled Evidence to pick an `acceptAt`.

## Reference

Spec [§1.10 ScriptedJudge](../spec.md#110-scriptedjudge-tests),
[§1.12 Record and replay](../spec.md#112-record-and-replay).
