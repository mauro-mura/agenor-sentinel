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

## F3. An orderly stop waits for a handler in flight, then drops its reply

- **Kind:** documentation defect by the letter of CRITERION; a behavioural defect in substance.
  Both readings are below. Which one prevails is not decided here.
- **Version:** 0.39.0, with `agenor-spring-boot-starter` on Spring Boot 4.0.9, Java 21, Redis
  transport, `DialogueCapability` used as the pages show (constructed in a `BaseAgent`, no manual
  wiring).
- **What happens:** a probe receives a REQUEST, and while its handler is running (a check that
  takes 3 s) the process gets SIGTERM, the orderly stop every orchestrator sends. The log of one
  reproduction (`data/f3/probe-stop.log`, 2026-10-30, the stack of the regime 1 run on commit
  `ff6780b`):

  ```
  14:43:36.463 AgenorAutoConfiguration : Stopping AgenorRuntime...
  14:43:36.465 LoggingLifecycleListener : Agent lifecycle: b34e025d-... RUNNING -> STOPPING
  14:43:38.385 ERROR DialogueHandlerRegistry : @DialogueHandler onCheck failed on REQUEST from
               coordinator: java.lang.IllegalStateException: DialogueCapability not initialized
               for agent b34e025d-... — call initialize() from your agent's start(), or implement
               LifecycleHooks (BaseAgent already does) for automatic wiring
      at dev.agenor.runtime.dialogue.DialogueCapability.inform(DialogueCapability.java:511)
      at dev.agenor.sentinel.probe.agents.ProbeAgent.onCheck(ProbeAgent.java:49)
  14:43:38.392 WARN ConsumerLoop : Handler failed for entry 1793371415377-0 on
               'sentinel:node:4ffd788b-...' (attempt 1/3): java.lang.IllegalStateException: ...
  14:43:38.395 LoggingLifecycleListener : Agent lifecycle: b34e025d-... STOPPING -> STOPPED
  ```

  The stop waits for the handler: the agent reaches `STOPPED` 1.9 s after `STOPPING`, right after
  the handler ends, well within the agent's 10 s stop timeout. But by then the dialogue capability
  has already been shut down, so the handler's `inform` throws. The check was done and its result
  is discarded. The entry is not acknowledged, as `adapters/redis` says for a handler that throws,
  and it stays pending in the stream of a node id that will never be used again (Q2). The
  coordinator sees a timeout 15 s after the send (`data/f3/ledger-row.csv`). The error message
  blames the application's wiring, which is not the cause.
- **How often, in the runs of Q4:** every request in flight at an orderly stop ended this way: 10
  requests at 9 of 15 stops in regime 2, 2 requests at 2 of 6 stops in regime 1. Each was
  delivered once and is still pending in the stopped node's stream, and none is in a `:dlq`. Those
  runs kept no probe log, so the cause is shown by the reproduction above, and the runs agree with
  it.
- **What the pages say:** nothing about work in flight during an orderly stop. `agent-development`
  says `onStop()` runs before behaviours are stopped and the agent is unregistered, and that
  `DialogueCapability` registers itself on the agent's start and stop hooks. `dialog-protocol`
  says `shutdown()` withdraws the capability's inbound registration, and that on a *restart*
  in-flight conversations are lost and peers wait out their own timeouts. `adapters/redis` says
  a restarted JVM picks up its own pending entries; under the starter no JVM restarts with the
  same node id.
- **Reading 1, by the letter: a documentation defect.** CRITERION §5 point 5 lists four
  behavioural defects: a message lost without an outcome, growth without bound, a system that
  stops silently, a page describing another configuration. None applies: the coordinator sees
  every one of these requests fail, Q4 counts no lost request, and the ERROR is logged. What is
  missing is a page saying what an orderly stop does to work in flight.
- **Reading 2, in substance: a behavioural defect.** The framework contradicts itself within one
  stop: it waits for the handler to finish, then refuses the reply the handler was written to
  send. Waiting is pointless if the result cannot leave, and the reply could have been sent: the
  Redis client was still open (it closes 40 ms after `STOPPED`). The consequence is that a rolling
  upgrade, the orderly operation an orchestrator does on every release, fails every request in
  flight, and an application cannot avoid it by anything the pages show. That is the shape of a
  defect, even if CRITERION §5 does not name it.
- **In memory (CRITERION §5, point 7):** not tried. The same stop order is likely to apply, since
  the capability is shut down by the agent's own stop hook whatever the transport, but nothing
  here shows it.
- **Missing capability, phrased as CRITERION §5 point 3 asks:** a node stopped in an orderly way
  either finishes the work it has accepted, outcome included, or gives it back so that someone
  else can.

## F4. A broker that comes back at a new address is never reached again, and health stays UP

