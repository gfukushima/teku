# Split milestone-guarded block-production tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all `assumeThat(...)` milestone skips from three block-production test classes by splitting each into milestone-range-scoped classes.

**Architecture:** Each original `@TestSpecContext(allMilestones = true)` class is split into an abstract base (shared `@BeforeEach` + helpers) plus concrete classes scoped to milestone ranges via `@TestSpecContext`. Tests move verbatim into the class whose range matches their old `assumeThat` guard; the guard is deleted. The original class name is kept for milestone-agnostic tests.

**Tech Stack:** Java 25, JUnit 5 (`@TestTemplate` + `@TestSpecContext` invocation provider), Mockito, AssertJ, Gradle.

## Global Constraints

- Pure test refactor — **no production code changes**.
- Same effective coverage: a test must run on exactly the milestones it ran on before (where the old assume defined the range), with the one documented anomaly exception.
- Milestone enum order: `PHASE0, ALTAIR, BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU, GLOAS, HEZE`.
- Declaration rules: unblinded-plain → `allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU}`; blinded → `milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU}`; block-contents → `milestone = {DENEB, ELECTRA, FULU}`; agnostic → `allMilestones = true`.
- Run `./gradlew spotlessApply` before every commit.
- Copyright header on every new file (copy the 12-line header verbatim from the original file being split).
- Imports only — no fully-qualified class names in code (CLAUDE.md).
- This is a relocation refactor: the "tests" already exist. TDD here means *move the test, run it, see it pass on the scoped milestones, confirm zero assume-skips remain*.

---

### Task 1: Split `GetNewBlockV3Test` (unit test)

**Files:**
- Create: `data/beaconrestapi/src/test/java/tech/pegasys/teku/beaconrestapi/handlers/v3/validator/AbstractGetNewBlockV3Test.java`
- Create: `.../v3/validator/GetNewBlockV3BlindedTest.java`
- Create: `.../v3/validator/GetNewBlockV3UnblindedTest.java`
- Create: `.../v3/validator/GetNewBlockV3BlockContentsTest.java`
- Modify: `.../v3/validator/GetNewBlockV3Test.java` (strip to agnostic tests)

**Interfaces:**
- Consumes: `AbstractMigratedBeaconHandlerTest` (existing base — provides `validatorDataProvider`, `schemaDefinitionCache`, `dataStructureUtil`, `request`, `setSpec`, `setHandler`).
- Produces: `AbstractGetNewBlockV3Test` exposing `protected final BLSSignature signature` and a `@BeforeEach void setup(SpecContext)` that all four concrete classes inherit.

- [ ] **Step 1: Create the abstract base `AbstractGetNewBlockV3Test`**

Holds the shared setup. Note the `specMilestone` field from the original is **dropped** (it was only read by the removed `assumeThat` calls). Copyright header + package `tech.pegasys.teku.beaconrestapi.handlers.v3.validator`.

```java
abstract class AbstractGetNewBlockV3Test extends AbstractMigratedBeaconHandlerTest {

  protected final BLSSignature signature = BLSTestUtil.randomSignature(1234);

  @BeforeEach
  public void setup(final TestSpecInvocationContextProvider.SpecContext specContext) {
    setSpec(specContext.getSpec());
    setHandler(new GetNewBlockV3(validatorDataProvider, schemaDefinitionCache));
    request.setPathParameter(SLOT, "1");
    request.setQueryParameter(RANDAO_REVEAL, signature.toBytesCompressed().toHexString());
    when(validatorDataProvider.getMilestoneAtSlot(UInt64.ONE)).thenReturn(SpecMilestone.ALTAIR);
  }
}
```

Imports needed (static + regular): `org.mockito.Mockito.when`; `RestApiConstants.RANDAO_REVEAL`, `RestApiConstants.SLOT`; `org.junit.jupiter.api.BeforeEach`; `tech.pegasys.teku.beaconrestapi.AbstractMigratedBeaconHandlerTest`; `tech.pegasys.teku.bls.BLSSignature`; `tech.pegasys.teku.bls.BLSTestUtil`; `tech.pegasys.teku.infrastructure.unsigned.UInt64`; `tech.pegasys.teku.spec.SpecMilestone`; `tech.pegasys.teku.spec.TestSpecInvocationContextProvider`.

