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

package tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.phase0;

import java.util.HashMap;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.state.PendingAttestation;
import tech.pegasys.teku.spec.datastructures.util.AttestationProcessingResult;
import tech.pegasys.teku.spec.datastructures.util.AttestationUtil;

interface ValidatorStatsPhase0 extends BeaconStatePhase0 {
  @Override
  default CorrectAndLiveValidators getValidatorStatsPreviousEpoch(final Bytes32 correctTargetRoot) {
    return getValidatorStats(getPreviousEpochAttestations(), correctTargetRoot);
  }

  @Override
  default CorrectAndLiveValidators getValidatorStatsCurrentEpoch(final Bytes32 correctTargetRoot) {
    return getValidatorStats(getCurrentEpochAttestations(), correctTargetRoot);
  }

  private CorrectAndLiveValidators getValidatorStats(
      final Iterable<PendingAttestation> attestations, final Bytes32 correctTargetRoot) {

    final Map<UInt64, Map<UInt64, AttestationProcessingResult>>
        liveValidatorsAggregationBitsBySlotAndCommittee = new HashMap<>();
    final Map<UInt64, Map<UInt64, AttestationProcessingResult>>
        correctValidatorsAggregationBitsBySlotAndCommittee = new HashMap<>();

    attestations.forEach(
        attestation -> {
          if (isCorrectAttestation(attestation, correctTargetRoot)) {
            correctValidatorsAggregationBitsBySlotAndCommittee
                .computeIfAbsent(attestation.getData().getSlot(), __ -> new HashMap<>())
                .merge(
                    attestation.getData().getIndex(),
                    attestation.getAggregationBits(),
                    AttestationUtil::nullableOr);
          }

          liveValidatorsAggregationBitsBySlotAndCommittee
              .computeIfAbsent(attestation.getData().getSlot(), __ -> new HashMap<>())
              .merge(
                  attestation.getData().getIndex(),
                  attestation.getAggregationBits(),
                  AttestationUtil::nullableOr);
        });

    final int numberOfCorrectValidators =
        correctValidatorsAggregationBitsBySlotAndCommittee.values().stream()
            .flatMap(aggregationBitsByCommittee -> aggregationBitsByCommittee.values().stream())
            .mapToInt(AttestationProcessingResult::getBitCount)
            .sum();

    final int numberOfLiveValidators =
        liveValidatorsAggregationBitsBySlotAndCommittee.values().stream()
            .flatMap(aggregationBitsByCommittee -> aggregationBitsByCommittee.values().stream())
            .mapToInt(AttestationProcessingResult::getBitCount)
            .sum();

    return new CorrectAndLiveValidators(numberOfCorrectValidators, numberOfLiveValidators);
  }

  private boolean isCorrectAttestation(
      final PendingAttestation attestation, final Bytes32 correctTargetRoot) {
    // Assuming that the correctTargetRoot is the root of the block at the attestation's slot
    return attestation.getData().getBeaconBlockRoot().equals(correctTargetRoot);
  }
}
