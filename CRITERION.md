# Criterion — nodes that come and go, on Redis, for a day

Written on 2026-10-28, before the repository exists and before a single line of code. Once
committed it is not edited: if a criterion turns out to be wrong, `FINDINGS.md` says so and this
file stays as it is. It is the repository's first commit.

## 1. Why, in this order

1. **Be genuinely useful, in public.** A distributed availability monitor: a set of URLs checked
   at intervals by several processes, with an alert when a service goes down and when it comes
   back. It runs continuously, scales by adding processes and is upgraded by replacing them,
   which is the shape a multi-agent system takes in production.
2. **Test the axis earlier example applications did not.** Three private applications have been
   built on Agenor from Maven Central (a Contract Net job dispatcher, a ticket router with human
   approval, an A2A/MCP research service). The first ran several processes on Redis, killed
   workers and restarted the manager, but gave each process a **fixed** node id and a stable
   population. None has run what the Spring Boot starter does by default: **a new node id on
   every start**, with no property to pin it (`spring-boot-starter.md`). None has changed the
   population while work was in flight, or run for longer than a test suite.
3. **Give a second case, or a refutation, to two decisions Agenor has deferred.** Discovery
   filtered by liveness was not added, pending a second application that needs it. Supervision
   (restart policy, backoff, escalation) was evaluated against the job dispatcher and **not
   opened**: restarting a process is the orchestrator's job, and what that application asked for
   was to see that an agent had crashed. Here an orchestrator actually restarts processes, so
   this example can confirm that verdict or overturn it.
4. **Know when to stop.** The earlier examples found behavioural defects. If this one finds only
   documentation defects, it is the last example written by Agenor's maintainer (§8).

## 2. The scenario, closed

- **Targets.** A `target` table in PostgreSQL (URL, interval, threshold). Targets are fake HTTP
  services, in a container of this repository, switched on and off on command. No third-party
  service is ever probed.
- **Probes** (`BaseAgent`, capability `probe`), one per `probe` process. They receive a request
  "check this URL" and answer with an outcome (HTTP status, latency, error).
- **Coordinator** (`BaseAgent`), in its own process. Each round it finds the probes with
  `findByCapability("probe")`, hands out targets with the **request** protocol, and publishes
  each outcome on the topic `outcomes`.
- **Alerter** (`BaseAgent`), in its own process, subscribed to `outcomes`. It decides when a
  target is down (N failed outcomes in a row) and when it is back, and writes the alert to a
  table and to a local webhook. A duplicate or missing alert is the error a user would see.
- **Host:** Spring Boot with the starter in all three roles. The node id the starter generates is
  accepted as it is: that is the point of the example.
- **Transport:** Redis (Valkey), JDBC directory on PostgreSQL. Presence is JDBC, because on Redis
  it does not exist (§3). In-memory only for unit tests.
- **Orchestrator:** `docker compose`, with `restart: unless-stopped` and `--scale probe=N`. No
  Kubernetes: the behaviour that matters (processes replaced, identity not surviving) is the
  same, at a fraction of the cost.
- **No LLM, no A2A, no MCP, no human approval.** The earlier examples covered them.

### The four failure regimes, caused on purpose

1. **Scale up and down:** from 2 to 5 probes and back, while work is in flight.
2. **Rolling upgrade:** each probe stopped with SIGTERM and replaced, one at a time.
3. **Death without goodbye:** `kill -9` of a probe, of the coordinator, of the alerter.
4. **The broker:** Valkey restarted **without persistence** (`--save "" --appendonly no`), so
   streams and consumer groups are gone. The job dispatcher stopped Valkey for 10 s with its
   data intact; this is the other case.

### The long run

**24 hours**, with a failure from regimes 1–3 every 15 minutes, chosen by a script with a fixed,
recorded seed. Regime 4 is tested separately, because it stops everything.

### Out of scope

- Node-to-node security: `redis.md` § Trust model states it is absent, and an example does not
  change that.
- Redis Cluster and Sentinel, broker failover, more than one directory.
- Leader election among coordinators. There is one coordinator; if it dies, the orchestrator
  restarts it.
- Throughput as a measure in itself. Load is there only so that failures happen while work is
  in flight.

## 3. What Agenor already has (from the public documentation, 0.39.0, 2026-10-28)

Read in the pages, not in the code. This example enters through the pages, and this section
records what they promise.

### Node identity and mailboxes

- `RedisMessagingFactory` generates a UUID `nodeId` on each `build()`. The starter generates one
  per JVM and has no property to set it (`spring-boot-starter.md`). The distributed quick start
  pins it by hand.
- **Each node has a stream, and it is a durable mailbox.** Its group is created at offset `0`, so
  a message for a node that has not started yet waits for it. "A node that never comes back
  keeps a stream. Nothing expires it" (`redis.md` § Point-to-point mechanics). The page covers a
  **reused** id, not an id that is never reused. Under the starter, every restart is a node that
  never comes back.
