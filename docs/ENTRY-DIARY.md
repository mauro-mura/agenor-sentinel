# Entry diary

This diary records what it takes to go from an empty project to a first complete round across three
processes, using only Agenor's public documentation and the artifacts on Maven Central. It is
written for a reader who does not know Agenor. Each stretch lists the steps, the pages read, any
reading of Agenor's source, and the time.

Times are wall-clock times taken from the shell (`date`) at the start and end of each stretch.
Where a stretch has no end time yet, it is still open.

Rule for this diary: reading Agenor's source is allowed only after asking, and every such reading
is recorded here with what was being looked for and which page lacked it. Source readings so far:
**0**.

## Stretch 1: coordinates and project skeleton

Started 2026-10-29 11:24 (+01:00).

### Steps

1. Guessed the Maven groupId as `dev.agenor` with a `maven-metadata.xml` request on Maven Central.
   The first request used the path `dev/agenor/maven-metadata.xml` and returned 404, which is
   expected: that is a group directory, not an artifact.
2. Opened the documentation home (`https://www.agenor.dev/docs/`) to find the page list and the
   Maven coordinates.
3. Read `distributed-quick-start` and `spring-boot-starter` for the coordinates and the wiring.
4. Confirmed on Central that `agenor-spring-boot-starter`, `agenor-adapters` and
   `agenor-adapters-persistence` all have 0.39.0 as their latest release.
5. Looked up the newest Spring Boot 4.0.x on Central (4.0.9), because the starter page says it
   targets Spring Boot 4.0.x.

### Pages read

- documentation home
- `distributed-quick-start`
- `spring-boot-starter`

### Observations

- **The documentation home does not show Maven coordinates.** It names the GitHub repository and
  the starter's Spring Boot line, but no groupId, artifactId or version. The groupId first appears
  on `distributed-quick-start`, and only for `agenor-adapters` and `agenor-adapters-persistence`.
  The starter's own coordinates are on `spring-boot-starter`. A newcomer who starts from the home
  page has to guess the groupId or open a second page. (Q1)
- **Pages were read through a page-to-text tool that summarizes**, so the exact code on the
  quick-start page was not seen; only a condensed walkthrough. The wiring code of the
  distributed quick start still has to be read in a browser. This is a limit of this diary's
  method, not a finding about the page.
- `spring-boot-starter` says presence configuration is not documented on that page and points to
  other guides. Whether the starter can turn JDBC presence on at all (Q3) is not yet known.
- `spring-boot-starter` gives dependency overrides (`jackson-2-bom.version`, `lettuce.version`)
  for applications whose parent is Spring Boot 4.0.8 or earlier. The newest 4.0.x on Central is
  4.0.9; whether it needs the overrides is to be found by building.
- Central also publishes a `-sources.jar` next to the `-javadoc.jar`. It has not been opened: this
  project reads documentation and Javadoc only.

### Source readings

None.

## Stretch 2: the API the three roles need

Started 2026-10-29 11:31 (+01:00). The pages were downloaded as HTML and converted to text
locally, so code samples were read exactly rather than through a summarizer.

### Pages read

- `distributed-quick-start`, `spring-boot-starter`, `adapters/redis`, `adapters/jdbc-directory`,
  `directory`, `messaging`, `agent-development`, `first-agent` (in full or in the relevant
  sections)
- Javadoc from the published `-javadoc.jar` files of `agenor-core`, `agenor-runtime`,
  `agenor-adapters`, `agenor-adapters-persistence` and `agenor-spring-boot-starter` 0.39.0:
  `@Agent`, `BaseAgent`, `DialogueCapability`

### What each role needs, and where it was found