- [ ] **Step 2: Create `GetNewBlockV3BlindedTest`**

```java
@TestSpecContext(milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU})
public class GetNewBlockV3BlindedTest extends AbstractGetNewBlockV3Test {
  // move shouldHandleBlindedBeaconBlocks() here verbatim, deleting its first line:
  //   assumeThat(specMilestone).isBetween(BELLATRIX, FULU);
}
```

Move `shouldHandleBlindedBeaconBlocks()` from the original verbatim, deleting the `assumeThat` line. Add imports the moved method needs: `BlockContainerAndMetaData`, `SafeFuture`, `HttpStatusCodes`, `HEADER_*` constants, `ONE`, `assertThat`, `doReturn`, `Optional`, `TestTemplate`, and `SpecMilestone.{BELLATRIX,CAPELLA,DENEB,ELECTRA,FULU}`, `TestSpecContext`.

- [ ] **Step 3: Create `GetNewBlockV3UnblindedTest`**

```java
@TestSpecContext(allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU})
public class GetNewBlockV3UnblindedTest extends AbstractGetNewBlockV3Test {
  // move shouldHandleUnBlindedBeaconBlocks() here verbatim, deleting its first two lines:
  //   assumeThat(specMilestone.isLessThan(DENEB) || specMilestone.isGreaterThanOrEqualTo(GLOAS))
  //       .isTrue();
}
```

- [ ] **Step 4: Create `GetNewBlockV3BlockContentsTest`**

```java
@TestSpecContext(milestone = {DENEB, ELECTRA, FULU})
public class GetNewBlockV3BlockContentsTest extends AbstractGetNewBlockV3Test {
  // move shouldHandleUnBlindedBlockContentsPostDeneb() here verbatim, deleting:
  //   assumeThat(specMilestone).isBetween(DENEB, FULU);
}
```

Add import for `BlockContainer` (used by this method).

- [ ] **Step 5: Strip `GetNewBlockV3Test` to agnostic tests only**

Edit the original file: change class declaration to `extends AbstractGetNewBlockV3Test`, keep `@TestSpecContext(allMilestones = true)`. **Delete** the three moved methods, the `@BeforeEach setup`, the `signature` field, the `specMilestone` field. **Keep**: `shouldThrowExceptionWhenEmptyBlock`, `metadata_shouldHandle204/400/406/500/503`. Remove now-unused imports (`assumeThat`, `BELLATRIX`, `DENEB`, `FULU`, `GLOAS`, `BlockContainer`, `BlockContainerAndMetaData`, `BLSTestUtil`, `BLSSignature`, `when`, `doReturn`, `SafeFuture`, `Optional`, `SLOT`, `RANDAO_REVEAL`, `ONE`, the `HEADER_*` no longer referenced — verify against remaining method bodies before deleting each).

Resulting class body keeps only:
```java
@TestSpecContext(allMilestones = true)
public class GetNewBlockV3Test extends AbstractGetNewBlockV3Test {
  @TestTemplate
  void shouldThrowExceptionWhenEmptyBlock() throws Exception { /* verbatim */ }

  @TestTemplate
  void metadata_shouldHandle204() { /* verbatim */ }
  // ... 400, 406, 500, 503 verbatim
}
```

- [ ] **Step 6: Run the suite**

Run: `./gradlew :data:beaconrestapi:test --tests "tech.pegasys.teku.beaconrestapi.handlers.v3.validator.GetNewBlockV3*"`
Expected: PASS, all four classes execute, **zero skipped tests**.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add data/beaconrestapi/src/test/java/tech/pegasys/teku/beaconrestapi/handlers/v3/validator/
git commit -m "Split GetNewBlockV3Test by milestone range to remove assume guards (#10007)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Split `GetNewBlockV3IntegrationTest`