- **Kind:** behavioural defect for the part that is silent; the cause of the part that does not
  reconnect is not established. Details below.
- **Version:** 0.39.0, with `agenor-spring-boot-starter` on Spring Boot 4.0.9, Java 21, Redis
  transport on Valkey 8.1.11. The images carry `lettuce-core` 7.5.1 and `netty-resolver-dns`
  4.2.19 (in `/app/lib`, as the starter brings them).
- **Orchestrator:** podman 4.9.3 behind the Docker CLI, docker-compose 1.29.2. Podman gives a
  container a new IP address on every start; that is what this finding depends on.
- **Run:** `scripts/measure 4` with its defaults, on commit `23d14de`, 2026-10-30 from 14:58:00 to
  15:05:46 UTC: three probes, Valkey stopped at 14:59:37.478, down 10 s, answering PING again at
  14:59:55.242 with 0 keys loaded. Data: `data/regime4/20261030T145800Z-seed3/`.
- **What happens:** Valkey came back at 10.89.3.71; before the stop it was at 10.89.3.63. Every
  process kept reconnecting to the old address until the end of the run. In the log of the run,
  all 211 `Cannot reconnect` lines of Lettuce's `ConnectionWatchdog`, from the five processes,
  name 10.89.3.63:

  ```
  Reconnecting, last destination was valkey.dns.podman/10.89.3.63:6379
  Cannot reconnect to [valkey/<unresolved>:6379]: Host is unreachable: valkey.dns.podman/10.89.3.63:6379
  ```

  The name is resolved again on each attempt (`<unresolved>`), but to the old address. Podman's
  DNS is not the cause: checked by hand after the run, `getent hosts valkey` inside a probe gave
  the new address within a second of a second restart, while the processes, 8 minutes after the
  first, were still trying 10.89.3.63. The stale address is held inside each JVM. Whether by
  Netty's DNS resolver cache, by Lettuce, or by how Agenor builds its Lettuce client is not
  established: finding out means reading source, which this example does not do without asking.
- **What the application sees:** nothing that says the system stopped.

  | Requests sent | 14:58:27–14:59:27 | 14:59:27–14:59:57, the stop | 14:59:57–15:04:57 |
  |---|---:|---:|---:|
  | Answered | 22 | 5 | 0 |
  | Timed out | 0 | 4 | 104 |

  (`timeline.txt`, 30 s slices by the time each request was sent). Every
  health sample after the stop, 75 of them, 15 per process, reads `UP`, with the Agenor component
  `UP` and `agents.running: 1` (`health.csv`). The logs hold no ERROR line: Lettuce's reconnect
  failures, the consumer loops' "Transient error ... Command timed out after 1 minute(s)", and the
  coordinator's own timeouts are all WARN (`log-counts.txt`: 0 ERROR, 372 WARN).
- **What the pages say:** `adapters/redis` says that on reconnect (Lettuce auto-reconnect) the
  consumer loop resumes from the last acknowledged id and that no message is lost. It does not
  say what happens when the broker's address changes. `spring-boot-starter` says the health
  endpoint "reflects the true state of the runtime"; it reports agents only, and nothing about
  the broker.
- **Why the silent part is a defect:** CRITERION §5 point 5 names "a system that stops working
  silently", and §4 Q7 adds that it is a defect even if no page promises otherwise. For five
  minutes no request was answered, and the only health check an orchestrator could read said UP.
  Whatever the cause of the stale address, a node that cannot reach its transport reports itself
  healthy.
- **Why the rest is not classified:** a client that never follows a changed address is a defect
  of someone, but not necessarily of Agenor, and it may not happen where a restarted broker keeps
  its address (Docker often reassigns the same one; a Kubernetes pod that is recreated never
  does). It is recorded as observed.
- **In memory (CRITERION §5, point 7):** the case does not exist; there is no broker.
- **What this example does:** Q7 asks what happens when the broker comes back *empty*, which this
  run could not show, since no process reached it. `compose.yml` now gives Valkey a fixed address,
  so that a restart brings it back where the processes look for it, and regime 4 is measured
  again. That isolates Q7's variable; it is not a fix, and it is marked `@friction F4`.
- **Missing capability, phrased as CRITERION §5 point 3 asks:** a node that cannot reach its
  transport says so where an orchestrator looks; and a node reaches a broker that has come back,
  wherever it has come back.

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

## Q3. The coordinator can tell who is alive, in 12 lines

- **Kind:** outcome of a question (CRITERION §4, Q3). Written first for regime 3 alone; regimes
  1 and 2 are at the end of this section.
- **Version and configuration:** as in Q2: 0.39.0, the starter in all three roles, JDBC directory
  and JDBC presence, heartbeat every 15 s, staleness window left to its default, which
  `adapters/jdbc-directory` derives as three times the interval: 45 s. Same orchestrator.
