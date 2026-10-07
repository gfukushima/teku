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

package tech.pegasys.teku.spec.logic.versions.gloas.withdrawals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.state.BeaconStateTestBuilder;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.gloas.MutableBeaconStateGloas;
import tech.pegasys.teku.spec.datastructures.state.versions.gloas.Builder;
import tech.pegasys.teku.spec.datastructures.state.versions.gloas.BuilderPendingWithdrawal;
import tech.pegasys.teku.spec.logic.common.withdrawals.WithdrawalsHelpers;
import tech.pegasys.teku.spec.logic.common.withdrawals.WithdrawalsHelpers.ExpectedWithdrawals;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class WithdrawalsHelpersGloasTest {

  private static final UInt64 BUILDER_INDEX = UInt64.ZERO;

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  @Test
  void buildersSweepDeductsWithdrawalsAlreadyQueuedForTheSameBuilder() {
    final UInt64 builderBalance = UInt64.valueOf(5_000_000_000L);
    final UInt64 pendingWithdrawalAmount = UInt64.valueOf(1_000_000_000L);

    final BeaconState state = stateWithBuilder(builderBalance, pendingWithdrawalAmount);

    final ExpectedWithdrawals expectedWithdrawals =
        getWithdrawalsHelpers().getExpectedWithdrawals(state);

    // The pending withdrawal is queued first, then the builder is swept for whatever is left.
    assertThat(expectedWithdrawals.withdrawals()).hasSize(2);
    assertThat(expectedWithdrawals.withdrawals().get(0).getAmount())
        .isEqualTo(pendingWithdrawalAmount);
    assertThat(expectedWithdrawals.withdrawals().get(1).getAmount())
        .isEqualTo(builderBalance.minus(pendingWithdrawalAmount));
  }

  @Test
  void buildersSweepSkipsBuilderFullyDrainedByQueuedWithdrawals() {
    final UInt64 builderBalance = UInt64.valueOf(1_000_000_000L);

    // The pending withdrawal consumes the whole balance, leaving nothing to sweep.
    final BeaconState state = stateWithBuilder(builderBalance, builderBalance);

    final ExpectedWithdrawals expectedWithdrawals =
        getWithdrawalsHelpers().getExpectedWithdrawals(state);

    assertThat(expectedWithdrawals.withdrawals()).hasSize(1);
    assertThat(expectedWithdrawals.withdrawals().getFirst().getAmount()).isEqualTo(builderBalance);
  }

  /**
   * A state holding a single builder that is withdrawable in the current epoch, with one pending
   * withdrawal already queued against it. There are no validators, so the validator sweep
   * contributes nothing.
   */
  private BeaconState stateWithBuilder(
      final UInt64 builderBalance, final UInt64 pendingWithdrawalAmount) {
    final Builder builder =
        dataStructureUtil
            .builderBuilder()
            .balance(builderBalance)
            .withdrawableEpoch(UInt64.ZERO)
            .build();
    final BuilderPendingWithdrawal pendingWithdrawal =
        SchemaDefinitionsGloas.required(spec.getGenesisSpec().getSchemaDefinitions())
            .getBuilderPendingWithdrawalSchema()
            .create(dataStructureUtil.randomEth1Address(), pendingWithdrawalAmount, BUILDER_INDEX);

    return new BeaconStateTestBuilder(dataStructureUtil)
        .build()
        .updated(
            mutableState -> {
              final MutableBeaconStateGloas stateGloas =
                  MutableBeaconStateGloas.required(mutableState);
              final SszListSchema<Builder, ?> buildersSchema = stateGloas.getBuilders().getSchema();
              stateGloas.setBuilders(buildersSchema.createFromElements(List.of(builder)));
              final SszListSchema<BuilderPendingWithdrawal, ?> pendingWithdrawalsSchema =
                  stateGloas.getBuilderPendingWithdrawals().getSchema();
              stateGloas.setBuilderPendingWithdrawals(
                  pendingWithdrawalsSchema.createFromElements(List.of(pendingWithdrawal)));
              stateGloas.setNextWithdrawalBuilderIndex(BUILDER_INDEX);
            });
  }

  private WithdrawalsHelpers getWithdrawalsHelpers() {
    return spec.getGenesisSpec().getWithdrawalsHelpers().orElseThrow();
  }
}
