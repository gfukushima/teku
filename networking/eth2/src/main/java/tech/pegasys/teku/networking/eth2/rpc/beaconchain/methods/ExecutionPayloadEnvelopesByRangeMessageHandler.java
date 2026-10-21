/*
 * Copyright (c) 2026 Consensys Incorporated. All rights reserved.
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

package tech.pegasys.teku.networking.eth2.rpc.beaconchain.methods;

import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ZERO;
import static tech.pegasys.teku.networking.eth2.rpc.core.RpcResponseStatus.INVALID_REQUEST_CODE;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback;
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException;
import tech.pegasys.teku.networking.p2p.rpc.StreamClosedException;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.config.SpecConfigGloas;
import tech.pegasys.teku.spec.datastructures.blocks.MinimalBeaconBlockSummary;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceNode;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.ExecutionPayloadEnvelopesByRangeRequestMessage;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;

/**
 * <a
 * href="https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/p2p-interface.md#executionpayloadenvelopesbyrange-v1">ExecutionPayloadEnvelopesByRange</a>
 */
public class ExecutionPayloadEnvelopesByRangeMessageHandler
    extends AbstractExecutionPayloadEnvelopesMessageHandler<
        ExecutionPayloadEnvelopesByRangeRequestMessage,
        ExecutionPayloadEnvelopesByRangeMessageHandler.RequestState> {

  private final SpecConfigGloas config;

  public ExecutionPayloadEnvelopesByRangeMessageHandler(
      final Spec spec,
      final SpecConfigGloas config,
      final MetricsSystem metricsSystem,
      final CombinedChainDataClient combinedChainDataClient,
      final ExecutionPayloadEnvelopesRequestLimiter requestLimiter) {
    this(
        spec,
        config,
        metricsSystem,
        combinedChainDataClient,
        requestLimiter,
        DEFAULT_UNBLINDING_BATCH_SIZE);
  }

  @VisibleForTesting
  ExecutionPayloadEnvelopesByRangeMessageHandler(
      final Spec spec,
      final SpecConfigGloas config,
      final MetricsSystem metricsSystem,
      final CombinedChainDataClient combinedChainDataClient,
      final ExecutionPayloadEnvelopesRequestLimiter requestLimiter,
      final int unblindingBatchSize) {
    super(
        spec,
        combinedChainDataClient,
        metricsSystem,
        requestLimiter,
        unblindingBatchSize,
        "by_range");
    this.config = config;
  }

  @Override
  public Optional<RpcException> validateRequest(
      final String protocolId, final ExecutionPayloadEnvelopesByRangeRequestMessage request) {
    try {
      request.getMaxSlot();
    } catch (final ArithmeticException __) {
      return Optional.of(
          new RpcException(INVALID_REQUEST_CODE, "Requested slot is too far in the future"));
    }

    if (request.getCount().isGreaterThan(config.getMaxRequestPayloads())) {
      requestCounter.labels("count_too_big").inc();
      return Optional.of(
          new RpcException(
              INVALID_REQUEST_CODE,
              String.format(
                  "Only a maximum of %s execution payload envelopes can be requested per request",
                  config.getMaxRequestPayloads())));
    }
    return Optional.empty();
  }

  @Override
  protected long getRequestedCount(final ExecutionPayloadEnvelopesByRangeRequestMessage message) {
    return message.getCount().longValue();
  }

  @Override
  protected RequestState createRequestState(
      final ExecutionPayloadEnvelopesByRangeRequestMessage message,
      final ResponseCallback<SignedExecutionPayloadEnvelope> callback) {
    final UInt64 startSlot = message.getStartSlot();
    final UInt64 count = message.getCount();

    // Trim the requested range down to the slots we are required to serve. Retrieving an envelope
    // for a finalized block requires reading the blinded envelope from the database and unblinding
    // it with an execution layer round trip, so slots outside the servable range are dropped before
    // any of that work is done. The count is reduced along with the start slot so that we never
    // respond with envelopes from outside the range the peer asked for.
    final UInt64 minServableSlot = spec.computeStartSlotAtEpoch(getMinServableEpoch());
    final UInt64 adjustedStartSlot = startSlot.max(minServableSlot);
    final UInt64 adjustedCount = count.minusMinZero(adjustedStartSlot.minusMinZero(startSlot));

    // Envelopes are only served for payloads that are part of the chain defined by the current
    // head: the fork choice node of the block on the head chain must be FULL. Slots from the
    // finalized block onwards are resolved on the head chain, which decides the payload status of
    // the finalized block itself. Slots before the finalized block are served from the finalized
    // database, which only retains envelopes of payloads the finalized chain built on.
    final NavigableMap<UInt64, ForkChoiceNode> hotNodes;

    final UInt64 finalizedBlockSlot = combinedChainDataClient.getFinalizedBlockSlot().orElse(ZERO);
    if (message.getMaxSlot().isLessThan(finalizedBlockSlot)) {
      // All requested slots are before the finalized block so skip scanning the protoarray
      hotNodes = new TreeMap<>();
    } else {
      hotNodes = combinedChainDataClient.getAncestorNodes(adjustedStartSlot, adjustedCount);
    }

    final UInt64 headSlot =
        hotNodes.isEmpty()
            ? combinedChainDataClient
                .getChainHead()
                .map(MinimalBeaconBlockSummary::getSlot)
                .orElse(ZERO)
            : hotNodes.lastKey();

    return new RequestState(
        callback, count.longValue(), adjustedStartSlot, adjustedCount, headSlot, hotNodes);
  }

  @Override
  protected SafeFuture<Void> serve(final RequestState requestState) {
    return sendFinalizedExecutionPayloadEnvelopes(requestState)
        .thenCompose(__ -> sendHotExecutionPayloadEnvelopes(requestState));
  }

  /**
   * Serves the slots below the head chain, which are read from the finalized database. Their
   * envelopes are only retained blinded and are unblinded through the execution layer, so the slots
   * are resolved to block roots first and the envelopes of a whole batch of blocks are then
   * retrieved with a single execution layer round trip. Each batch is sent before the next one is
   * retrieved and nothing more is retrieved once the peer has closed the stream.
   */
  private SafeFuture<Void> sendFinalizedExecutionPayloadEnvelopes(final RequestState requestState) {
    if (!requestState.hasFinalizedSlotsToWalk()) {
      return SafeFuture.COMPLETE;
    }
    if (requestState.isStreamClosed()) {
      return SafeFuture.failedFuture(new StreamClosedException());
    }
    final List<UInt64> slots = requestState.takeNextFinalizedSlots(unblindingBatchSize);
    return resolveFinalizedBlockRoots(slots)
        .thenCompose(
            blocks ->
                loadExecutionPayloadEnvelopes(
                    blocks,
                    SlotAndBlockRoot::getBlockRoot,
                    (block, executionPayload) ->
                        executionPayload.getSlot().equals(block.getSlot())))
        .thenCompose(requestState::sendExecutionPayloadEnvelopes)
        .thenCompose(__ -> sendFinalizedExecutionPayloadEnvelopes(requestState));
  }

  /** Resolves the block at each slot to its root, in slot order, skipping empty slots. */
  private SafeFuture<List<SlotAndBlockRoot>> resolveFinalizedBlockRoots(final List<UInt64> slots) {
    SafeFuture<List<SlotAndBlockRoot>> blockRoots = completedFuture(new ArrayList<>());
    for (final UInt64 slot : slots) {
      blockRoots =
          blockRoots.thenCompose(
              resolved ->
                  combinedChainDataClient
                      .getBlockAtSlotExact(slot)
                      .thenApply(
                          maybeBlock -> {
                            maybeBlock.ifPresent(
                                block -> resolved.add(new SlotAndBlockRoot(slot, block.getRoot())));
                            return resolved;
                          }));
    }
    return blockRoots;
  }

  /** Serves the slots on the head chain one at a time, from the hot store. */
  private SafeFuture<Void> sendHotExecutionPayloadEnvelopes(final RequestState requestState) {
    SafeFuture<Boolean> executionPayloadFuture =
        processNextHotExecutionPayloadEnvelope(requestState);
    // Avoid risk of StackOverflowException by iterating when the future is already complete, as
    // BeaconBlocksByRange does: thenCompose on a completed future would execute immediately and
    // recurse back into this method. When not already complete, thenCompose is executed on a
    // separate thread so doesn't recurse on the same stack.
    while (executionPayloadFuture.isDone() && !executionPayloadFuture.isCompletedExceptionally()) {
      if (executionPayloadFuture.join()) {
        return SafeFuture.COMPLETE;
      }
      executionPayloadFuture = processNextHotExecutionPayloadEnvelope(requestState);
    }
    return executionPayloadFuture.thenCompose(
        complete ->
            complete ? SafeFuture.COMPLETE : sendHotExecutionPayloadEnvelopes(requestState));
  }

  /** Serves the next slot on the head chain and returns true if the request is now complete. */
  private SafeFuture<Boolean> processNextHotExecutionPayloadEnvelope(
      final RequestState requestState) {
    if (!requestState.hasMoreSlotsToWalk()) {
      return completedFuture(true);
    }
    if (requestState.isStreamClosed()) {
      return SafeFuture.failedFuture(new StreamClosedException());
    }
    // Ensure execution payloads are loaded off of the event thread
    return loadHotExecutionPayloadEnvelope(requestState.getCurrentSlot(), requestState)
        .thenCompose(
            maybeExecutionPayload ->
                maybeExecutionPayload
                    .map(requestState::sendExecutionPayloadEnvelope)
                    .orElse(SafeFuture.COMPLETE))
        .thenApply(
            __ -> {
              requestState.onSlotWalked();
              return !requestState.hasMoreSlotsToWalk();
            });
  }

  private SafeFuture<Optional<SignedExecutionPayloadEnvelope>> loadHotExecutionPayloadEnvelope(
      final UInt64 slot, final RequestState requestState) {
    final Optional<ForkChoiceNode> maybeKnownNode = requestState.getKnownNode(slot);
    if (maybeKnownNode.isEmpty()) {
      // Unknown root above the finalized slots means this is an empty slot (or payload absent)
      return completedFuture(Optional.empty());
    }
    final ForkChoiceNode knownNode = maybeKnownNode.get();
    if (knownNode.payloadStatus() != PAYLOAD_STATUS_FULL) {
      // The payload is not part of the head chain: the next block built on the EMPTY variant, or
      // the head itself is EMPTY or PENDING because fork choice has not selected its payload. Its
      // envelope must not be served even if we hold it.
      return completedFuture(Optional.empty());
    }
    // Known node whose payload is part of the chain so lookup by root
    return combinedChainDataClient
        .getExecutionPayloadByBlockRoot(knownNode.blockRoot())
        .thenApply(
            maybeExecutionPayload ->
                maybeExecutionPayload.filter(
                    executionPayload -> executionPayload.getSlot().equals(slot)));
  }

  /** The cursor over the requested slots: those below the head chain first, then those on it. */
  static class RequestState extends ExecutionPayloadEnvelopesRequestState {

    private final UInt64 headSlot;
    private final NavigableMap<UInt64, ForkChoiceNode> knownNodes;
    private UInt64 currentSlot;
    private UInt64 remainingSlots;

    RequestState(
        final ResponseCallback<SignedExecutionPayloadEnvelope> callback,
        final long requestedCount,
        final UInt64 startSlot,
        final UInt64 count,
        final UInt64 headSlot,
        final NavigableMap<UInt64, ForkChoiceNode> knownNodes) {
      super(callback, requestedCount);
      this.currentSlot = startSlot;
      this.remainingSlots = count;
      this.headSlot = headSlot;
      this.knownNodes = knownNodes;
    }

    /** Only slots below the head chain need envelopes unblinded through the execution layer. */
    @Override
    boolean needsUnblinding() {
      return hasFinalizedSlotsToWalk();
    }

    UInt64 getCurrentSlot() {
      return currentSlot;
    }

    Optional<ForkChoiceNode> getKnownNode(final UInt64 slot) {
      return Optional.ofNullable(knownNodes.get(slot));
    }

    /** Whether the walk has further slots to serve, up to the requested count and our head. */
    boolean hasMoreSlotsToWalk() {
      return !remainingSlots.isZero() && currentSlot.isLessThanOrEqualTo(headSlot);
    }

    /**
     * Slots below the first node of the head chain are served from the finalized database. When no
     * node of the range is in fork choice every slot up to the head is.
     */
    private boolean isFinalizedSlot(final UInt64 slot) {
      return knownNodes.isEmpty() || slot.isLessThan(knownNodes.firstKey());
    }

    boolean hasFinalizedSlotsToWalk() {
      return hasMoreSlotsToWalk() && isFinalizedSlot(currentSlot);
    }

    /**
     * Takes up to {@code maxSlots} of the next finalized slots of the walk, marking them walked.
     */
    List<UInt64> takeNextFinalizedSlots(final int maxSlots) {
      final List<UInt64> slots = new ArrayList<>();
      while (slots.size() < maxSlots && hasFinalizedSlotsToWalk()) {
        slots.add(currentSlot);
        onSlotWalked();
      }
      return slots;
    }

    /** Moves past the current slot, charging the peer for it whether or not it had an envelope. */
    void onSlotWalked() {
      currentSlot = currentSlot.increment();
      remainingSlots = remainingSlots.minusMinZero(1);
      charge(1);
    }
  }
}