- **Run:** `scripts/measure-q2` with the same defaults and seed as Q2 (seed 3, five probes killed
  with SIGKILL, one every 120 s), on commit `627e76b`, 2026-10-30 from 11:33:52 to 11:46:16 UTC.
  Data: `data/q2/20261030T113352Z-seed3/`. The run without the filter is Q2's.

### What the application needs

`findByCapability` does not filter by liveness, as `directory` says. A filter on the
descriptor's own status does not help either: a probe killed without goodbye keeps its row with
status `RUNNING` (Q2), so `AgentDescriptor.status()` and `AgentQuery.status(RUNNING)` both keep
it. What tells it apart is `getStatus`, which `adapters/jdbc-directory` describes as reading
`status` and `last_seen` and answering `UNKNOWN` past the staleness window.

The coordinator now keeps only the candidates whose `getStatus` reads `RUNNING`, one call per
candidate, in parallel (`CoordinatorAgent.live`, `@friction Q3`). `scripts/tags` counts
**12 lines**; the call that applies it in `round()` changes one existing line and is not counted.
No cache, no window of its own: the backend's window is used as it is.

### The numbers, same seed, without and with the filter

| | Without filter (`e0b9afc`) | With filter (`627e76b`) |
|---|---:|---:|
| Requests | 230 | 230 |
| Answered | 132 | 211 |
| Timed out | 98 | 19 |
| Dead-lettered, lost | 0, 0 | 0, 0 |
| Sent to a probe already dead | 96 | 18 |
| In flight at a death | 2 | 1 |
| Dead probe still chosen, after its death | 111.5 to 602.0 s (until the dispatching stopped) | 24.1 to 38.3 s |

Per kill, with the filter (from `scripts/q2-deaths`, which takes each dead node's last heartbeat
from the `probe_node_last_seen` column of the ledger):

| Kill | Last heartbeat → kill | Kill → last request sent | Last heartbeat → last request sent | Sent after death |
|-----:|----------------------:|-------------------------:|-----------------------------------:|-----------------:|
|    1 |                 5.5 s |                   38.3 s |                             43.8 s |                4 |
|    2 |                 4.8 s |                   32.3 s |                             37.1 s |                4 |
|    3 |                10.0 s |                   32.0 s |                             42.0 s |                3 |
|    4 |                 9.3 s |                   24.1 s |                             33.5 s |                3 |
|    5 |                 4.9 s |                   32.6 s |                             37.5 s |                4 |

Every last request to a dead probe was sent within 45 s of its last heartbeat: the probe leaves
the candidates when `getStatus` turns `UNKNOWN`, as the page says. The 18 requests sent after a
death fall inside that window, and no filter built on presence can avoid them; with the request
protocol they end as timeouts the application sees, as in Q2.

### Against CRITERION §7

- **"A dead probe stays a candidate for at least the staleness window": refuted as written.**
  Measured from the death it stayed a candidate for 24.1 to 38.3 s, less than the 45 s window.
  The window runs from the last heartbeat, which came 4.8 to 10.0 s before the kill, not from the
  death; and the last request is sent before the window closes, not when it closes. Measured from
  the last heartbeat, the window is the bound, and it held.
- **"A filter of 15–30 lines": refuted.** It is 12.
- **"Generic": confirmed.** The method names no type of this domain: it takes the descriptors
  found for a capability and keeps those whose presence reads `RUNNING`. Anyone choosing an agent
  by capability among replaceable processes would write the same lines.

### What it decides, and where the criterion pulls two ways

Q3 says that generic lines make this the second case for liveness-filtered discovery, and that
the decision not to add it is reopened. CRITERION §5, point 2 says something is proposed to
Agenor only if Agenor cannot express it, and "inconvenient is not enough; inexpressible is".
Agenor expresses it, with the public API, in 12 lines. The two rules give different answers on
the same evidence. Which one prevails is not decided here, and CRITERION.md is not changed.

What the evidence does add to either reading: the obvious filter, on the descriptor's status,
is wrong in exactly the case it is written for, and nothing in `directory` or
`adapters/jdbc-directory` says so. The page shows `AgentQuery.status(RUNNING)` without saying
that the stored status of an agent that died without goodbye stays `RUNNING`. That is a gap in
the pages whichever rule prevails.

### Regimes 1 and 2: an orderly stop leaves the candidates at once