- Topic groups are created at `$`: a subscriber receives from that moment on. Every
  `subscribeTopic` creates a new group with a unique subscription id.

### Delivery and failure (`redis.md` § Delivery guarantees, § Failure modes)

- At-least-once, all the way into the agent's handler (ADR-033). Handlers must be idempotent.
- `XAUTOCLAIM` reclaims entries idle for longer than `pendingEntriesTimeoutMs` (30 s). The
  consumer loop reading **that** stream does it.
- After `maxDeliveryAttempts` (3) the message goes to the `:dlq` stream. Attempts are counted in
  memory, per consumer loop, so **a restart resets the count**.
- A `sendTo` to an agent in the same JVM does not go through Redis: on failure it is
  dead-lettered after a single attempt.
- The DLQ can be read (`deadLetterQueue().recent(n)`, the console). Replaying a message is the
  application's decision.

### Who is alive (`directory.md`, `jdbc-directory.md`, ADR-028)

- Presence is a separate opt-in, and heartbeats are **off** unless an interval is set.
  `getStatus` answers `UNKNOWN` past the staleness window, which depends on the backend.
- Presence on Redis: designed (ADR-028, phase B), **not built**. On this stack presence is JDBC.
- `findByCapability` does not filter by liveness.

### Send side

- The agent's mailbox is bounded, and so are concurrent handlers (ADR-032, ADR-033). Sending is
  **not** bounded.

## 4. The questions

Each one says what decides it. A question with no deciding criterion is not a question.

**The shared instrument: the reconciliation ledger.** Every coordinator request is written to a
table of this example before it is sent, with its message id. At the end, each must be in one of
these states:
- **answered**;
- **timed out**, with an error the application saw;
- **dead-lettered**;
- **lost**: none of the three.

An external script, reading Redis and PostgreSQL, assigns the states. Questions Q2–Q6 are
decided on this ledger, not on logs.

**Q1 — Can a developer get in with the public documentation alone?**
Every reading of Agenor's source needed to make progress is recorded: what was being looked for,
and which page lacked it. The entry diary is public and written for a reader who does not know
Agenor.

**Q2 — A probe dies without saying goodbye: where does its work end up?**
Regime 3, with the starter and an ephemeral id. Two measures:
- the messages in flight to the dead probe, and those sent to it between its death and the moment
  the coordinator stops choosing it, split across the four states;
- where the lost ones physically are: in the old node's stream, in a pending list nobody will
  reclaim, or elsewhere.

Decides: a message the framework accepted (the send succeeded) that ends up **lost** is an Agenor
defect, unless the page for the configuration in use says it can happen. If the page says so only
for another configuration (a reused id), it is a documentation defect.

**Q3 — Does the coordinator know who is alive?**
Regimes 1–3, with JDBC presence on as `jdbc-directory.md` describes. Decides:
- how long a dead probe stays among the results of `findByCapability`, and how many messages it
  is sent in that time;
- how many lines the application needs to avoid choosing it: `getStatus` per candidate, its own
  filter, a cache;
- whether those lines are generic (§5, point 1). If they are, this is the second case for
  liveness-filtered discovery, and the decision not to add it is reopened.

**Q4 — Does a rolling upgrade lose anything?**
Regime 2, SIGTERM only. Decides: with an orderly stop the ledger must have **no** lost entry. A
single one is a defect. What an orderly stop leaves behind in the directory and in Redis (rows,
streams, groups) is recorded as well.

**Q5 — What grows over time?**
The long run. Every hour:
- Redis keys by kind: node streams, topic streams, `:dlq`;
- consumer groups per stream;
- pending-list entries;
- rows in `agenor_agents`;
- Valkey memory.

Decides: anything that grows with the number of restarts, without bound and without a cleanup
described on a page, is a defect: of the page if the cleanup exists, of the framework if it does
not. Growth over time that stops at `maxStreamLength` is not a defect.

**Q6 — Does a poison message ever reach the DLQ?**
A special target makes the process of the probe that checks it terminate (`System.exit`, not an
exception), and the orchestrator restarts it. Decides:
- whether the message ends in the DLQ, in an endless cycle of deaths, or lost in an old node's
  stream;
- after how many deaths, and with what signal visible to an operator.

The comparison is with an exception in the handler, which `redis.md` documents: DLQ after 3
attempts.

**Q7 — The broker comes back empty: does the system notice?**
Regime 4. Decides: once Valkey is back with no data, do the consumer loops recreate their groups,
or fail with `NOGROUP` forever? And if they fail, does the application see it (an error, a
crashed state, a health check), or does the system stop working silently? Stopping silently is a
defect even if no page promises otherwise.

