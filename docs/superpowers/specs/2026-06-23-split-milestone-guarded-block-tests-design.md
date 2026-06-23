# Split milestone-guarded block-production tests (#10007)

**Date:** 2026-06-23
**Issue:** [#10007 — Split integration tests](https://github.com/Consensys/teku/issues/10007)
**Status:** Approved design

## Problem

Three test classes use `@TestSpecContext(allMilestones = true)` and guard individual
`@TestTemplate` methods with `assumeThat(specMilestone)...` so that the test is *skipped*
on milestones where it does not apply. This produces noisy "skipped" results and obscures
which milestones each test actually targets.

Affected files:

- `data/beaconrestapi/.../handlers/v3/validator/GetNewBlockV3Test.java` (unit)
- `data/beaconrestapi/.../v3/GetNewBlockV3IntegrationTest.java` (integration)
- `validator/remote/.../typedef/handlers/ProduceBlockRequestTest.java` (integration)

## Goal

Remove every `assumeThat(...)` milestone skip by splitting each class so that each test
runs only against the milestones it applies to. Pure test refactor — no production code
changes, same effective coverage.

## Milestone ranges

Enum order: `PHASE0, ALTAIR, BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU, GLOAS, HEZE`.

| Group | Current guard | Milestones |
|---|---|---|
| Blinded blocks | `isBetween(BELLATRIX, FULU)` | BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU |
| Unblinded plain blocks | `< DENEB \|\| >= GLOAS` | PHASE0, ALTAIR, BELLATRIX, CAPELLA, GLOAS, HEZE |
| Unblinded block contents | `isBetween(DENEB, FULU)` | DENEB, ELECTRA, FULU |
| Milestone-agnostic | none | all |

## Design

### Structure — abstract base + one concrete class per range

For each original file: an abstract base holds the shared `@BeforeEach` setup and helper
methods; the original class name is kept for the milestone-agnostic tests; new
range-scoped classes hold the milestone-specific tests. No `assumeThat` remains.

**`GetNewBlockV3Test`** (unit, extends `AbstractMigratedBeaconHandlerTest`):

- `AbstractGetNewBlockV3Test extends AbstractMigratedBeaconHandlerTest` — `signature`
  field + `@BeforeEach` setup (`setSpec`, `setHandler`, request path/query params,
  `getMilestoneAtSlot` stub). The `specMilestone` field is dropped (only used by the
  removed assumes).
- `GetNewBlockV3Test` *(kept name)* — `@TestSpecContext(allMilestones = true)` —
  `shouldThrowExceptionWhenEmptyBlock`, `metadata_shouldHandle204/400/406/500/503`.
- `GetNewBlockV3BlindedTest` — `@TestSpecContext(milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU})` —
  `shouldHandleBlindedBeaconBlocks`.
- `GetNewBlockV3UnblindedTest` — `@TestSpecContext(allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU})` —
  `shouldHandleUnBlindedBeaconBlocks`.
- `GetNewBlockV3BlockContentsTest` — `@TestSpecContext(milestone = {DENEB, ELECTRA, FULU})` —
  `shouldHandleUnBlindedBlockContentsPostDeneb`.

**`GetNewBlockV3IntegrationTest`** (extends `AbstractDataBackedRestAPIIntegrationTest`):

- `AbstractGetNewBlockV3IntegrationTest` — `specMilestone` + `dataStructureUtil` fields,
  `@BeforeEach` setup (`startRestAPIAtGenesis`), and the `get` / `getExpectedBlockAsJson`
  / `assertResponseWithHeaders` helpers.
- `GetNewBlockV3IntegrationTest` *(kept)* — `allMilestones = true` — `shouldFailWhenNoBlockProduced`.
- `GetNewBlockV3BlindedIntegrationTest` — `milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU}` —
  `shouldGetBlindedBeaconBlockAsJson`, `shouldGetBlindedBeaconBlockAsSsz`.
- `GetNewBlockV3UnblindedIntegrationTest` — `allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU}` —
  `shouldGetUnBlindedBeaconBlockAsJson`, `shouldGetUnblindedBeaconBlockAsSsz`.
- `GetNewBlockV3BlockContentsIntegrationTest` — `milestone = {DENEB, ELECTRA, FULU}` —
  `shouldGetUnBlindedBlockContentPostDenebAsJson`, `shouldGetUnBlindedBlockContentPostDenebAsSsz`.

**`ProduceBlockRequestTest`** (extends `AbstractTypeDefRequestTestBase`, which already
provides `spec`, `specMilestone`, `dataStructureUtil`):

- `AbstractProduceBlockRequestTest extends AbstractTypeDefRequestTestBase` — `request` /
  `responseBodyBuffer` fields, `setupRequest` / `reset` lifecycle, `readExpectedJsonResource` helper.
- `ProduceBlockRequestTest` *(kept)* — `allMilestones = true` — `shouldPassUrlParameters`, `handle500`.
- `ProduceBlockRequestBlindedTest` — `milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU}` —
  `shouldGetBlindedBeaconBlockAsJson`, `shouldGetBlindedBeaconBlockAsSsz` (see anomaly below).
- `ProduceBlockRequestUnblindedTest` — `allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU}` —
  `shouldGetUnblindedBeaconBlockAsJson`, `shouldGetUnblindedBeaconBlockAsSsz`.
- `ProduceBlockRequestBlockContentsTest` — `milestone = {DENEB, ELECTRA, FULU}` —
  `shouldGetUnblindedBlockContentsPostDenebAsJson`, `shouldGetUnblindedBlockContentsPostDenebAsSsz`.

### Milestone declaration rationale

- **Unblinded plain blocks** → `allMilestones + ignoredMilestones = {DENEB, ELECTRA, FULU}`.
  Open-ended: post-FULU forks (GLOAS, HEZE, future) return plain blocks again, so
  auto-inclusion of new milestones is correct.
- **Blinded** (`{BELLATRIX…FULU}`) and **block-contents** (`{DENEB, ELECTRA, FULU}`) →
  explicit lists. Historically bounded (blinded blocks end at GLOAS/ePBS), so new
  milestones must not be auto-included.

### Anomaly: `ProduceBlockRequestTest.shouldGetBlindedBeaconBlockAsSsz`

This test currently has **no** `assumeThat` and runs on all milestones. It is moved into
`ProduceBlockRequestBlindedTest` (`{BELLATRIX…FULU}`), which is conceptually correct
(blinded blocks only exist in that range) but slightly reduces coverage (drops
PHASE0/ALTAIR/GLOAS/HEZE). **Must verify it passes in the blinded group.** If it
regresses, keep it in the agnostic `ProduceBlockRequestTest` class instead.

## Testing / verification

Pure test refactor; no production code touched. Verify the affected suites run green with
the same effective coverage and zero skipped tests from the removed assumes:

```bash
./gradlew :data:beaconrestapi:test --tests "*GetNewBlockV3*"
./gradlew :data:beaconrestapi:integrationTest --tests "*GetNewBlockV3*"
./gradlew :validator:remote:integrationTest --tests "*ProduceBlockRequest*"
./gradlew spotlessApply
```

## Out of scope

- Changing production code or test behavior beyond the assume removal.
- Refactoring other `assumeThat`-guarded test classes not listed in #10007.
- Altering the JSON resource fixtures (named per milestone) consumed by the tests.
