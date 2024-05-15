/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.spec.logic.versions.electra.operations.validation;

import static tech.pegasys.teku.spec.logic.common.operations.validation.OperationInvalidReason.check;
import static tech.pegasys.teku.spec.logic.common.operations.validation.OperationInvalidReason.firstOf;

import java.util.Optional;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.state.Fork;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.helpers.BeaconStateAccessors;
import tech.pegasys.teku.spec.logic.common.helpers.MiscHelpers;
import tech.pegasys.teku.spec.logic.common.operations.validation.AttestationDataValidator;
import tech.pegasys.teku.spec.logic.common.operations.validation.OperationInvalidReason;

public class AttestationDataValidatorElectra implements AttestationDataValidator {

  private final SpecConfig specConfig;
  private final MiscHelpers miscHelpers;
  private final BeaconStateAccessors beaconStateAccessors;

  public AttestationDataValidatorElectra(
      final SpecConfig specConfig,
      final MiscHelpers miscHelpers,
      final BeaconStateAccessors beaconStateAccessors) {
    this.specConfig = specConfig;
    this.miscHelpers = miscHelpers;
    this.beaconStateAccessors = beaconStateAccessors;
  }

  @Override
  public Optional<OperationInvalidReason> validate(
      final Fork fork, final BeaconState state, final AttestationData data) {
    return firstOf(
        () ->
            check(
                miscHelpers
                        .computeEpochAtSlot(data.getSlot())
                        .equals(beaconStateAccessors.getPreviousEpoch(state))
                    || miscHelpers
                        .computeEpochAtSlot(data.getSlot())
                        .equals(beaconStateAccessors.getCurrentEpoch(state)),
                () -> "Attestation is not from the current or previous epoch"),
        () ->
            check(
                miscHelpers
                    .computeEpochAtSlot(data.getSlot())
                    .equals(miscHelpers.computeEpochAtSlot(data.getSlot())),
                () -> "Attestation slot is not within the attestation's epoch"),
        () ->
            check(
                data.getSlot()
                        .plus(specConfig.getMinAttestationInclusionDelay())
                        .compareTo(state.getSlot())
                    <= 0,
                () -> "Attestation submitted too quickly"),
        () -> {
          UInt64 currentEpoch = beaconStateAccessors.getCurrentEpoch(state);
          return check(
              data.getSource().getEpoch().equals(currentEpoch)
                  || data.getSource()
                      .getEpoch()
                      .equals(beaconStateAccessors.getPreviousEpoch(state)),
              () -> "Attestation has incorrect justified checkpoint");
        });
  }
}
