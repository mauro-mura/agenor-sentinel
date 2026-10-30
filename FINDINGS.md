# Findings

What this example found in Agenor, and where CRITERION.md turned out to be wrong. CRITERION.md is
frozen: a criterion that proves wrong is corrected here, never there.

Every finding names the Agenor version it was observed on, the evidence, and its kind:

- **behavioural defect**: Agenor does something a page does not allow, or fails silently
  (CRITERION §5, point 5);
- **documentation defect**: a page is missing, wrong, or describes another configuration;
- **criterion correction**: CRITERION.md assumed something that is not so.

Documentation gaps met on the way in are recorded first in the entry diary
(`docs/ENTRY-DIARY.md`); the ones that matter beyond Q1 are repeated here.

## F1. Package scanning finds no agent inside a Spring Boot executable jar

- **Kind:** behavioural defect.
- **Version:** 0.39.0, with `agenor-spring-boot-starter` on Spring Boot 4.0.9, Java 21.
- **Configuration:** the starter's quick start: `agenor.agents.base-package` naming the package of
  an `@Agent` class, application packaged with `spring-boot-maven-plugin` and started with
  `java -jar`, which is how Spring Boot applications are normally shipped.
- **What happens:** the scan throws, the error is logged, and the runtime starts with no agent:

  ```
  ERROR d.agenor.runtime.discovery.AgentScanner : Error scanning package: dev.agenor.sentinel.probe.agents
  java.nio.file.NoSuchFileException: nested:/app/app.jar
      at java.base/java.util.zip.ZipFile.<init>(ZipFile.java:251)
      at java.base/java.util.jar.JarFile.<init>(JarFile.java:345)
      ...
      at dev.agenor.runtime.discovery.AgentScanner.scanJarFile(AgentScanner.java:194)
      at dev.agenor.runtime.discovery.AgentScanner.scanPackage(AgentScanner.java:106)
      at dev.agenor.runtime.discovery.AgentScanner.scanForAgents(AgentScanner.java:43)
      at dev.agenor.runtime.AgenorRuntime.discoverAndCreateAgents(AgenorRuntime.java:647)
  INFO  d.agenor.runtime.discovery.AgentScanner : Agent discovery completed. Found 0 agent classes
  INFO  dev.agenor.runtime.AgenorRuntime        : Agenor Runtime started successfully with 0 agents
  ```

  The frames are from the stack trace the application logged; Agenor's source was not read.
  The path `nested:/app/app.jar` is Spring Boot's URL form for a class inside the executable jar,
  and the scanner opens it as if it were a file.
- **Evidence that packaging is the cause:** the same jar, extracted with Spring Boot's own
  `java -Djarmode=tools -jar app.jar extract` and started from the extracted layout, finds the
  agent (`Found 1 agent classes`, `started successfully with 1 agents`). Both runs on the host,
  2026-10-29 14:55 and 14:56 (+01:00).
- **Why it is a defect, not a documentation gap:** the process stays up, `start()` reports
  success, and the starter's health indicator answers `UP` with `agents.total: 0`:

  ```
  {"components":{"Agenor":{"details":{"runtime.name":"sentinel-probe","agents.total":0,
   "agents.running":0},"status":"UP"}, ...},"status":"UP"}
  ```

  An orchestrator has no reason to restart it. The only signal is one ERROR line. That is
  "a system that stops working silently" in CRITERION §5, point 5, for the configuration the
  starter page presents first.
- **In memory (CRITERION §5, point 7):** the transport does not matter here. The same failure
  happens before any messaging is involved.
- **What this example does meanwhile:** the container image extracts the jar and starts from the
  extracted layout (`Dockerfile`, marked `@friction`). The probe's code stays on the documented
  scanning path, so the workaround goes away with the fix.
- **Missing capability, phrased as CRITERION §5 point 3 asks:** an application packaged the way
  Spring Boot packages applications can declare its agents with `base-package`, and a node whose
  declared agents were not found does not report itself as started.

## F2. The application's own Flyway fails when another process created the directory first

- **Kind:** documentation defect.
- **Version:** 0.39.0, with `agenor-spring-boot-starter` on Spring Boot 4.0.9, JDBC directory on
  PostgreSQL 16.15.
- **What the pages say:** `spring-boot-starter`: "Your application's own Flyway
  (spring-boot-starter-flyway) can use the same database with Boot's defaults. Agenor's
  migrations are outside db/migration and run after Boot's Flyway, and each schema keeps its own
  history table, so spring.flyway.* needs no setting for Agenor." `adapters/jdbc-directory`: the
  directory "can share a database with the HITL queue and with your own schema, created in any
  order."
