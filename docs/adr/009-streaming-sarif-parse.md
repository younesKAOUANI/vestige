# ADR-009: Streaming SARIF parse with batched inserts

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §4.3, ADR-007

## Context

SARIF from a monorepo scan is not a small document. A repository-wide SonarQube or
CodeQL run against a large codebase routinely produces reports in the tens to
hundreds of megabytes, and Vestige's own upload ceiling (`vestige.ingestion.max-report-bytes`,
default 200 MB) is sized to that reality rather than an arbitrary round number. A
naïve `ObjectMapper.readValue(bytes, SomeSarifClass.class)` binds the entire document
into a Java object graph before any processing can start — for a 200 MB document, that
is several times 200 MB of heap held simultaneously for objects most of which are
read once, converted to a handful of fields, and discarded. Doing that inside a
request-scoped worker thread (§4.2's processing pipeline) for every large report a
tenant submits is a memory ceiling that scales with the worst report anyone happens
to upload, not with anything Vestige controls.

## Decision

`SarifReader` reads with Jackson's streaming (pull) `JsonParser` API end to end, never
binding the `results` array — the one that can be large — to an object graph as a
whole. `ObjectMapper.readTree(JsonParser)` is used only for small, bounded
sub-objects one at a time (one `tool`, one `artifacts[i]`, one `results[i]`), which is
the standard, documented way to mix Jackson's streaming and tree APIs without losing
the streaming guarantee for the document as a whole. Because SARIF does not guarantee
field order, and resolving a result fully needs sibling data that can appear
elsewhere in the same `run` object (`tool.driver.rules[].defaultConfiguration.level`,
`artifacts[].location.uri`), each result is first reduced to a small, bounded pending
record holding only what is still unresolved; final resolution and database batching
happen in one pass after the whole `run` object has streamed past and `tool`/
`artifacts` are known for good. Findings are handed to the caller's batch consumer
every `vestige.ingestion.finding-batch-size` results (default 1,000,
`RunProcessingService` then issues one JDBC batch `INSERT` per batch,
`spring.jpa.properties.hibernate.jdbc.batch_size: 1000` matching it) rather than
accumulated into one list for the whole report. Peak heap for parsing is therefore
bounded by one batch's worth of findings, not by the report's total size.

## Rejected alternatives

**Full object binding** (`ObjectMapper.readValue` into a complete SARIF object
model). The simplest code to write, and the default anyone reaches for first with
Jackson. Rejected specifically because it fails the one property this decision exists
to guarantee: bounded memory regardless of report size. §4.3 states the requirement
plainly — "peak heap stays flat regardless of report size" — and full binding cannot
deliver that by construction; it would mean either capping report size far below what
a real monorepo scan produces, or accepting that the worker's memory footprint is
hostage to whatever the largest report any tenant ever uploads happens to be.

**External object-storage staging** (write the uploaded bytes to S3/blob storage
first, then have the worker stream the parse from there instead of from a database
column). Solves a different problem than the one this ADR is about — where the bytes
*live* between upload and processing — and was in fact adopted for that question
(see the README "Roadmap": production would use object storage rather than the
`analysis_report_payload.sarif bytea` column this repository actually ships, a
deliberate v1 simplification). It does not, on its own, avoid full-graph binding; a
worker reading from an S3 object still needs to parse what it reads with a streaming
API to get the same peak-heap guarantee, so object storage and streaming parse are
complementary decisions, not alternatives to each other — this ADR is specifically
about *how the bytes are parsed*, independent of *where they are stored* while
waiting to be.

## Consequences

- The trade this makes explicitly, documented on `SarifReader` itself: JSON parsing
  is fully streaming end to end, but the database-batching pass runs *after*
  resolution rather than perfectly interleaved with token consumption. An eager
  "flush whenever a result's dependencies happen to already be known" approach was
  tried and rejected during development, because it made batch order — and therefore
  the persisted `finding.seq` identity column's order, which the matcher's
  determinism (§3.3) depends on — contingent on where `tool`/`artifacts` happen to sit
  in a given producer's output, which is exactly the kind of producer-dependent
  behaviour a deterministic system cannot afford.
- `RunProcessingService` streams directly from the persisted payload into
  `saveAll`-driven JDBC batches; there is no intermediate `List<Finding>` sized to the
  whole report anywhere in the processing path.
- The 200 MB ceiling and 1,000-row batch size are both configuration
  (`VestigeProperties.Ingestion`), not constants baked into `SarifReader`, so either
  can be tuned per deployment without a code change if real-world report sizes turn
  out larger or smaller than expected.

## What would change our mind

If a single report's `results` array itself (not the whole document, just that one
array) ever needed random access rather than a single forward pass — for a hypothetical
future feature that has to cross-reference results against each other during parsing,
rather than against previously-persisted state the way matching already does — that
would argue for a different strategy for that specific feature. Nothing in v1's
ingestion path needs more than one forward pass.