**Files:**
- Create: `data/beaconrestapi/src/integration-test/java/tech/pegasys/teku/beaconrestapi/v3/AbstractGetNewBlockV3IntegrationTest.java`
- Create: `.../v3/GetNewBlockV3BlindedIntegrationTest.java`
- Create: `.../v3/GetNewBlockV3UnblindedIntegrationTest.java`
- Create: `.../v3/GetNewBlockV3BlockContentsIntegrationTest.java`
- Modify: `.../v3/GetNewBlockV3IntegrationTest.java` (strip to agnostic test)

**Interfaces:**
- Consumes: `AbstractDataBackedRestAPIIntegrationTest` (provides `spec`, `validatorApiChannel`, `startRestAPIAtGenesis`, `getResponse`).
- Produces: `AbstractGetNewBlockV3IntegrationTest` exposing `protected SpecMilestone specMilestone`, `protected DataStructureUtil dataStructureUtil`, the `@BeforeEach setup(SpecContext)`, and helpers `get(BLSSignature, String)`, `getExpectedBlockAsJson(SpecMilestone, boolean, boolean)`, `assertResponseWithHeaders(Response, boolean, UInt256, UInt256)`.

- [ ] **Step 1: Create `AbstractGetNewBlockV3IntegrationTest`**

Package `tech.pegasys.teku.beaconrestapi.v3`. Move into it (verbatim from the original): `LOG`, `dataStructureUtil`, `specMilestone` fields; the `@BeforeEach setup`; and the three private helpers `get`, `getExpectedBlockAsJson`, `assertResponseWithHeaders` — changing their visibility from `private` to `protected`. Inside `getExpectedBlockAsJson`, the `Resources.getResource(GetNewBlockV3IntegrationTest.class, fileName)` call must change to `AbstractGetNewBlockV3IntegrationTest.class` (same package, resource files unchanged).

```java
abstract class AbstractGetNewBlockV3IntegrationTest
    extends AbstractDataBackedRestAPIIntegrationTest {

  private static final Logger LOG = LogManager.getLogger();
  protected DataStructureUtil dataStructureUtil;
  protected SpecMilestone specMilestone;

  @BeforeEach
  void setup(final TestSpecInvocationContextProvider.SpecContext specContext) {
    spec = specContext.getSpec();
    specMilestone = specContext.getSpecMilestone();
    startRestAPIAtGenesis(specMilestone);
    dataStructureUtil = specContext.getDataStructureUtil();
  }

  protected Response get(final BLSSignature signature, final String contentType) throws IOException { /* verbatim */ }
  protected String getExpectedBlockAsJson(final SpecMilestone specMilestone, final boolean blinded, final boolean blockContents) throws IOException { /* verbatim, with class ref change */ }
  protected void assertResponseWithHeaders(final Response response, final boolean blinded, final UInt256 executionPayloadValue, final UInt256 consensusBlockValue) { /* verbatim */ }
}
```

- [ ] **Step 2: Create `GetNewBlockV3BlindedIntegrationTest`**

```java
@TestSpecContext(milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU})
public class GetNewBlockV3BlindedIntegrationTest extends AbstractGetNewBlockV3IntegrationTest {
  // move shouldGetBlindedBeaconBlockAsJson() and shouldGetBlindedBeaconBlockAsSsz() verbatim,
  // each deleting its leading: assumeThat(specMilestone).isBetween(BELLATRIX, FULU);
}
```

- [ ] **Step 3: Create `GetNewBlockV3UnblindedIntegrationTest`**

```java
@TestSpecContext(allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU})
public class GetNewBlockV3UnblindedIntegrationTest extends AbstractGetNewBlockV3IntegrationTest {
  // move shouldGetUnBlindedBeaconBlockAsJson() and shouldGetUnblindedBeaconBlockAsSsz() verbatim,
  // each deleting its leading two-line assumeThat(... isLessThan(DENEB) || ... GLOAS).isTrue();
}
```