**Q8 — Does the application need something the orchestrator does not give?**
The question the job dispatcher closed with a no. Here the orchestrator restarts processes.
Every element of this example that exists because Agenor does not restart, retry or make
something visible carries `@supervision` in its Javadoc. What decides is the nature of that code,
not its amount:
- **restarting an agent inside a live process**: that is supervision, and the case the job
  dispatcher did not have;
- **making a state visible**: what the job dispatcher asked for, and this would be the second
  case;
- **the orchestrator's work**: does not count.

## 5. Decision rules

1. **Propose something to Agenor only for what is generic.** Generic: anyone running agents on
   replaceable processes would write the same code, whatever the domain.
2. **Only if Agenor cannot express it with what it has.** Inconvenient is not enough;
   inexpressible is.
3. **Each gap is phrased as a missing capability, never as a solution.** For example "a message
   for a node that will not return does not stay without an outcome", not "a TTL on streams".
4. **One application records, two design.** For liveness-filtered discovery and crash visibility
   this is the second case, if the lines confirm it (Q3, Q8). For everything else it is the first.
5. **Defects are fixed, gaps are recorded.** Defects, fixed in Agenor with a test that fails
   first:
   - a message lost without an outcome;
   - growth without bound and without cleanup;
   - a system that stops working silently;
   - a page describing a configuration other than the default one.
6. **Breaking fixes are batched; non-breaking ones ship as they come.** A fix that changes an
   observable contract waits for the others and ships with them in one release, announced as
   such.
7. **A fix is checked against the other transport.** Every defect found here is on Redis. Before
   closing it, write down what the same case does in memory. If the case does not exist in memory
   (a dying process takes everything with it), say so.
8. **The example does not work around.** If a defect blocks the application, the example records
   it and waits for the release that fixes it (§6). A workaround is written only to measure the
   rest, and carries `@friction`.

## 6. What is counted

- **Maven Central artifacts only**, from 0.39.0, never a local build. A version not yet published
  is friction to record.
- **The reconciliation ledger** (§4), with the script that computes it, versioned in the
  repository and runnable by anyone.
- **`@friction`** (where Agenor is missing or awkward) and **`@supervision`** (Q8) in the Javadoc,
  with the question they belong to. Lines counted by a script, not from memory.
- **The hourly series of Q5**, committed as CSV: the data an outside reader can reproduce.
- **The entry diary** (Q1), from the empty project to the first complete round across three
  processes. For each stretch: steps, pages read, source readings, time.
- **Agenor's API census before and after**, at 0.39.0 and at the release that closes the example,
  for the Redis messaging, directory and presence packages.

## 7. Stated expectations, so they are not rationalised afterwards

- **Q1:** the way in is `distributed-quick-start.md`, `spring-boot-starter.md`, `redis.md` and
  `jdbc-directory.md`. At least one source reading will be needed, to understand what happens to
  the stream of a node that will not come back.
- **Q2:**
  - The messages already pending for the dead probe, and those written to its stream after its
    death, end up **lost**: nobody reads that stream any more, because the replacement process has
    another id. The coordinator sees them as timed-out requests, so the application notices by
    timeout; the ledger counts them as timed out, not lost. The decisive case is a send that
    expects no reply.
  - It is the most likely defect of the example, and the page describes it only halfway.
- **Q3:**
  - A dead probe stays a candidate for at least the staleness window.
  - The application will write a filter of 15–30 lines, generic: the second case for
    liveness-filtered discovery.
- **Q4:** nothing lost. Every stopped probe leaves its stream and its groups behind, and one
  directory row per agent if the agent's name changes on each start.
- **Q5:**
  - Node streams grow by one per restart, without bound.
  - Topic groups grow by one per alerter restart.
  - In 24 hours, at one failure every 15 minutes, that is about 96 orphaned keys. Not a memory
    problem: a cleanup problem that no page describes for an ephemeral id.
- **Q6:** the poison message does **not** reach the DLQ. It stays pending in the dead probe's
  stream, and nobody reclaims it. The cycle stops by accident, not by design, and nobody sees it.
- **Q7:** unknown. It is the question with the least certain outcome, which is why it is here.
- **Q8:** no restarting of agents inside a live process. A few lines to make an unresponsive probe
  visible, confirming the job dispatcher's case. Expected verdict: supervision stays closed; crash
  visibility gets its second case.

If the numbers refute one of these, that is data and is written down as such.

## 8. When this example closes, and what it decides next

- It closes when Q1–Q8 each have a written outcome and the long run has gone to the end at least
  once on a release from Maven Central.
- **If it finds behavioural defects**, the maintainer's examples have not finished their job, and
  a fifth is legitimate, on an axis different from all four.
- **If it finds only documentation defects**, it is the last one. The next step is to put someone
  who did not write the framework in front of it: a user, an outside issue, a talk. Not a fifth
  example.