- **What happens:** the coordinator and the alerter run the application's migrations with Boot's
  Flyway; the probes run none. On 2026-10-29 at 21:10 UTC the probes started first, and Agenor's
  directory migrations created `agenor_agents`, `agenor_agent_capabilities` and
  `agenor_directory_schema_history` in `public`. Boot's Flyway in the coordinator and in the
  alerter then refused to start:

  ```
  FlywayException: Found non-empty schema(s) "public" but no schema history table.
  Use baseline() or set baselineOnMigrate to true to initialize the schema history table.
  ```

  Both processes failed in a loop under `restart: unless-stopped` and never recovered, because
  Agenor's tables stay. The first complete round of the entry diary had worked only because the
  coordinator happened to migrate before any probe started.
- **Why the pages are wrong for this configuration:** "run after Boot's Flyway" holds inside one
  process. With several processes on one database, which is the distributed configuration,
  another process's Agenor migrations can run before this process's Boot Flyway, and Boot's
  defaults do not accept that. The fix the application needs is on `adapters/jdbc-directory`, in
  the tip for "a Flyway you configure by hand", which the same page says Spring Boot users can
  skip.
- **What this example does:** `spring.flyway.baseline-on-migrate: true` and
  `spring.flyway.baseline-version: 0` in the coordinator and the alerter, marked `@friction F2`.
  The baseline is below V1, as the page warns, so V1 is not skipped. Checked on three start
  orders, each with two probes and the whole application answering afterwards: the database left
  by the failure above (baseline 0, then V1 and V2); an empty database with the probes first and
  then the coordinator and the alerter together (one baseline, the other process finds the schema
  up to date); an empty database with the coordinator and the alerter first (V1 and V2, no
  baseline).
- **In memory (CRITERION §5, point 7):** the case does not exist; the in-memory directory has no
  schema.
- **Missing capability, phrased as CRITERION §5 point 3 asks:** an application whose processes
  share the directory's database can run its own migrations with the settings the page gives,
  whichever process starts first.

## O1. Outcomes published while the alerter is not subscribed never reach it

- **Kind:** observation of documented behaviour; not a defect. To be measured under regime 3.
- **Version:** 0.39.0.
- **What happened:** on the first clean start (2026-10-29), the coordinator received 29 replies
  in the first minute and the alerter applied 24 outcomes. The 5 missing ones were published
  between 13:58:34.015 and 13:58:34.063 UTC; the alerter subscribed to `outcomes` at
  13:58:37.861 UTC.
- **Why it is documented:** `adapters/redis` says topic groups are created at `$`, so "a
  subscriber gets what is published from the moment it subscribes and nothing earlier", and that
  every `subscribeTopic` creates a new group with a unique subscription id.
- **Why it matters here:** a restarted alerter subscribes again under a new id, from `$`. Every
  outcome published while it was down is skipped, so a target that goes down during an alerter
  restart can miss its DOWN alert, or its UP. CRITERION §2 names a missing alert as the error a
  user would see. Whether this happens under regime 3, and how often, is for the ledger to show;
  the ledger will need a column for "answered, outcome published, never applied".

## C1. The ledger cannot hold Agenor's message id before the send

- **Kind:** criterion correction.
- **CRITERION §4** says every coordinator request "is written to a table of this example before
  it is sent, with its message id".
- **What is so:** the request protocol (`DialogueCapability.request(target, content, timeout)`,
  0.39.0) builds and sends the REQUEST itself and returns only the future of the reply. The
  message id is not known to the application before the send. It is known afterwards only if a
  reply arrives: the reply's `inReplyTo()` is the REQUEST's id.
- **What the ledger does instead:** it is keyed by the application's own request id, written
  before the send and carried in the payload (`CheckRequest.requestId`). When a reply arrives,
  its `inReplyTo()` is recorded too. The reconciliation script finds unanswered requests in Redis
  by the request id inside the stream entry, not by message id.
- Building the `Message` by hand to learn its id first would have meant leaving the protocol the
  pages recommend, for the sake of the measuring instrument; it was not done.

## C2. The four states of the ledger overlap, and need a fifth

- **Kind:** criterion correction.
- **CRITERION §4** gives four states for every request: answered, timed out (with an error the
  application saw), dead-lettered, and lost, which is "none of the three".
- **What is so:** the first three are not exclusive. A request that is dead-lettered on the probe's
  side gets no reply, so the coordinator also sees it time out; a request can be answered and
  still leave a copy behind in a stream. And a request sent a moment before the script runs is
  none of the three without being lost.