- [ ] **Step 4: Create `GetNewBlockV3BlockContentsIntegrationTest`**

```java
@TestSpecContext(milestone = {DENEB, ELECTRA, FULU})
public class GetNewBlockV3BlockContentsIntegrationTest extends AbstractGetNewBlockV3IntegrationTest {
  // move shouldGetUnBlindedBlockContentPostDenebAsJson() and shouldGetUnBlindedBlockContentPostDenebAsSsz()
  // verbatim, each deleting: assumeThat(specMilestone).isBetween(DENEB, FULU);
}
```

- [ ] **Step 5: Strip `GetNewBlockV3IntegrationTest` to agnostic test**

Change to `extends AbstractGetNewBlockV3IntegrationTest`, keep `@TestSpecContext(allMilestones = true)`. Delete the six moved methods, the three helpers (now in base), the `@BeforeEach`, and the `LOG`/`dataStructureUtil`/`specMilestone` fields. Keep only `shouldFailWhenNoBlockProduced`. Prune now-unused imports (`assumeThat`, milestone constants, `JsonNode`, `Resources`, etc. — verify against the remaining method).

- [ ] **Step 6: Run the suite**

Run: `./gradlew :data:beaconrestapi:integrationTest --tests "tech.pegasys.teku.beaconrestapi.v3.GetNewBlockV3*"`
Expected: PASS across all four classes, **zero skipped tests**.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add data/beaconrestapi/src/integration-test/java/tech/pegasys/teku/beaconrestapi/v3/
git commit -m "Split GetNewBlockV3IntegrationTest by milestone range to remove assume guards (#10007)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Split `ProduceBlockRequestTest`

**Files:**
- Create: `validator/remote/src/integration-test/java/tech/pegasys/teku/validator/remote/typedef/handlers/AbstractProduceBlockRequestTest.java`
- Create: `.../handlers/ProduceBlockRequestBlindedTest.java`
- Create: `.../handlers/ProduceBlockRequestUnblindedTest.java`
- Create: `.../handlers/ProduceBlockRequestBlockContentsTest.java`
- Modify: `.../handlers/ProduceBlockRequestTest.java` (strip to agnostic tests)

**Interfaces:**
- Consumes: `AbstractTypeDefRequestTestBase` (existing — provides `spec`, `specMilestone`, `dataStructureUtil`, `mockWebServer`, `okHttpClient`, `readResource`; its own `@BeforeEach beforeEach(SpecContext)` runs before subclass setup).
- Produces: `AbstractProduceBlockRequestTest` exposing `protected ProduceBlockRequest request`, `protected Buffer responseBodyBuffer`, the `setupRequest`/`reset` lifecycle, and `protected String readExpectedJsonResource(SpecMilestone, boolean, boolean)`.

- [ ] **Step 1: Create `AbstractProduceBlockRequestTest`**

Package `tech.pegasys.teku.validator.remote.typedef.handlers`. Move the `LOG`, `request`, `responseBodyBuffer` fields, the `setupRequest` `@BeforeEach`, the `reset` `@AfterEach`, and the `readExpectedJsonResource` helper (visibility `private` → `protected`).

```java
abstract class AbstractProduceBlockRequestTest extends AbstractTypeDefRequestTestBase {

  private static final Logger LOG = LogManager.getLogger();
  protected ProduceBlockRequest request;
  protected Buffer responseBodyBuffer;

  @BeforeEach
  void setupRequest() {
    request =
        new ProduceBlockRequest(
            mockWebServer.url("/"), okHttpClient, new SchemaDefinitionCache(spec), UInt64.ONE, false);
    responseBodyBuffer = new Buffer();
  }

  @AfterEach
  void reset() {
    responseBodyBuffer.clear();
    responseBodyBuffer.close();
  }

  protected String readExpectedJsonResource(
      final SpecMilestone specMilestone, final boolean blinded, final boolean blockContents) {
    /* verbatim */
  }
}
```

- [ ] **Step 2: Create `ProduceBlockRequestBlindedTest`** (includes the anomaly test)