| Need | Found | Where |
|---|---|---|
| Declare that a probe has capability `probe` | `@Agent(capabilities = {"probe"})` | Javadoc of `@Agent` only; no page shows it |
| Find probes | `AgentDiscovery.findByCapability("probe")` | `directory` |
| Send a check and wait for the outcome | `DialogueCapability.request(target, content, timeout)`, answered with `inform` or `failure` | `agent-development`, Javadoc of `DialogueCapability` |
| Publish and subscribe to `outcomes` | `getMessageDispatcher().publish(...)`, `@AgenorMessageHandler("outcomes")` | `agent-development`, `messaging` |
| Redis transport under the starter | `agenor.messaging.provider: redis` and `agenor.messaging.redis.*` | `spring-boot-starter`, `adapters/redis` |
| JDBC directory under the starter | `agenor.directory.provider: jdbc` and `agenor.directory.jdbc.*` | `spring-boot-starter` |
| JDBC presence under the starter | `agenor.directory.presence: jdbc`, `agenor.directory.heartbeat-interval` | `adapters/jdbc-directory` only |

### Observations

- **Capabilities on an agent class are documented only in the Javadoc.** The `directory` page
  shows capabilities on a hand-built `AgentDescriptor`; nothing on a page says how a scanned
  `BaseAgent` advertises them. A reader of the pages alone would not find
  `@Agent(capabilities = ...)`. (Q1)
- **Presence keys are missing from the starter's configuration reference.** `spring-boot-starter`
  lists `agenor.directory.provider` and `agenor.directory.jdbc.*`, and says presence is not
  documented there. The keys `agenor.directory.presence`, `heartbeat-interval` and
  `staleness-window` are on `adapters/jdbc-directory`. (Q1, Q3)
- **The request protocol does not expose the message id before sending.**
  `DialogueCapability.request(...)` builds and sends the REQUEST itself and returns only the
  future of the reply. The reconciliation ledger (CRITERION §4) wants each request written to a
  table *before* it is sent, *with its message id*. The application can know its own request
  id, carried in the payload, but not Agenor's message id at that point. (Q1, Q2)
- **An agent's id is a random UUID unless the class or the constructor sets one** (Javadoc of
  `@Agent` and `BaseAgent()`). With one probe class scanned in every probe process, `@Agent("probe")`
  would give every probe the same address, so a probe either keeps the generated UUID, which
  changes on every start, or derives its id from something per process.
- `messaging` says topic subscriptions are not cleaned up when an agent stops. Each topic
  subscription is its own consumer group on Redis (`adapters/redis`). Relevant to Q5.
- `adapters/redis` states that a node that never comes back keeps its stream, and that the remedy
  is `DEL agenor:node:<nodeId>` when an id is retired. It does not say how an operator learns
  which ids are retired when ids are generated (CRITERION §3, as expected).
- **No page covers one agent class replicated across processes.** Every distributed example
  gives each node agents with fixed, distinct ids. A search of the pages read for "replica",
  "scale" and "several processes" finds nothing. A reader of the pages would most likely copy
  `@Agent("probe")` from the examples; only the Javadoc of `@Agent` warns that two instances then
  claim one address. With discovery by capability the id does not matter to the caller, so the
  probe keeps the generated UUID. (Q1)

### Source readings

None.

## Stretch 3: the five modules

From 2026-10-29 11:34 to 11:54 (+01:00): writing the code, with a few more readings along the way.

### Steps

1. A parent `pom.xml` with five modules: `sentinel-common` (payloads and the application's
   schema), `sentinel-probe`, `sentinel-coordinator`, `sentinel-alerter`, and `sentinel-fixtures`
   (the fake targets and the alert webhook, JDK only).
2. The probe is discovered by package scanning (`agenor.agents.base-package`). The coordinator and
   the alerter need the application's database, so they are Spring beans registered with
   `runtime.registerAgent(...)`, as `spring-boot-starter` shows.
3. A `Dockerfile` and a `compose.yml` with PostgreSQL, Valkey, the fixtures and the three roles.
4. `mvn -DskipTests package` succeeded on the first attempt. The packaged jars contain the Agenor
   0.39.0 artifacts from Central, Lettuce 7.5.1.RELEASE and Jackson 2.22.1.

### Pages read

- `spring-boot-starter` again, for agents built as beans and for the version overrides
- Javadoc: `BaseAgent` (methods), `@DialogueHandler`, `@Behavior`, `Message`, `DialogueMessage`,
  `AgentDescriptor`

