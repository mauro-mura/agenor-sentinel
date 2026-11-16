# agenor-sentinel

A distributed uptime monitor built on [Agenor](https://www.agenor.dev/docs/), a multi-agent
framework for the JVM, and run for 24 hours while its processes were scaled, replaced and killed on
purpose. It is two things at once:

- **an application**: several processes check a set of URLs, and an alert goes out when a service
  goes down and again when it comes back;
- **an experiment**: what happens to the work in flight when the processes doing it come and go,
  measured rather than assumed. [`FINDINGS.md`](FINDINGS.md) is what it found.

It was built from the outside: Agenor **0.39.0** from Maven Central, the public documentation and
the Javadoc of the published jars, and no reading of Agenor's source.
[`docs/ENTRY-DIARY.md`](docs/ENTRY-DIARY.md) records the way in, page by page.

## How it works

Three roles, each a Spring Boot application with `agenor-spring-boot-starter`, each in its own
process:

| Role | Module | What it does |
|---|---|---|
| **Probe** | `sentinel-probe` | Receives "check this URL" and answers with the outcome: HTTP status, latency, error. Run as many as you like. |
| **Coordinator** | `sentinel-coordinator` | Each round, finds the probes that are alive, hands each due target to one of them with a request, and publishes every outcome on the topic `outcomes`. |
| **Alerter** | `sentinel-alerter` | Subscribed to `outcomes`. After N failed checks in a row it raises `DOWN`, on the next success `UP`, and writes each alert to a table and to a webhook. |

They talk over **Redis** (Valkey); the directory of agents and their presence are in
**PostgreSQL**. Every process takes a new node id each time it starts, which is the starter's
default and the point of the experiment: a process that is replaced does not come back as itself.

The monitored targets are **fake services** in `sentinel-fixtures`, switched up and down on
command. Nothing outside your machine is ever probed.

Two more modules serve the measurements: `sentinel-common` holds the messages and the database
schema, and `sentinel-reconcile` decides, for every request the coordinator sent, whether it was
answered, timed out, dead-lettered or lost.

## Run it

You need Java 21, Maven 3.9 and Docker or Podman with `docker compose`.

```bash
mvn -DskipTests package
SENTINEL_RESTART=unless-stopped docker compose up -d --build --scale probe=2
```

`SENTINEL_RESTART=unless-stopped` lets the orchestrator restart a process that exits. Without it,
a process that loses the start-up race described in [F6](FINDINGS.md) stays down. Ports are bound
to `127.0.0.1` only: Agenor does not authenticate nodes to each other.

Five targets are configured, all up. Take one down and watch the alert arrive:

```bash
curl -X POST http://127.0.0.1:58080/control/alpha/down
sleep 45
curl http://127.0.0.1:58080/webhook            # one JSON line per alert received
curl -X POST http://127.0.0.1:58080/control/alpha/up
```

`alpha` is checked every 10 s and alerts after 3 failures, so `DOWN` arrives within about 40 s.
The durable record is the `alert` table (PostgreSQL on `127.0.0.1:55432`, database, user and
password `sentinel`).

Change the number of probes while it runs, and the coordinator follows:

```bash
SENTINEL_RESTART=unless-stopped docker compose up -d --scale probe=4
```

Stop everything with `docker compose down`.

## The experiment

[`CRITERION.md`](CRITERION.md) was written before the first line of code and is never edited. It
asks eight questions, says in advance what would decide each one, and states the expected answers
so that they cannot be rationalised afterwards. The failures were caused on purpose, in four
regimes:

1. scaling the probes up and down while work is in flight;
2. a rolling upgrade: every probe stopped with SIGTERM and replaced, one at a time;
3. death without goodbye: `kill -9` of a probe, of the coordinator or of the alerter;
4. the broker restarted with no persistence, so it comes back empty.

Then a **24-hour run**: a failure from regimes 1–3 every 15 minutes, 96 in all, chosen with a
fixed, recorded seed.

Every number comes from a script in [`scripts/`](scripts), and the data each run produced is
committed under [`data/`](data), so a reader can check it rather than trust it.

| Script | What it does |
|---|---|
| `scripts/faults` | Causes the failures of one regime, and records each one |
| `scripts/long-run` | The 24-hour run, with an hourly census of what Redis and the database hold |
| `scripts/reconcile` | The final state of every request the coordinator sent |
| `scripts/measure`, `measure-q2`, `measure-q6` | The runs behind individual questions |

Each script describes its own options at the top of the file.

## What it found

All on Agenor 0.39.0. [`FINDINGS.md`](FINDINGS.md) gives the evidence and the reasoning for each
entry, and what it means for each question.

| | Finding | Kind |
|---|---|---|
| F1 | Package scanning finds no agent inside a Spring Boot executable jar | behavioural |
| F2 | The application's own Flyway fails when another process created the directory first | documentation |
| F3 | An orderly stop waits for a handler in flight, then drops its reply | documentation; behavioural in substance |
| F4 | A broker that comes back at a new address is never reached again, and health stays UP | behavioural |
| F5 | A broker that comes back empty stops every consumer loop, for good, and health stays UP | behavioural |
| F6 | Processes that start together on an empty database can fail Agenor's own migration | behavioural |
| F7 | No page says where a reply goes when its requester has died | documentation |
| F8 | What a restart leaves behind is never removed | two readings, undecided |

What held up: a probe killed mid-check loses no work, because its requests time out where the
coordinator sees them (Q2). A rolling upgrade loses nothing, although it fails the requests in
flight (Q4, F3). Twelve lines of application code were enough to stop choosing dead probes (Q3),
and the application never had to restart or retry anything itself (Q8).

What did not: a broker that came back empty stopped the system without anyone noticing (Q7, F5).
A message that kills the process handling it does not reach the dead-letter queue: after one
death it lies pending where nobody reads it (Q6).

## Status

All eight questions have a written outcome, and the 24-hour run has gone to the end on a release
from Maven Central. The "after" side of the API census that `CRITERION.md` §6 asks for waits for
the release that closes the example.

## Limits

This application was written by an AI coding assistant directed by Agenor's maintainer, not by a
developer meeting the framework for the first time. That it got in with the public documentation
alone shows the pages are **sufficient**, not that they are easy; `FINDINGS.md` § Q1 says what that
does and does not prove.

## License

[Apache 2.0](LICENSE), as Agenor.