```java
@TestSpecContext(milestone = {BELLATRIX, CAPELLA, DENEB, ELECTRA, FULU})
public class ProduceBlockRequestBlindedTest extends AbstractProduceBlockRequestTest {
  // move shouldGetBlindedBeaconBlockAsJson() verbatim, deleting:
  //   assumeThat(specMilestone).isBetween(BELLATRIX, FULU);
  // move shouldGetBlindedBeaconBlockAsSsz() verbatim (it had NO assume — see anomaly note).
}
```

`shouldGetBlindedBeaconBlockAsSsz` is the documented anomaly: it had no guard and ran on all milestones. It moves into the blinded range here. **Watch its result in Step 6** — if it fails on any of `{BELLATRIX..FULU}`, that's a real signal; if it would have needed PHASE0/ALTAIR/GLOAS/HEZE, move it instead into the agnostic `ProduceBlockRequestTest` class (Step 5) and note why.

- [ ] **Step 3: Create `ProduceBlockRequestUnblindedTest`**

```java
@TestSpecContext(allMilestones = true, ignoredMilestones = {DENEB, ELECTRA, FULU})
public class ProduceBlockRequestUnblindedTest extends AbstractProduceBlockRequestTest {
  // move shouldGetUnblindedBeaconBlockAsJson() and shouldGetUnblindedBeaconBlockAsSsz() verbatim,
  // each deleting its leading two-line assumeThat(... isLessThan(DENEB) || ... GLOAS).isTrue();
  // NOTE: shouldGetUnblindedBeaconBlockAsSsz keeps its internal `if (isGreaterThanOrEqualTo(BELLATRIX))` branch.
}
```

- [ ] **Step 4: Create `ProduceBlockRequestBlockContentsTest`**

```java
@TestSpecContext(milestone = {DENEB, ELECTRA, FULU})
public class ProduceBlockRequestBlockContentsTest extends AbstractProduceBlockRequestTest {
  // move shouldGetUnblindedBlockContentsPostDenebAsJson() and shouldGetUnblindedBlockContentsPostDenebAsSsz()
  // verbatim, each deleting: assumeThat(specMilestone).isBetween(DENEB, FULU);
}
```

- [ ] **Step 5: Strip `ProduceBlockRequestTest` to agnostic tests**

Change to `extends AbstractProduceBlockRequestTest`, keep `@TestSpecContext(allMilestones = true)`. Delete moved methods, the lifecycle methods (`setupRequest`, `reset`), the `LOG`/`request`/`responseBodyBuffer` fields, and `readExpectedJsonResource`. Keep `shouldPassUrlParameters` and `handle500`. Prune unused imports (`assumeThat`, milestone constants, `MediaType`, `MockResponse` if unused by remaining tests, etc. — `shouldPassUrlParameters` and `handle500` still use `MockResponse`, `RecordedRequest`, `Bytes32`, so keep those).

- [ ] **Step 6: Run the suite**

Run: `./gradlew :validator:remote:integrationTest --tests "tech.pegasys.teku.validator.remote.typedef.handlers.ProduceBlockRequest*"`
Expected: PASS across all five classes, **zero skipped tests**. Confirm the anomaly test (`shouldGetBlindedBeaconBlockAsSsz`) passes in the blinded group; if not, apply the fallback from Step 2.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add validator/remote/src/integration-test/java/tech/pegasys/teku/validator/remote/typedef/handlers/
git commit -m "Split ProduceBlockRequestTest by milestone range to remove assume guards (#10007)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Verification (after all tasks)

- [ ] No `assumeThat` remains in the six split files:
  `rg "assumeThat" data/beaconrestapi validator/remote | rg -i "GetNewBlockV3|ProduceBlockRequest"` → no matches.
- [ ] Full affected-module builds pass:
  `./gradlew :data:beaconrestapi:test :data:beaconrestapi:integrationTest :validator:remote:integrationTest`
- [ ] `./gradlew spotlessCheck` clean.