Measured with the runs of Q4 (same commit, same filter): 15 probes replaced with SIGTERM in
regime 2, 6 probes removed by scaling down in regime 1. A probe stopped with SIGTERM deletes its
own row in `agenor_agents` on the way out, so `findByCapability` stops returning it at once. No
request was sent to a probe after its stop, in either run (`q2.txt` of each run: "still chosen for
0.0 s" on every stop). The filter has nothing to do in these regimes, and the application would
need no line without it. What these stops do lose is in F3 and Q4, not here.

So the 12 lines of the filter are needed for one case only: a process that dies without goodbye.

### Not measured

The cost of the filter: one `getStatus`, a read of `agenor_agents`, per candidate per round. It is
not among Q3's deciding criteria.

## Q4. A rolling upgrade loses nothing, and fails the work in flight

- **Kind:** outcome of a question (CRITERION §4, Q4). No defect by Q4's rule; F3 is what it found.
- **Version and configuration:** as in Q3: 0.39.0, the starter in all three roles, JDBC directory
  and presence, heartbeat every 15 s, the coordinator's liveness filter on. Orderly stops are
  `docker stop -t 30` (SIGTERM to the init process, which forwards it to the JVM), so the runtime
  falls back to SIGKILL only after 30 s.
- **Runs:** `scripts/measure 2` and `scripts/measure 1` with their defaults, seed 3, on commit
  `ff6780b`, 2026-10-30:
  - regime 2, from 14:18:47 to 14:31:57 UTC: three probes, five rolling upgrades two minutes
    apart, each replacing every probe one at a time: 15 stops. Data:
    `data/regime2/20261030T141847Z-seed3/`.
  - regime 1, from 14:31:57 to 14:42:40 UTC: two probes scaled up to five and back down, twice:
    6 stops. Data: `data/regime1/20261030T143157Z-seed3/`.

### The ledger

| | Regime 2 | Regime 1 |
|---|---:|---:|
| Requests | 241 | 195 |
| Answered | 231 | 193 |
| Timed out | 10 | 2 |
| Dead-lettered | 0 | 0 |
| In flight | 0 | 0 |
| **Lost** | **0** | **0** |
| Sent to a probe after its stop | 0 | 0 |

Every stop ended with exit code 143, the JVM leaving on SIGTERM; no stop needed the SIGKILL
fallback. In regime 2, the six stops of a probe with no check in flight took 0.6 to 0.7 s from the
signal, and the nine with a check in flight took 1.2 to 3.3 s: the stop waited for the handler,
as in F3 (`faults.csv`, `stop_seconds`). In regime 1 the containers leaving were stopped in
parallel, and every stop took 0.6 to 1.3 s.

Every one of the 12 timeouts is a request that was in flight at a stop, pending in the stopped
node's stream, delivered once: F3.

### What decides it

Q4 says that with an orderly stop the ledger must have no lost entry, and that a single one is a
defect. There is none. CRITERION §7 expected "nothing lost", and that holds.

What the rule does not see: the requests in flight at a stop did not survive it either. They
failed in a way the application sees, which is why Q4 counts them as timed out and not lost. The
rule was written to catch a message vanishing; it does not ask whether an orderly stop costs the
work in flight, and it does: 12 of 12. That is F3.

### What an orderly stop leaves behind

From `census.csv` of each run, taken after the warm-up, before the first stop, and again after
the final ledger:

| | Regime 2 before | Regime 2 after | Regime 1 before | Regime 1 after |
|---|---:|---:|---:|---:|
| Rows in `agenor_agents` | 5 | 5 | 4 | 4 |
| Node streams | 5 | 20 | 4 | 10 |
| Topic streams | 1 | 1 | 1 | 1 |
| `:dlq` streams | 0 | 0 | 0 | 0 |
| Consumer groups | 6 | 21 | 5 | 11 |
| Pending entries | 0 | 10 | 2 | 2 |
| Valkey `used_memory` (bytes) | 1,353,456 | 1,939,416 | 1,286,496 | 1,774,704 |

- **Directory rows: none left behind.** A probe stopped with SIGTERM deletes its own row, so the
  row count is back to the live processes after every stop.
- **Streams and groups: one of each per stop, left behind.** 15 stops added 15 node streams and
  15 consumer groups in regime 2; 6 stops added 6 of each in regime 1. Nothing removes them, and
  no page describes their removal for a node id that is never reused. Whether that growth has a
  bound is Q5's.
- **Pending entries:** after the run, these are F3's requests (10 and 2). The 2 counted in regime
  1 *before* the first stop are checks that were running at that moment: the census was taken
  while the coordinator was dispatching.

### Against CRITERION §7

- **"Nothing lost": confirmed.**
- **"Every stopped probe leaves its stream and its groups behind": confirmed.** One stream and one
  group per stop.
- **"And one directory row per agent if the agent's name changes on each start": refuted.** The
  agent's id does change on every start (Q2), and an orderly stop still leaves no row: the probe
  deletes it on the way out.