### Observations

- **The version-override note on `spring-boot-starter` is stale.** It says the
  `jackson-2-bom.version` and `lettuce.version` overrides are needed "up to Boot 4.0.8". The
  `spring-boot-dependencies` 4.0.9 pom on Central still manages Jackson 2.21.7 and Lettuce
  6.8.2, so the overrides are needed on 4.0.9 too. A reader on 4.0.9 who trusts the page would
  leave them out. (Q1)
- **The reply carries the request's message id, but only the reply.**
  `DialogueMessage.inReplyTo()` on the reply is the REQUEST's id, so the ledger records it when a
  reply arrives. For a request that times out or is dead-lettered, the application never learns
  Agenor's message id. The ledger is keyed by the application's own request id, carried in the
  payload; the reconciliation script has to find it inside stream entries. (Q2)
- The Javadoc of `@DialogueHandler` shows only `void` handlers and does not say whether a
  `CompletableFuture` may be returned, as `messaging` documents for `@AgenorMessageHandler`. The
  probe's handler blocks on its HTTP check and returns `void`.
- Whether `@Behavior` methods on an agent registered with `registerAgent(...)` (rather than
  discovered by scanning) are scheduled is not stated on a page. The coordinator relies on it;
  the first run will show.

### Source readings

None.

## Stretch 4: the first complete round

From 2026-10-29 14:54 to 14:59 (+01:00). The container runtime on this machine is podman 4.9.3
behind the Docker CLI, with docker-compose 1.29.2 as the compose provider.

### Steps

1. `docker compose build` failed: podman does not resolve the short image name
   `eclipse-temurin:...`. The `FROM` line now names `docker.io/library/eclipse-temurin:...`, which
   works on Docker too. Nothing to do with Agenor.
2. `docker compose up` could not pull PostgreSQL ("Proxy Authentication Required"): the podman
   service behind the socket does not have the shell's proxy credentials. Pulling the two images
   with the podman CLI first worked. Nothing to do with Agenor.
3. The stack came up, with two probes. The coordinator ran its `@Behavior` (so behaviours on an
   agent registered as a bean are scheduled) and logged "5 target(s) due and no probe in the
   directory": **both probes had started with zero agents**. Package scanning fails inside a
   Spring Boot executable jar. Recorded as **F1** in `FINDINGS.md`, with the evidence.
4. The images now start from Spring Boot's extracted layout (`Dockerfile`, marked `@friction`).
   The stack was torn down with its volumes and started clean at 14:58:17.
5. **First complete round at 14:58:56:** two probes registered with generated ids and transport
   `redis`; 11 requests sent round-robin (6 and 5), all answered `INFORM`, each with
   `inReplyTo` set.
6. Alerts: target `alpha` switched down at 14:59:05, DOWN alert at 14:59:29 (threshold 3,
   interval 10 s); switched up at 14:59:31, UP alert at 14:59:41. One alert of each kind, both
   delivered to the webhook (HTTP 204).
7. Outcomes published before the alerter had subscribed were never applied (5 of the first 29).
   This is documented behaviour; recorded as **O1** in `FINDINGS.md` because of what it means
   for an alerter restart.

### Observations

- **The first complete round took about 35 minutes of recorded time**, stretches 1 to 4 (11:24
  to 11:54 and 14:54 to 14:59; the three hours between them were spent away from the project, and
  the time a user spends deciding is not counted). One behavioural
  defect (F1) stood in the way, and was found only by reading the logs: the health endpoint said
  `UP`.
- On an orderly stop (SIGTERM from `timeout`, during the diagnosis of F1) a probe run from the
  host removed its own row from `agenor_agents`. That is a first sight of Q4, not a measure.

### Source readings

None. The entry is complete without a single reading of Agenor's source; CRITERION §7 expected
at least one (Q1). The question that expectation was about, what happens to the stream of a node
that never comes back, has not been asked yet: it belongs to Q2.