- **What the script does:** `scripts/reconcile` (module `sentinel-reconcile`) assigns the first
  state that applies, in this order: answered, dead-lettered, timed out, in flight, lost.
  Dead-lettered comes before timed out, because otherwise no request could ever be counted as
  dead-lettered. **In flight** is a fifth state: no reply and no error yet, and younger than a grace
  period (30 s by default, twice the coordinator's request timeout). The timed-out state keeps the
  error class, since not every error the coordinator sees is a timeout.
- Next to the state, the script reports where the REQUEST physically is in Redis (pending,
  unread, delivered, in a `:dlq` stream, trimmed away, in a stream that no longer exists, or not
  found), which is Q2's second measure, and what became of the outcome of each INFORM reply,
  which is what O1 needs.
- **The fifth state must not stand in for a lost one.** A ledger with any request in flight is not
  a final result: the script says so and exits with status 3. Final ledgers are taken after
  `scripts/quiesce` has stopped the dispatching and the grace period has passed, so in flight is
  always zero in them. The coordinator is not stopped for this: its last requests would be left
  with neither a reply nor an error, and would be counted as lost.

## Q2. A probe killed without goodbye: its work times out, and nothing is lost

- **Kind:** outcome of a question (CRITERION §4, Q2). No defect.
- **Version:** 0.39.0, with `agenor-spring-boot-starter` in all three roles, so every start gets
  a new node id. Redis transport on Valkey 8.1.11; JDBC directory and JDBC presence on
  PostgreSQL, heartbeat every 15 s, staleness window left to its default.
- **Orchestrator:** podman 4.9.3 behind the Docker CLI, with docker-compose 1.29.2,
  `restart: unless-stopped`. Restart and kill behaviour belong to it.
- **Run:** `scripts/measure-q2` with its defaults, on commit `e0b9afc`, 2026-10-30 from 09:33:54
  to 09:46:16 UTC. Three probes; regime 3 at seed 3: five probes killed with SIGKILL, one every
  120 s; every target answering after 3 s, below the probe's 5 s check timeout; the
  coordinator's request timeout is 15 s. Each killed probe was replaced 5.4 to 6.6 s after its
  death, under a new node id **and a new agent id**. Data: `data/q2/20261030T093354Z-seed3/`.

### The ledger

230 requests, final (none in flight after `scripts/quiesce` and the 30 s grace period):

| State         | Requests |
|---------------|---------:|
| answered      |      132 |
| dead-lettered |        0 |
| timed out     |       98 |
| in flight     |        0 |
| **lost**      |    **0** |

Every one of the 98 timed out with `java.util.concurrent.TimeoutException`, and every one was
addressed to a probe that had been killed. Split as Q2 asks:

| Kill | Killed at (UTC) | In flight at death | Sent after death | Still chosen after death |
|-----:|-----------------|-------------------:|-----------------:|-------------------------:|
|    1 | 09:35:28.327    |                  1 |               35 |                  602.0 s |
|    2 | 09:37:28.324    |                  0 |               26 |                  472.0 s |
|    3 | 09:39:28.930    |                  1 |               19 |                  367.4 s |
|    4 | 09:41:28.694    |                  0 |               11 |                  241.6 s |
|    5 | 09:43:28.821    |                  0 |                5 |                  111.5 s |
|      | **total**       |              **2** |           **96** |                          |

### Where the unanswered requests are

- **The 2 in flight at death** are in the pending list of the dead node's own stream
  (`sentinel:node:<old node id>`), delivered once to the consumer of that node, which no longer
  exists. `adapters/redis` says entries idle past `pendingEntriesTimeoutMs` are reclaimed by the
  consumer loop reading that stream; for a node id that is never reused there is no such loop, so
  nobody reclaims them and they never reach the `:dlq`.
- **The 96 sent after death** are unread in the dead node's stream. The page says such a stream
  waits for its node and is never expired; under the starter the node never comes back.

### What decides it

Q2 counts as an Agenor defect a request the framework accepted that ends up lost. None did: the
request protocol (`DialogueCapability.request` with a timeout) turned every request to a dead probe
into an error the application saw. CRITERION §7 expected exactly this for requests with a reply.

Two parts of Q2 stay open:

- **The decisive case named in §7, a send that expects no reply, was not measured.** This
  application sends none. Without a timeout to surface them, the same 98 messages would have
  stayed in the dead streams with no outcome at all.
- Whether `adapters/redis` describes this "only halfway", as §7 expected, is not settled by
  numbers. The page covers a node that never comes back, and does not say that a node id
  generated on every start makes this the normal case.

### What the run shows beyond Q2

- **A dead probe is never dropped.** Each killed probe was still being chosen when the dispatching
  stopped, up to 602 s after its death. At the end the directory held 10 rows, all `RUNNING`:
  the coordinator, the alerter, the three live probes and the five dead ones. Their `last_seen`
  stopped at the last heartbeat before the kill, so presence did record the deaths. The
  coordinator chooses round-robin over `findByCapability("probe")` with no filter of its own, and
  `findByCapability` does not filter by liveness, as the pages say. 98 of the 230 requests (43 %)
  went to dead probes. This is the baseline for Q3, measured with no application filter; Q3
  itself is not decided here.
- **The agent id changes too.** Every replacement registered under a new agent id as well as a
  new node id, so each restart adds a directory row. That matters for Q4 and Q5.
- **O1 again, at start-up only.** 4 outcomes were published and never applied, all between
  09:34:24.349 and 09:34:24.398 UTC; the alerter subscribed to `outcomes` at 09:34:24.915. The
  alerter was not restarted in this run, so this is the start-up case already recorded in O1.
