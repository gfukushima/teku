/*
 * Copyright Consensys Software Inc., 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.beaconrestapi.handlers.v3.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_CONSENSUS_BLOCK_VALUE;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_CONSENSUS_VERSION;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_EXECUTION_PAYLOAD_BLINDED;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_EXECUTION_PAYLOAD_VALUE;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ONE;
import static tech.pegasys.teku.spec.SpecMilestone.DENEB;
import static tech.pegasys.teku.spec.SpecMilestone.ELECTRA;
import static tech.pegasys.teku.spec.SpecMilestone.FULU;

import java.util.Optional;
import org.junit.jupiter.api.TestTemplate;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.http.HttpStatusCodes;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.datastructures.metadata.BlockContainerAndMetaData;

@TestSpecContext(
    allMilestones = true,
    ignoredMilestones = {DENEB, ELECTRA, FULU})
public class GetNewBlockV3UnblindedTest extends AbstractGetNewBlockV3Test {

  @TestTemplate
  void shouldHandleUnBlindedBeaconBlocks() throws Exception {
    BlockContainerAndMetaData blockContainerAndMetaData =
        dataStructureUtil.randomBlockContainerAndMetaData(ONE);
    doReturn(SafeFuture.completedFuture(Optional.of(blockContainerAndMetaData)))
        .when(validatorDataProvider)
        .produceBlock(ONE, signature, Optional.empty(), Optional.empty());

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(HttpStatusCodes.SC_OK);
    assertThat(request.getResponseBody()).isEqualTo(blockContainerAndMetaData);
    assertThat(request.getResponseHeaders(HEADER_CONSENSUS_VERSION))
        .isEqualTo(blockContainerAndMetaData.specMilestone().lowerCaseName());
    assertThat(request.getResponseHeaders(HEADER_EXECUTION_PAYLOAD_BLINDED))
        .isEqualTo(Boolean.toString(false));
    assertThat(request.getResponseHeaders(HEADER_EXECUTION_PAYLOAD_VALUE))
        .isEqualTo(blockContainerAndMetaData.executionPayloadValue().toDecimalString());
    assertThat(request.getResponseHeaders(HEADER_CONSENSUS_BLOCK_VALUE))
        .isEqualTo(blockContainerAndMetaData.consensusBlockValue().toDecimalString());
  }
}
