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
