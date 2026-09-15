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

package tech.pegasys.teku.statetransition.execution;

import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ProposerPreferences;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.gloas.BeaconStateGloas;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.BeaconStateAccessorsGloas;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.PredicatesGloas;
import tech.pegasys.teku.statetransition.validation.ExecutionPayloadBidGossipValidator;
import tech.pegasys.teku.storage.client.RecentChainData;

public class BuilderBidValidator {

  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final ProposerPreferencesManager proposerPreferencesManager;
  private final RecentChainData recentChainData;

  public BuilderBidValidator(
      final Spec spec,
      final ProposerPreferencesManager proposerPreferencesManager,
      final RecentChainData recentChainData) {
    this.spec = spec;
    this.proposerPreferencesManager = proposerPreferencesManager;
    this.recentChainData = recentChainData;
  }

  /**
   * Validates a bid coming from the Builder API
   *
   * <p><a
   * href="https://github.com/ethereum/builder-specs/blob/main/specs/gloas/validator.md#validating-a-signedexecutionpayloadbid">Validating
   * a SignedExecutionPayloadBid</a>
   *
   * @param signedBid the signed bid to validate
   * @param state the current beacon state
   * @param parentBlockHash the block hash of the parent the block is being built on
   * @param parentBlockRoot the block root of the parent the block is being built on
   * @return true if the bid is valid, false otherwise
   */
  public boolean validateBid(
      final SignedExecutionPayloadBid signedBid,
      final BeaconState state,
      final Bytes32 parentBlockHash,
      final Bytes32 parentBlockRoot) {
    final ExecutionPayloadBid bid = signedBid.getMessage();
    final UInt64 slot = bid.getSlot();
    final SpecVersion specVersion = spec.atSlot(slot);

    final PredicatesGloas predicates = PredicatesGloas.required(specVersion.predicates());
    final BeaconStateAccessorsGloas beaconStateAccessors =
        BeaconStateAccessorsGloas.required(specVersion.beaconStateAccessors());
    final BeaconStateGloas stateGloas = BeaconStateGloas.required(state);

    if (!predicates.isActiveBuilder(state, bid.getBuilderIndex())) {
      LOG.warn("Bid rejected: builder {} is not active", bid.getBuilderIndex());
      return false;
    }

    if (!slot.equals(state.getSlot())) {
      LOG.warn("Bid rejected: bid slot {} does not match state slot {}", slot, state.getSlot());
      return false;
    }

    if (!bid.getParentBlockHash().equals(stateGloas.getLatestExecutionPayloadBid().getBlockHash())
        && !bid.getParentBlockHash().equals(stateGloas.getLatestBlockHash())) {
      LOG.warn("Bid rejected: parent block hash does not extend a known parent");
      return false;
    }

    if (!bid.getParentBlockRoot().equals(state.getLatestBlockHeader().hashTreeRoot())) {
      LOG.warn("Bid rejected: parent block root mismatch");
      return false;
    }

    /*
     * The check above is the spec one, which allows a bid to extend either the FULL or the EMPTY
     * variant. After an empty slot those two diverge, so additionally require the bid to extend the
     * parent the block is actually being built on. Otherwise a high value bid on the other variant
     * could win selection and then fail process_execution_payload_bid.
     */
    if (!bid.getParentBlockHash().equals(parentBlockHash)
        || !bid.getParentBlockRoot().equals(parentBlockRoot)) {
      LOG.warn(
          "Bid rejected: bid parent (block hash {}, block root {}) does not match the parent the block is being built on (block hash {}, block root {})",
          bid.getParentBlockHash(),
          bid.getParentBlockRoot(),
          parentBlockHash,
          parentBlockRoot);
      return false;
    }

    if (!bid.getPrevRandao()
        .equals(
            beaconStateAccessors.getRandaoMix(
                state, beaconStateAccessors.getCurrentEpoch(state)))) {
      LOG.warn("Bid rejected: prev_randao mismatch");
      return false;
    }

    /*
     * The spec asserts the fee recipient and the gas limit unconditionally, so a bid cannot be
     * accepted without the preferences to check it against. Missing preferences for our own
     * proposal slot means they were never submitted, in which case accepting the bid would let a
     * builder choose the fee recipient.
     */
    final Optional<ProposerPreferences> maybeProposerPreferences =
        proposerPreferencesManager.getProposerPreferences(slot);
    if (maybeProposerPreferences.isEmpty()) {
      LOG.warn("Bid rejected: no proposer preferences available for slot {}", slot);
      return false;
    }
    final ProposerPreferences proposerPreferences = maybeProposerPreferences.get();

    if (!bid.getFeeRecipient().equals(proposerPreferences.getFeeRecipient())) {
      LOG.warn(
          "Bid rejected: fee recipient {} does not match proposer preferences fee recipient {}",
          bid.getFeeRecipient(),
          proposerPreferences.getFeeRecipient());
      return false;
    }

    final Optional<UInt64> maybeParentGasLimit =
        recentChainData.getExecutionGasLimitForBlockRootAndHash(
            bid.getParentBlockRoot(), bid.getParentBlockHash());
    if (maybeParentGasLimit.isEmpty()) {
      LOG.warn(
          "Bid rejected: parent execution payload gas limit is unavailable for parent block root {} and block hash {}",
          bid.getParentBlockRoot(),
          bid.getParentBlockHash());
      return false;
    }

    if (!ExecutionPayloadBidGossipValidator.isGasLimitTargetCompatible(
        maybeParentGasLimit.get(), bid.getGasLimit(), proposerPreferences.getTargetGasLimit())) {
      LOG.warn(
          "Bid rejected: gas limit {} is not compatible with parent gas limit {} and proposer preferences target gas limit {}",
          bid.getGasLimit(),
          maybeParentGasLimit.get(),
          proposerPreferences.getTargetGasLimit());
      return false;
    }

    if (bid.getValue().isGreaterThan(UInt64.ZERO)
        && !beaconStateAccessors.canBuilderCoverBid(state, bid.getBuilderIndex(), bid.getValue())) {
      LOG.warn("Bid rejected: builder {} cannot cover bid value", bid.getBuilderIndex());
      return false;
    }

    if (!specVersion
        .operationSignatureVerifier()
        .verifyExecutionPayloadBidSignature(
            state, signedBid, specVersion.getConfig().getBLSSignatureVerifier())) {
      LOG.warn("Bid rejected: invalid signature from builder {}", bid.getBuilderIndex());
      return false;
    }

    return true;
  }
}
