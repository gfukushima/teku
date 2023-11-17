/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.beaconrestapi.v1.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.beaconrestapi.v1.validator.PostBlindedAndUnblindedBlockTest.Version.V1;
import static tech.pegasys.teku.beaconrestapi.v1.validator.PostBlindedAndUnblindedBlockTest.Version.V2;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import okhttp3.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.pegasys.teku.beacon.sync.events.SyncState;
import tech.pegasys.teku.beaconrestapi.AbstractDataBackedRestAPIIntegrationTest;
import tech.pegasys.teku.beaconrestapi.handlers.v1.beacon.PostBlindedBlock;
import tech.pegasys.teku.beaconrestapi.handlers.v1.beacon.PostBlock;
import tech.pegasys.teku.beaconrestapi.handlers.v2.beacon.PostBlindedBlockV2;
import tech.pegasys.teku.beaconrestapi.handlers.v2.beacon.PostBlockV2;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockSchema;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainer;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainerSchema;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.validator.api.SendSignedBlockResult;

public class PostBlindedAndUnblindedBlockTest extends AbstractDataBackedRestAPIIntegrationTest {

  private DataStructureUtil dataStructureUtil;

  public static Stream<Arguments> postBlockCases() {
    return Stream.of(
            PostBlock.ROUTE, PostBlindedBlock.ROUTE, PostBlockV2.ROUTE, PostBlindedBlockV2.ROUTE)
        .flatMap(
            route ->
                Stream.of(
                    // route, useSsz, useVersionHeader
                    Arguments.of(route, false, false),
                    // Methods using Eth-Consensus-Version header (only for SSZ, we could add it for
                    // json too when we start using it)
                    Arguments.of(route, true, false),
                    Arguments.of(route, true, true)))
        .map(
            args -> {
              final String route = (String) args.get()[0];
              final boolean isBlindedBlock = route.contains("blinded");
              final Version version = route.contains("/v2/") ? V2 : V1;
              final boolean useSsz = (boolean) args.get()[1];
              final boolean useVersionHeader = (boolean) args.get()[2];
              return Arguments.of(version, isBlindedBlock, route, useSsz, useVersionHeader);
            });
  }

  @ParameterizedTest(name = "version:{0}_blinded:{1}_ssz:{3}_versionHeader:{4}")
  @MethodSource("postBlockCases")
  void shouldReturnOk(
      final Version version,
      final boolean isBlindedBlock,
      final String route,
      final boolean useSsz,
      final boolean useVersionHeader)
      throws IOException {

    startRestAPIAtGenesis(SpecMilestone.BELLATRIX);
    dataStructureUtil = new DataStructureUtil(spec);

    when(syncService.getCurrentSyncState()).thenReturn(SyncState.IN_SYNC);

    final SignedBeaconBlockSchema signedBeaconBlockSchema;
    final SignedBeaconBlock request;

    if (isBlindedBlock) {
      request = dataStructureUtil.randomSignedBlindedBeaconBlock(UInt64.ONE);
      signedBeaconBlockSchema =
          spec.atSlot(UInt64.ONE).getSchemaDefinitions().getSignedBlindedBeaconBlockSchema();
    } else {
      request = dataStructureUtil.randomSignedBeaconBlock(UInt64.ONE);
      signedBeaconBlockSchema =
          spec.atSlot(UInt64.ONE).getSchemaDefinitions().getSignedBeaconBlockSchema();
    }

    prepareResponse(request, version);

    postRequestAndAssert(
        route, request, signedBeaconBlockSchema, useVersionHeader, useSsz, version);
  }

  @ParameterizedTest(name = "version:{0}_blinded:{1}_ssz:{3}_versionHeader:{4}")
  @MethodSource("postBlockCases")
  void shouldReturnOkPostDeneb(
      final Version version,
      final boolean isBlindedBlock,
      final String route,
      final boolean useSsz,
      final boolean useVersionHeader)
      throws IOException {
    startRestAPIAtGenesis(SpecMilestone.DENEB);
    dataStructureUtil = new DataStructureUtil(spec);

    when(syncService.getCurrentSyncState()).thenReturn(SyncState.IN_SYNC);

    final SignedBlockContainerSchema<SignedBlockContainer> signedBlockContainerSchema;
    final SignedBlockContainer request;

    if (isBlindedBlock) {
      request = dataStructureUtil.randomSignedBlindedBeaconBlock(UInt64.ONE);
      signedBlockContainerSchema =
          spec.atSlot(UInt64.ONE).getSchemaDefinitions().getSignedBlindedBlockContainerSchema();
    } else {
      request = dataStructureUtil.randomSignedBlockContents(UInt64.ONE);
      signedBlockContainerSchema =
          spec.atSlot(UInt64.ONE).getSchemaDefinitions().getSignedBlockContainerSchema();
    }

    prepareResponse(request, version);

    postRequestAndAssert(
        route, request, signedBlockContainerSchema, useVersionHeader, useSsz, version);
  }

  private void prepareResponse(final SignedBeaconBlock request, final Version version) {
    if (version == V2) {
      when(validatorApiChannel.sendSignedBlock(
              request, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION))
          .thenReturn(SafeFuture.completedFuture(SendSignedBlockResult.success(request.getRoot())));
    } else {
      when(validatorApiChannel.sendSignedBlock(request, BroadcastValidationLevel.NOT_REQUIRED))
          .thenReturn(SafeFuture.completedFuture(SendSignedBlockResult.success(request.getRoot())));
    }
  }

  private void prepareResponse(final SignedBlockContainer request, final Version version) {
    if (version == V2) {
      when(validatorApiChannel.sendSignedBlock(
              request, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION))
          .thenReturn(SafeFuture.completedFuture(SendSignedBlockResult.success(request.getRoot())));
    } else {
      when(validatorApiChannel.sendSignedBlock(request, BroadcastValidationLevel.NOT_REQUIRED))
          .thenReturn(SafeFuture.completedFuture(SendSignedBlockResult.success(request.getRoot())));
    }
  }

  private <T extends SszData> void postRequestAndAssert(
      final String route,
      final T request,
      final SszSchema<T> signedBlockContainerSchema,
      final boolean useVersionHeader,
      final boolean useSsz,
      final Version version)
      throws IOException {
    Map<String, String> params = new HashMap<>();

    if (version == V2) {
      params.put("broadcast_validation", "consensus_and_equivocation");
    }

    // Hardcode deneb
    // when we are in bellatrix, in spec.deserializeSigned(blinded)BlockContainer, milestone lookup
    // will fail and will fall back to slot based selection.
    // Having all possible combinations would be super slow.
    final Optional<String> milestoneHeader =
        useVersionHeader ? Optional.of("deneb") : Optional.empty();

    if (useSsz) {
      try (final Response response =
          postSsz(
              route,
              signedBlockContainerSchema.sszSerialize(request).toArrayUnsafe(),
              params,
              milestoneHeader)) {
        assertThat(response.code()).isEqualTo(SC_OK);
      }
    } else {
      try (final Response response =
          post(
              route,
              JsonUtil.serialize(request, signedBlockContainerSchema.getJsonTypeDefinition()),
              params,
              milestoneHeader)) {
        assertThat(response.code()).isEqualTo(SC_OK);
      }
    }
  }

  enum Version {
    V1,
    V2
  }
}
