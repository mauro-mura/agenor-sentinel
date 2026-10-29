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
