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
- **How often, in the 24-hour run (Q5):** 158 orderly stops, 70 requests timed out, none lost. This
  run kept the probes' logs, and they show a second form. In 58 of the 70 log entries the handler ran
  and its reply was refused, as above. In the other 12 the handler never ran: the request was read
  after the agent had left the node's registry, and the log says `RedisMessageDispatcher: No local
  handler for receiverId=...`, then `ConsumerLoop: Handler failed for entry ... (attempt 1/3):
  dev.agenor.core.exceptions.AgentNotFoundException`. The consumer loop was still reading a mailbox
  whose only agent had gone. The two counts add up to the 70 requests; the log lines were not matched
  to the requests one by one. All 70 requests are pending in a stopped node's stream.
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

## F5. A broker that comes back empty stops every consumer loop, for good, and health stays UP

- **Kind:** behavioural defect: "a system that stops working silently" (CRITERION §5 point 5;
  §4 Q7 says it is one even if no page promises otherwise).
- **Version:** 0.39.0, with `agenor-spring-boot-starter` on Spring Boot 4.0.9, Java 21, Redis
  transport on Valkey 8.1.11 with no persistence (`--save "" --appendonly no`), at a fixed address
  (F4). Same orchestrator.
- **Run:** `scripts/measure 4` with its defaults, on commit `f461f48`, 2026-10-30 from 15:21:07 to
  15:28:53 UTC. Valkey stopped at 15:22:42.922, down 10 s, answering PING at 15:23:00.675 with 0
  keys loaded. Data: `data/regime4/20261030T152107Z-seed3/`.
- **What happens:** every process reconnects, between 15:23:26 and 15:23:37 (Lettuce's
  `Reconnected to valkey/<unresolved>:6379`). From then on every consumer loop, the node stream of
  each of the five processes and the alerter's subscription to `outcomes`, fails once a second
  until the end of the run:

  ```
  WARN ConsumerLoop : Transient error in consumer loop for 'sentinel:node:24bf7103-...':
       NOGROUP No such key 'sentinel:node:24bf7103-...' or consumer group 'sentinel:cg:node'
       in XREADGROUP with GROUP option
  ```

  The first such line is at 15:23:35.273, the last at 15:28:52.801: 1915 in all
  (`log-counts.txt`). No loop recreates its group; no line of the logs says one did. The node
  groups are created once, when the factory is built (`adapters/redis`: "when
  `RedisMessagingFactory.build()` constructs the transport"), and the topic group once, when the
  subscription is made, so nothing in a running process creates them again.

  Sending still works, which keeps the failure out of sight: the coordinator's `XADD` to a probe's
  stream recreates the stream, with no group, and the send succeeds. At the end Valkey held 3 node
  streams with 108 entries and no consumer group at all (`census.csv`); the reconciliation finds
  every unanswered request in a stream with no group (`NO_GROUP`).
- **What the application sees:**

  | Requests sent | 15:21:33–15:22:33 | 15:22:33–15:23:03, the stop | 15:23:03–15:28:03 |
  |---|---:|---:|---:|
  | Answered | 22 | 5 | 0 |
  | Timed out | 0 | 6 | 102 |

  (`timeline.txt`). No outcome reached the alerter after the stop, so no alert could be raised,
  for a target going down or coming back. Every one of the 75 health samples taken after the stop
  reads `UP`, with the Agenor component `UP` and `agents.running: 1` (`health.csv`). The logs hold
  no ERROR line (`log-counts.txt`): the loops call the failure "transient", at WARN, forever.
- **What the pages say:** `adapters/redis` promises durability "with AOF/RDB", so losing the data
  of a broker with no persistence is not a defect by itself. It says that on reconnect the loop
  "resumes reading from the last acknowledged ID" and that "no messages are lost; pending entries
  are claimed on the next iteration". It does not say what a loop does when its group is gone, and
  `spring-boot-starter` does not say when the health indicator reports anything other than UP.
- **Against the job dispatcher's case:** that example stopped Valkey for 10 s with its data
  intact, and the system resumed. The difference here is only that the data, groups included, did
  not come back.
- **In memory (CRITERION §5, point 7):** the case does not exist; there is no broker to lose.
- **What this example does:** nothing. Every process would have to be restarted for the groups to
  be created again; an orchestrator does that only for a process whose health says so, and here it
  says UP. The example waits for the release that fixes this (CRITERION §5, point 8).
- **Missing capability, phrased as CRITERION §5 point 3 asks:** a node whose broker has lost its
  consumer groups either reads again or says, where an orchestrator looks, that it cannot.

## F6. Processes that start together on an empty database can fail Agenor's own migration

- **Kind:** behavioural defect. It is loud and an orchestrator's restart cures it, but the page
  calls the migration idempotent and gives several JVMs one database, and says nothing of a start
  that fails because another one is under way.
- **Version:** 0.39.0, with `agenor-spring-boot-starter` on Spring Boot 4.0.9 (Flyway 11.14.1), JDBC
  directory on PostgreSQL 16.15.
- **What the pages say:** `adapters/jdbc-directory`: the migration "runs on every
  JdbcAgentDirectory.create() call", "Calling it again is idempotent", and "Two JVMs can share the
  same database to form a logical cluster". `spring-boot-starter`: "Flyway schema migration runs
  automatically on startup". Neither page mentions processes starting at the same moment.
- **What happened:** at the start of the dress rehearsal below, three probes reached the migration
  within 60 ms on an empty database. `probe_1` created the history table at 10:07:48.765 UTC.
  `probe_2`, at 10:07:48.803, tried to baseline, found that table still empty, and the process
  ended:

  ```
  FlywayException: Unable to baseline schema history table
  "public"."agenor_directory_schema_history" as it already exists, and is empty.
  ```

  `probe_1` began to migrate at 10:07:48.849. `restart: unless-stopped` started `probe_2` again at
  10:07:50.210, and it was running by 10:07:55. Evidence: `data/long-run/20261031T100720Z-seed3/logs/001-before-fault.txt.gz`.
- **How often:** once in the 8 runs that kept their logs: this one, four in `data/q6` and
  `data/regime4`, and three short trials whose data was not kept. The other four runs in `data/`
  kept no logs and cannot say. It is a race, so this is not a rate.
- **What it costs:** one restart here. Without an orchestrator, the process that loses the race
  stays down. Neither page gives a setting for it; starting one process first would avoid it.
- **In memory (CRITERION §5, point 7):** the case does not exist; the in-memory directory has no
  schema.
- **Missing capability, phrased as CRITERION §5 point 3 asks:** the processes of an application
  can start together on an empty database, and each comes up at its first attempt.

## F7. No page says where a reply goes when its requester has died

- **Kind:** documentation defect. The loss of the request itself is documented; what becomes of the
  reply is not.
- **Version:** 0.39.0, Redis transport, the starter in all three roles.
- **What the pages say:** `dialog-protocol`: "Conversation state is held in memory, in the agent's
  own process", "every in-flight conversation it was tracking is gone", and a future "cannot be
  recovered by any means". So a requester's death losing its open requests is documented. The only
  reply the page follows is "a reply that reaches an agent restarted since it opened the
  conversation", which "opens a new one in INITIATED". `adapters/redis`: "A node that never comes
  back keeps a stream. Nothing expires it". No page puts the two together.
- **What happened:** the coordinator is an agent with a fixed id (`coordinator`), and the starter
  gives each start a new node id. It was killed at 11:53:57 in the dress rehearsal below, with 3
  requests open. The probes answered all 3, and all 3 replies are unread in the stream of the dead
  node, not with its replacement. The ledger has them as lost, cause `SENDER_STOPPED`. The case the
  page describes, a reply reaching the restarted agent, was not seen.
- **Against CRITERION §4:** a coordinator that dies leaves requests with no reply and no error, so
  the ledger's definition makes them lost, and it stays that way: `scripts/reconcile` adds a cause
  to the state and never changes the state. Q2's rule that a lost message is a defect unless a page
  allows it was written for a dead probe. For a dead requester, the page allows it.
- **Missing page:** what happens to a reply whose requester died before it arrived, for a requester
  with a fixed id and a new node id.

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
- **Measured in the 24-hour run (Q5):** 20 outcomes published and never applied, at 8 of the 11
  alerter kills, 2 to 4 at each. All 20 were successful checks, so no alert changed. Every target
  stayed up in that run, so it cannot say what a missed failure would have cost.

## O2. False alerts during orderly faults, with every target up

- **Kind:** observation. The cause is not established, and nothing here attributes it to Agenor.
- **Version:** 0.39.0, podman 4.9.3 with docker-compose 1.29.2, in the 24-hour run (Q5).
- **What happened:** no target was switched off during the run, and there were still 3 DOWN alerts,
  each followed by its UP 12 to 17 s later (`alert.csv`). CRITERION §2 names a duplicate or missing
  alert as the error a user would see; a false alert is one too, though it does not name it.

  | Raised (UTC) | Target | Alert | Webhook | During |
  |---|---|---|---:|---|
  | 2026-11-02 07:19:01 | bravo | DOWN | 204 | fault #64, scale down |
  | 2026-11-02 07:19:13 | bravo | UP | 204 | |
  | 2026-11-02 11:48:45 | alpha | DOWN | **0** | fault #82, rolling upgrade |
  | 2026-11-02 11:49:02 | alpha | UP | 204 | |
  | 2026-11-02 12:34:38 | delta | DOWN | 204 | fault #85, rolling upgrade |
  | 2026-11-02 12:34:52 | delta | UP | 204 | |

- **Where they come from:** 44 checks failed out of 29,061 (`failed-outcomes.csv`), every one because
  the probe's own HTTP client could not connect to the `fixtures` container: 22 `ConnectException`,
  21 `HttpConnectTimeoutException`, 1 `HttpTimeoutException`. The alerter applied all 44, and did what
  it was told: a threshold of failures in a row means DOWN. The 0 on the alpha alert is the alerter
  failing to reach the same container with its webhook, so this is not only the probes.
- **When:** all 44 fall in 10 of the 64 faults that start or stop containers in order (7 of 32 rolling
  upgrades, 2 of 16 scale downs, 1 of 16 scale ups), 10 to 85 s after the fault started. None falls in
  the 32 kills, where the orchestrator replaces one container. `fixtures` never restarted. Only 10 of
  the 44 came from a probe within 30 s of its first request, so it is not only new containers.
- **What it suggests, and does not show:** connections between containers failing while containers
  come and go points at the network of the orchestrator. That is a suspicion. Agenor carries no part
  of this path, but this run cannot separate podman from anything else: it needs the same run on real
  Docker.

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

## Q7. The broker comes back empty: the system stops, and does not notice

- **Kind:** outcome of a question (CRITERION §4, Q7). It found F5.
- **Run:** the second run of regime 4, with Valkey at a fixed address (F5 gives the data). The
  first run of regime 4 could not answer Q7: no process reached the restarted broker at all (F4).

### What decides it

Q7 asks two things.

- **Do the consumer loops recreate their groups, or fail with `NOGROUP` forever?** They fail
  with `NOGROUP` forever: once a second per loop, for the 5 minutes and 17 seconds from the first
  failure to the end of the run, with no group ever created again.
- **If they fail, does the application see it: an error, a crashed state, a health check?** No.
  The failures are logged at WARN as transient; no ERROR is logged; the agents stay `RUNNING`; the
  health endpoint of every process answers UP throughout. The application's only sign is its own
  requests timing out, all of them, which an application that sends nothing it waits for would not
  have either.

So the system stops working silently, which Q7 calls a defect even if no page promises otherwise:
F5.

### Against CRITERION §7

- **Q7: "unknown."** The outcome is the worse of the two the question names.

### What it adds to F4

Both regime 4 runs ended the same way for the application: no request answered after the stop,
health UP. In the first the broker could not be reached; in the second it was reached and could
not be read. A node of this stack has no way, today, to tell an orchestrator that its transport is
not working, whatever the reason.

## Q6. A poison message: one death, then it lies pending where nobody reads; the DLQ only sees exceptions

- **Kind:** outcome of a question (CRITERION §4, Q6). No new finding.
- **Version and configuration:** as in Q4: 0.39.0, the starter in all three roles, JDBC directory
  and presence, Valkey at a fixed address (F4). Same orchestrator.
- **Runs:** `scripts/measure-q6`, on commit `4852b9f`, 2026-10-30, three probes, one poison request
  each (a target with an interval of a day), 300 s of observation:
  - `exit`, from 15:59:59 to 16:07:40 UTC: the probe that handles the request calls `System.exit`,
    and so would every later delivery of it. Data: `data/q6/20261030T155959Z-exit/`.
  - `throw`, from 16:07:40 to 16:15:12 UTC: the handler throws instead, the case `adapters/redis`
    documents. Data: `data/q6/20261030T160740Z-throw/`.

### What happened to the poison message

| | `System.exit` | Exception in the handler |
|---|---|---|
| Deliveries | 1 | 3 |
| Probe deaths (container restarts, `restarts.csv`) | 1 | 0 |
| Where it ended | pending in the dead node's stream, delivered once | the node's `:dlq` stream, `dlq_attempts` 3 |
| State in the ledger | timed out | dead-lettered |
| Other requests | 129 answered, none lost | 131 answered, none lost |

**With `System.exit`** (`logs.txt.gz`, probe_1): `System.exit` runs the JVM's shutdown hooks, so
the probe stops in an orderly way while the handler is stuck inside `System.exit`. The agent goes
to `STOPPING` at 16:01:45.827, 19 ms after the request was sent; the mailbox gives up on the
handler five seconds later ("Mailbox for agent '...' stopped with handlers still running after
PT5S", WARN); the agent's directory row is removed, as on any orderly stop (Q4); the process
exits, and its container is running again at 16:01:55. The replacement has a new node id, so the
message is never delivered again: it stays pending in the old node's stream, under a consumer that
no longer exists, delivered once (`poison.txt`). The coordinator sees its request time out.

**With an exception** (`logs.txt.gz`, probe_2): the handler fails at 16:09:18.290, again at
16:10:06.457 and at 16:10:38.395, each time logged at ERROR, each time "attempt n/3" at WARN. At
16:11:10.332 the consumer loop logs "exceeded 3 delivery attempts; moving to DLQ" (ERROR), and the
message is in `sentinel:node:<node id>:dlq`, with the reason, the payload and `dlq_attempts` 3
(`dlq.txt`): 112 s after the send. This is what `adapters/redis` describes.

### What decides it

- **Where does it end?** Neither in the DLQ nor in an endless cycle of deaths: pending in the dead
  node's stream, where nobody will read it.
- **After how many deaths?** One. The cycle stops because the restarted probe has a new node id
  and never sees its predecessor's stream. That is an accident of the starter's identity, not a
  mechanism: `adapters/redis` says that "a restarted JVM picks up its own pending entries
  automatically" and that attempts are counted in memory, so "a restart resets the count". With a
  node id that survives restarts, those two sentences together describe a message redelivered to
  every new process and never dead-lettered. That configuration was not run here; it is what the
  pages imply, not a measure.
- **With what signal for an operator?** One container restart, and one WARN line about a mailbox
  stopped with handlers running. No ERROR; the health of every process read UP in all 75 samples
  of the run (`health.csv`); the DLQ is empty, so `deadLetterQueue().recent(n)` and the console
  show nothing. The application sees one request time out. With an exception, by contrast, the
  operator gets seven ERROR lines (two per attempt, one for the move to the DLQ,
  `log-counts.txt`) and a DLQ entry that names the cause.

No new defect. The request had a reply to wait for, so the application saw it fail, as in Q2; a
send that expects no reply would have left nothing anywhere but a pending entry, which is Q2's
open case again.

### Against CRITERION §7

- **"The poison message does not reach the DLQ": confirmed.**
- **"It stays pending in the dead probe's stream, and nobody reclaims it": confirmed.**
- **"The cycle stops by accident, not by design": confirmed.** After one death, because of the new
  node id.
- **"And nobody sees it": confirmed for Agenor and for an operator, not for the application.**
  Nothing in Agenor's DLQ, its console or its health shows it, and the only log line is a WARN
  that does not name the message. The application, which waits for a reply, sees a timeout.

### Side observations

- **The DLQ is per node.** The dead-lettered message is in `sentinel:node:<node id>:dlq`, a
  stream named after the node that failed it. Under the starter that name is new on every start,
  so each process that ever dead-letters anything leaves a DLQ stream of its own. Whether that
  grows is Q5's.
- **O1 again, at start-up.** The `throw` run has 5 outcomes published and never applied, all
  between 16:08:04.351 and 16:08:04.399 UTC; the alerter subscribed at 16:08:04.746.

## The dress rehearsal of the long run: the scripts hold, and Q5's estimate is too low

- **Kind:** a rehearsal, not an outcome. Q5 stays open until the 24-hour run.
- **Version and configuration:** as in Q4: 0.39.0, the starter in all three roles, JDBC directory
  and presence, Valkey at a fixed address (F4). Same orchestrator.
- **Run:** `scripts/long-run --hours 2`, on commit `9a019be`, 2026-10-31 from 10:07:21 to 12:09:41
  UTC. Three probes at the start; 8 faults at seed 3, one every 15 minutes, as `schedule.csv` lists:
  3 of regime 1, 2 of regime 2, 3 of regime 3 (a probe, the alerter, the coordinator). Data:
  `data/long-run/20261031T100720Z-seed3/`.
- **The scripts:** every fault finished, with no `errors.log`. Before this run, three short trials
  found that `up --scale` had been refused since F4's fixed address, and that `scale` left stopped
  containers for the next rolling upgrade to start again. Both are fixed in `scripts/faults`.

### The ledger

| State | Requests | Cause |
|---|---:|---|
| answered | 2445 | |
| timed out | 4 | `PROBE_STOPPED`: the probe killed at 10:23:57 |
| timed out | 3 | `NONE`: orderly stops, with the request pending in the stopped node's stream (F3) |
| lost | 3 | `SENDER_STOPPED`: the coordinator killed at 11:53:57 (F7) |

No lost request lacks an explanation. A timed-out request has cause `NONE` here because an orderly
stop removes the directory row, and a node with no row is not counted as stopped.

### O1, measured once

The alerter was killed at 10:38:57 and subscribed again at 10:39:03.561 UTC. The 2 replies recorded
at 10:38:58 are `PUBLISHED_NOT_APPLIED`; the next one, at 10:39:04.110, was applied. So O1 happens
under regime 3, as its entry expected. One kill does not say how often. Four trial runs, whose data
was not kept, had one alerter kill each: one left 2 outcomes unapplied, from requests sent just after
the kill, and three left none. The 24-hour run has 11 kills to count.

### What grows

| | Start | After 2 h |
|---|---:|---:|
| Node streams | 5 | 21 |
| Consumer groups | 6 | 23 |
| Directory rows | 5 | 8 |
| Stream entries | 71 | 7348 |
| Valkey memory | 1.35 MB | 6.79 MB |

Node streams grew by exactly the 16 new nodes in `faults.csv`, and none went away. Consumer groups
grew by those 16 and by 1 for the alerter's restart. Three censuses give no slope, and this schedule
is not the 24-hour one.

### Against CRITERION §7

- **"Node streams grow by one per restart": confirmed so far.**
- **"Topic groups grow by one per alerter restart": confirmed once.**
- **"In 24 hours, at one failure every 15 minutes, that is about 96 orphaned keys": refuted as an
  estimate, pending the run.** It counts one restart per failure, and here a failure made 2 new nodes
  on average: a rolling upgrade or a scale up makes several. Applying each fault of the 24-hour
  schedule at seed 3 gives 189 new nodes, so 194 node streams. That is a projection, not a
  measure; the same calculation gives the 16 measured here.

### Limits of this run

- The health of the processes was sampled every 21 s on average: 15 s is the pause between samples.
- It ran on the same orchestrator as Q2-Q7, so it says nothing about real Docker.

## Q5. What grows: everything a restart leaves behind, and nothing removes it

- **Kind:** outcome of a question (CRITERION §4, Q5). It found F8.
- **Version and configuration:** as in Q4: 0.39.0 from Maven Central, the starter in all three roles,
  JDBC directory and presence, heartbeat every 15 s, the coordinator's liveness filter on, Valkey at
  a fixed address (F4). Podman 4.9.3 behind the Docker CLI, with docker-compose 1.29.2.
- **Run:** `scripts/long-run` with its defaults, on commit `e07d289`, from 2026-11-01 15:31:36 to
  2026-11-02 15:33:58 UTC. 96 faults at seed 3, one every 15 minutes, as `schedule.csv` lists: 16 scale
  ups, 16 scale downs, 32 rolling upgrades, and 32 kills (10 of a probe, 11 of the coordinator, 11 of
  the alerter). Every fault finished, and there is no `errors.log`. Data:
  `data/long-run/20261101T153136Z-seed3/`; the numbers below are in its `report.txt`, computed by
  `scripts/long-run-report`.

### The series

25 censuses, one an hour (`census.csv`):

| | Start | After 24 h |
|---|---:|---:|
| Node streams | 5 | 194 |
| Topic streams | 1 | 1 |
| `:dlq` streams | 0 | 0 |
| Consumer groups | 6 | 206 |
| Pending entries | 3 | 71 |
| Rows in `agenor_agents` | 5 | 14 |
| of which stale | 0 | 10 |
| Stream entries | 71 | 87,304 |
| Valkey `used_memory` | 1.37 MB | 65.6 MB |

- **Node streams:** 189 new nodes in `faults.csv`, 189 new streams. None went away. 190 of the 194
  belong to nodes that will never return.
- **Consumer groups:** one for each node stream, and 12 on the topic: the alerter's first, and one for
  each of its 11 kills. One subscriber is alive.
- **Directory rows:** 4 live, and one for each of the 10 probes killed. An orderly stop deletes its row
  (Q4); a kill does not. Every one of the 10 still says `RUNNING`, the oldest last seen 21 hours before
  the end of the run.
- **Pending entries:** at the end, 71: the 70 requests of F3 and 1 that a killed probe was working on.
  None will be reclaimed.
- **Memory:** 2.68 MB an hour, nearly in a straight line, at 3,633 stream entries an hour.

Where the bytes are at the end (`keys.csv`, by `MEMORY USAGE`):

| | Keys | Entries | Bytes | Share |
|---|---:|---:|---:|---:|
| Streams of dead nodes | 190 | 55,882 | 45,046,252 | 70.1% |
| The topic | 1 | 29,061 | 17,237,320 | 26.8% |
| Streams of live nodes | 4 | 2,361 | 1,969,856 | 3.1% |

### What decides it

Q5's rule: what grows with the number of restarts, without bound and without a cleanup described on a
page, is a defect, of the page if the cleanup exists, of the framework if it does not. Growth that
stops at `maxStreamLength` is not.

| What | Grows with | Bound | Cleanup on a page | Verdict |
|---|---|---|---|---|
| The topic's entries | time | `max-stream-length`, 100,000 by default; at this rate about 3.4 days | not needed | no defect |
| Entries of one node's stream | time | the same limit | not needed | no defect |
| Consumer groups on a topic | kills of a subscriber | none | `unsubscribe()` in `onStop()`, which a kill never reaches | F8: two readings |
| Directory rows of killed agents | kills of a probe | none | none; `unregister` is listed, for no such case | F8: two readings |
| Node streams, their groups and their memory | every new node | none | `DEL`, by hand | F8: two readings |

The bound on the topic was not reached in this run; it comes from the page.

### Against CRITERION §7

- **"Node streams grow by one per restart, without bound": confirmed.** 189 of 189, and 24 hours show
  nothing that would stop them.
- **"Topic groups grow by one per alerter restart": confirmed.** 11 of 11.
- **"In 24 hours, at one failure every 15 minutes, that is about 96 orphaned keys": refuted.** There
  are 190. A rolling upgrade or a scale up makes several new nodes, not one: 96 faults made 189.
- **"Not a memory problem: a cleanup problem that no page describes for an ephemeral id": half
  confirmed.** 65.6 MB in a day on this small load is not a problem yet, but it does not level off,
  and 70% of it is the mail of nodes that are gone. As to the page, the `DEL` line exists and does not
  mention an ephemeral id: see F8.

The expectation about topic groups was measured only for kills: every alerter stop in this run was
one. What an orderly stop of the alerter does to its group was not measured by any run here.

### What the run says about the other questions

Each unanswered request is put down to the latest fault that started no more than 20 s after it was
sent.

| State | Requests | Under |
|---|---:|---|
| answered | 29,061 | |
| timed out | 60 | rolling upgrades |
| timed out | 10 | scale downs |
| timed out | 33 | kills of a probe, at 10 of 10 |
| dead-lettered | 0 | |
| in flight | 0 | |
| lost | 9 | kills of the coordinator, at 5 of 11 |

- **Q2 holds:** a probe killed loses nothing, and its work times out. Of the 33 requests, 32 are unread
  in the dead node's stream and 1 is pending.
- **Q4 holds, at a larger scale:** 158 orderly stops, all with exit code 143, 0 lost. F3 cost 70
  requests, and it has a second form, recorded there.
- **F7 holds:** all 9 lost requests were answered by the probe, and the replies are unread in the
  stream of the coordinator that died.
- **O1 measured:** 20 outcomes at 8 of 11 alerter kills, recorded there.
- **New:** false alerts during orderly faults: O2.
- **Health:** 45 of 22,363 samples are not `UP`, all within 120 s of a fault starting. One of them is a
  503 from a probe during a rolling upgrade; the others could not reach a container.

### Limits of this run

- **Every target stayed up.** A missing alert cannot be seen in it, only a false one (O2).
- It ran on podman only, so it says nothing about real Docker, and O2 may belong to podman.
- The load is small and fixed: about 1,214 requests an hour. The memory figures are this load's.
- `alert.csv`, `directory.csv`, `keys.csv` and `failed-outcomes.csv` were copied almost 4 hours after
  the end, with the dispatching stopped and the stack still up, because `scripts/long-run-report` was
  written after the run. Just before the copy, Valkey's memory read within 32 bytes of the last census.
- `log-counts.txt` in this run's data is wrong, and is left as written: it says 100 `ERROR` and 300
  `WARN`, and the logs hold 116 and 312. It skipped `logs/removed`, on the mistaken belief that those
  logs only repeat the slices, when each also holds a removed probe's last stretch. `report.txt` has the
  right counts, and `scripts/long-run` now leaves the counting to the report.

## F8. What a restart leaves behind is never removed

- **Kind:** two readings, as in F3; which one prevails is not decided here. For each thing that grows,
  Agenor has a primitive that could remove it, and no page applies that primitive to this case. Found
  by Q5.
- **Version:** 0.39.0, Redis transport, JDBC directory and presence, the starter in all three roles.
- **Corrected:** as first committed, this entry said no page describes a cleanup for consumer groups,
  and called them and the directory rows framework defects. That was wrong: `messaging` describes
  `unsubscribe()`, and the entry diary had noted it. The search then covered 2 pages; now it covers
  all 29 pages reachable from the documentation home that open. Two more linked from it, ADR-032 and
  ADR-033, answered 404.

| What is left | At the end of the 24-hour run | What the pages offer | Why it does not reach this case |
|---|---|---|---|
| Node streams, with their groups | 190 of dead nodes | `adapters/redis`: "`DEL agenor:node:<nodeId>` when you retire an ID for good" | No page says that under the starter every restart retires an id, or how to tell which are retired |
| Consumer groups on a topic | 12 on `outcomes`, 1 subscriber alive | `messaging`: "Subscriptions are not automatically cleaned up on agent stop — call unsubscribe() in onStop()" | A kill never runs `onStop()`. The alerter subscribes with `@AgenorMessageHandler`, and no page says how such a handler gets its `Subscription`. No page says whether `unsubscribe()` removes the group on Redis |
| Directory rows of killed agents | 10, all reading `RUNNING` | `directory` lists `AgentRegistry.unregister` | No page connects it to an agent that died. `adapters/jdbc-directory` only says that `getStatus` answers `UNKNOWN` past the staleness window. Not tried here |

- **Reading 1, by the letter: a documentation defect.** Q5's rule makes it a defect of the page if the
  cleanup exists. A script could call `DEL`, and probably `unregister`, so what is missing is the page
  that says to, when, and on what.
- **Reading 2, in substance: a behavioural defect.** At 189 retired ids a day, a line for an operator's
  rare act is not a cleanup. The starter generates the ids, offers no property to pin one, and keeps
  no record of which are finished. The only way to find them is to compare streams with the directory,
  which itself keeps killed agents as `RUNNING`. A process killed cannot clean up after itself, and
  nothing else in Agenor does it for it.
- **In memory (CRITERION §5, point 7):** not tried. A process that dies takes an in-memory transport
  and directory with it, so the case should not exist.
- **Missing capabilities, phrased as CRITERION §5 point 3 asks:** a node that will not return stops
  holding space on the broker; a subscriber that is gone stops holding a group; an agent that died
  without goodbye does not stay in the directory for ever.

## Q8. The application restarts nothing and retries nothing; what it lacks is something to look at

- **Kind:** outcome of a question (CRITERION §4, Q8). No new finding.
- **What was read:** the 629 lines of the three roles and `sentinel-common`, and `compose.yml`.
  `scripts/tags` counts **0 lines** tagged `@supervision` (`data/tags/q1-q8-outcomes.csv`). That count
  was reread by hand for this entry, because a tag nobody wrote also reads 0.

### Each candidate, by the nature of its code

| Element | Why it exists | Nature | Counts for Q8 |
|---|---|---|---|
| `restart: unless-stopped` | A dead process comes back | The orchestrator's work | No |
| `Targets.claimDue` | A target is claimed once per interval, whatever became of the last check | No retry: a timed-out check is not sent again; the next one comes when it is due | No |
| `CoordinatorAgent.live`, 12 lines | `findByCapability` does not filter by liveness | Agenor already makes this visible, through `getStatus`. Q3's, tagged `@friction Q3` | No |
| `ownNode()`, the ledger's sender columns, `scripts/reconcile` | CRITERION §4's instrument | Measurement. No role decides anything by them | No |
| The health component | | Agenor's own, from the starter; no code here | No |

Nothing restarts an agent inside a live process, and nothing was ever wanted to. Each process holds
one agent, and every fault in this example takes both together. No regime stops an agent and leaves
its process alive, so this example gives no evidence on that case either way.

### What the application lacks, and has no code for

The example does not work around (CRITERION §5, point 8), so these needs produced findings, not lines:

| | What cannot be seen | Where |
|---|---|---|
| The transport is dead and health says `UP` | A node cannot tell an orchestrator its broker is gone | F4, F5 |
| A poison message lies pending | No signal after the first death | Q6 |
| Rows of killed agents read `RUNNING` | The stored status is wrong; only `getStatus` weighs it | Q3, F8 |
| Which streams are finished | Nothing marks them | F8 |

In F4 and F5 the cure is the orchestrator's: a restarted process would read the broker again. What it
lacks is a reason to restart, and that is a state to be made visible.

### What decides it, and where the criterion pulls two ways

Q8 says the nature of the code decides, not its amount. There is no code, so by the letter nothing
here is a case of anything: neither supervision nor crash visibility. Yet what the job dispatcher
asked for, to see what has failed, is the plainest need in the findings above, in a form it did not
have: not an agent that crashed, but a process that lost its transport and looks well. The rule
counts code, and CRITERION §5 point 8 forbids writing that code. The two together cannot record this
case. Which one prevails is not decided here, and CRITERION.md is not changed.

### Against CRITERION §7

- **"No restarting of agents inside a live process": confirmed**, within the limit above.
- **"A few lines to make an unresponsive probe visible": refuted.** No such lines: JDBC presence makes it
  visible, and the 12 lines of Q3 only use it.
- **"Supervision stays closed": confirmed.** Nothing here asks for a restart policy, a backoff or an
  escalation.
- **"Crash visibility gets its second case": not by the rule's measure**, which finds no code. In
  substance, yes, from F4 and F5, as above.

## Q1. A way in with the public documentation alone: yes, with no source reading

- **Kind:** outcome of a question (CRITERION §4, Q1). The account is in `docs/ENTRY-DIARY.md`.
- **Version:** 0.39.0, from Maven Central.
- **Source readings: 0**, in the entry and in everything since. Neither the `-sources.jar` nor Agenor's
  repository was opened. What the pages lacked was found in the Javadoc of the published jars, or by
  running the application.
- **Time:** about 35 minutes of recorded time to the first complete round (the diary's stretches 1 to 4).
  This is not a newcomer's time: see the limits below.

### Where the pages were not enough

| What was needed | Where it was found | Kind |
|---|---|---|
| Maven coordinates | Not on the home page; a second page, or a guess | gap |
| How a scanned agent declares a capability | Javadoc of `@Agent` only | gap |
| Presence settings | `adapters/jdbc-directory`; `spring-boot-starter` points away | gap |
| Version overrides on Spring Boot 4.0.9 | The note says up to 4.0.8; the pom on Central shows otherwise | stale page |
| One agent class in several processes | No page; the Javadoc of `@Agent` warns about one id | gap |
| A process's own node id | Javadoc of `AgentResolver` | gap |
| Package scanning in an executable jar | Logs, with health saying `UP` | F1, behavioural |
| Processes that start together on one database | `adapters/jdbc-directory`, a tip for another case; then a race with no setting | F2, F6 |

### Against CRITERION §7

- **"The way in is `distributed-quick-start`, `spring-boot-starter`, `redis` and `jdbc-directory`":
  refuted as too few.** The entry also needed `directory`, `messaging`, `agent-development`,
  `first-agent`, and the Javadoc of five jars.
- **"At least one source reading will be needed": refuted.** The reading it expected, what happens to
  the stream of a node that will not come back, was answered by `adapters/redis` and by running it (Q2).

### Limits

- The entry was made by an AI coding assistant, Claude Code, directed by the framework's maintainer, not
  by a developer working alone. The 35 minutes and the zero source readings are that assistant's, and
  say nothing about how long a person would take.
- Whoever enters here is not a stranger to the project: CRITERION §3 summarises the pages beforehand,
  and it was written by the framework's maintainer. Q1 shows the pages are sufficient, not that they
  are easy; CRITERION §8 leaves that to someone who did not write the framework.
- Zero source readings does not mean the pages are complete: several answers came from the Javadoc,
  and F7 and F8 record what no page says.

## The API census (CRITERION §6): the "before" side

`scripts/api-census 0.39.0` lists the Redis messaging, directory and presence packages as the published
Javadoc states them: 22 types and 124 members, in `data/api-census/0.39.0.txt`. CRITERION §6 asks for
this before and after, at the release that closes the example. 0.39.0 is still the latest on Central,
so there is no "after" yet. This example added nothing to Agenor's API, and any change comes from a
later release.
