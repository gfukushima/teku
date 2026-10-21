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
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.peers.Eth2Peer;
import tech.pegasys.teku.networking.eth2.peers.RequestKey;
import tech.pegasys.teku.networking.eth2.rpc.core.PeerRequiredLocalMessageHandler;
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback;
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException;
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException.ResourceUnavailableException;
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
    extends PeerRequiredLocalMessageHandler<
        ExecutionPayloadEnvelopesByRangeRequestMessage, SignedExecutionPayloadEnvelope> {

  private static final Logger LOG = LogManager.getLogger();

  /**
   * Number of finalized slots whose envelopes are unblinded with a single execution layer round
   * trip.
   */
  static final int DEFAULT_UNBLINDING_BATCH_SIZE = 32;

  private final Spec spec;
  private final SpecConfigGloas config;
  private final LabelledMetric<Counter> requestCounter;
  private final CombinedChainDataClient combinedChainDataClient;
  private final Counter totalExecutionPayloadEnvelopesRequestedCounter;
  private final ExecutionPayloadEnvelopesRequestLimiter requestLimiter;
  private final int unblindingBatchSize;

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
    this.spec = spec;
    this.config = config;
    this.combinedChainDataClient = combinedChainDataClient;
    this.requestLimiter = requestLimiter;
    this.unblindingBatchSize = unblindingBatchSize;
    requestCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.NETWORK,
            "rpc_execution_payload_envelopes_by_range_requests_total",
            "Total number of execution payload envelopes by range requests received",
            "status");
    totalExecutionPayloadEnvelopesRequestedCounter =
        metricsSystem.createCounter(
            TekuMetricCategory.NETWORK,
            "rpc_execution_payload_envelopes_by_range_requested_envelopes_total",
            "Total number of execution payload envelopes requested in accepted execution payload envelopes by range requests from peers");
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
  public void onIncomingMessage(
      final String protocolId,
      final Eth2Peer peer,
      final ExecutionPayloadEnvelopesByRangeRequestMessage message,
      final ResponseCallback<SignedExecutionPayloadEnvelope> callback) {
    final UInt64 startSlot = message.getStartSlot();
    final UInt64 count = message.getCount();
    LOG.trace(
        "Peer {} requested {} execution payload envelopes starting at slot {}",
        peer.getId(),
        count,
        startSlot);

    final Optional<RequestKey> maybeRequestKey =
        peer.approveExecutionPayloadEnvelopesRequest(callback, count.longValue());

    if (!peer.approveRequest() || maybeRequestKey.isEmpty()) {
      requestCounter.labels("rate_limited").inc();
      return;
    }

    // Trim the requested range down to the slots we are required to serve. Retrieving an envelope
    // for a finalized block requires reading the blinded envelope from the database and unblinding
    // it with an execution layer round trip, so slots outside the servable range are dropped before
    // any of that work is done. The count is reduced along with the start slot so that we never
    // respond with envelopes from outside the range the peer asked for.
    final UInt64 minServableSlot = getMinServableSlot();
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

    final RequestState requestState =
        new RequestState(callback, adjustedStartSlot, adjustedCount, headSlot, hotNodes);

    // Envelopes of finalized blocks are unblinded through the execution layer, so only a bounded
    // number of requests needing that is served at a time. Requests served entirely from the head
    // chain are cheap and are never held back by that cap.
    final boolean needsUnblinding = requestState.hasFinalizedSlotsToWalk();
    if (needsUnblinding && !requestLimiter.tryAcquire()) {
      requestCounter.labels("concurrency_limited").inc();
      callback.completeWithErrorResponse(
          new ResourceUnavailableException(
              "Too many execution payload envelope requests are being served"));
      return;
    }
    requestCounter.labels("ok").inc();
    totalExecutionPayloadEnvelopesRequestedCounter.inc(count.longValue());

    SafeFuture<RequestState> response =
        sendFinalizedExecutionPayloadEnvelopes(requestState)
            .thenCompose(this::sendHotExecutionPayloadEnvelopes);
    if (needsUnblinding) {
      response = response.alwaysRun(requestLimiter::release);
    }

    response.finish(
        __ -> {
          // The peer is charged for every slot walked rather than for the envelopes sent: each
          // walked slot cost a lookup and, below the head chain, an execution layer round trip,
          // whether or not an envelope came out of it. Only slots never walked are refunded.
          if (requestState.getWalkedSlots() != count.longValue()) {
            peer.adjustExecutionPayloadEnvelopesRequest(
                maybeRequestKey.get(), requestState.getWalkedSlots());
          }
          callback.completeSuccessfully();
        },
        err -> handleError(err, callback, "execution payload envelopes by range"));
  }

  /**
   * The first slot we are required to serve envelopes for: the start of the earliest epoch within
   * {@code MIN_EPOCHS_FOR_BLOCK_REQUESTS} of the current epoch, never earlier than the Gloas fork.
   */
  private UInt64 getMinServableSlot() {
    final UInt64 currentEpoch =
        combinedChainDataClient.getRecentChainData().getCurrentEpoch().orElse(ZERO);
    final UInt64 minServableEpoch =
        currentEpoch
            .minusMinZero(spec.getNetworkingConfig().getMinEpochsForBlockRequests())
            .max(spec.getSpecConfig(currentEpoch).getGloasForkEpoch());
    return spec.computeStartSlotAtEpoch(minServableEpoch);
  }

  /**
   * Serves the slots below the head chain, which are read from the finalized database. Their
   * envelopes are only retained blinded and are unblinded through the execution layer, so the slots
   * are resolved to block roots first and the envelopes of a whole batch of blocks are then
   * retrieved with a single execution layer round trip. Each batch is sent before the next one is
   * retrieved and nothing more is retrieved once the peer has closed the stream.
   */
  private SafeFuture<RequestState> sendFinalizedExecutionPayloadEnvelopes(
      final RequestState requestState) {
    if (!requestState.hasFinalizedSlotsToWalk()) {
      return completedFuture(requestState);
    }
    if (requestState.isStreamClosed()) {
      return SafeFuture.failedFuture(new StreamClosedException());
    }
    final List<UInt64> slots = requestState.takeNextFinalizedSlots(unblindingBatchSize);
    return resolveFinalizedBlockRoots(slots)
        .thenCompose(this::loadFinalizedExecutionPayloadEnvelopes)
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

  /**
   * Retrieves the envelopes of all the blocks with one lookup and returns them in slot order,
   * leaving out any envelope that is not for the slot it was requested for.
   */
  private SafeFuture<List<SignedExecutionPayloadEnvelope>> loadFinalizedExecutionPayloadEnvelopes(
      final List<SlotAndBlockRoot> blocks) {
    if (blocks.isEmpty()) {
      return completedFuture(List.of());
    }
    final Set<Bytes32> blockRoots =
        blocks.stream().map(SlotAndBlockRoot::getBlockRoot).collect(Collectors.toSet());
    return combinedChainDataClient
        .getExecutionPayloadsByBlockRoots(blockRoots)
        .thenApply(
            executionPayloads ->
                blocks.stream()
                    .map(
                        block ->
                            Optional.ofNullable(executionPayloads.get(block.getBlockRoot()))
                                .filter(
                                    executionPayload ->
                                        executionPayload.getSlot().equals(block.getSlot())))
                    .flatMap(Optional::stream)
                    .toList());
  }

  /** Serves the slots on the head chain one at a time, from the hot store. */
  private SafeFuture<RequestState> sendHotExecutionPayloadEnvelopes(
      final RequestState requestState) {
    SafeFuture<Boolean> executionPayloadFuture =
        processNextHotExecutionPayloadEnvelope(requestState);
    // Avoid risk of StackOverflowException by iterating when the future is already complete, as
    // BeaconBlocksByRange does: thenCompose on a completed future would execute immediately and
    // recurse back into this method. When not already complete, thenCompose is executed on a
    // separate thread so doesn't recurse on the same stack.
    while (executionPayloadFuture.isDone() && !executionPayloadFuture.isCompletedExceptionally()) {
      if (executionPayloadFuture.join()) {
        return completedFuture(requestState);
      }
      executionPayloadFuture = processNextHotExecutionPayloadEnvelope(requestState);
    }
    return executionPayloadFuture.thenCompose(
        complete ->
            complete
                ? completedFuture(requestState)
                : sendHotExecutionPayloadEnvelopes(requestState));
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
    return requestState
        .loadNextHotExecutionPayloadEnvelope()
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

  @VisibleForTesting
  class RequestState {

    private final ResponseCallback<SignedExecutionPayloadEnvelope> callback;
    private final UInt64 headSlot;
    private final NavigableMap<UInt64, ForkChoiceNode> knownNodes;
    private UInt64 currentSlot;
    private UInt64 remainingSlots;
    private long walkedSlots = 0;

    RequestState(
        final ResponseCallback<SignedExecutionPayloadEnvelope> callback,
        final UInt64 startSlot,
        final UInt64 count,
        final UInt64 headSlot,
        final NavigableMap<UInt64, ForkChoiceNode> knownNodes) {
      this.callback = callback;
      this.currentSlot = startSlot;
      this.remainingSlots = count;
      this.headSlot = headSlot;
      this.knownNodes = knownNodes;
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

    boolean isStreamClosed() {
      return callback.isClosed();
    }

    long getWalkedSlots() {
      return walkedSlots;
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

    void onSlotWalked() {
      currentSlot = currentSlot.increment();
      remainingSlots = remainingSlots.minusMinZero(1);
      walkedSlots++;
    }

    SafeFuture<Void> sendExecutionPayloadEnvelope(
        final SignedExecutionPayloadEnvelope executionPayloadEnvelope) {
      return callback.respond(executionPayloadEnvelope);
    }

    SafeFuture<Void> sendExecutionPayloadEnvelopes(
        final List<SignedExecutionPayloadEnvelope> executionPayloadEnvelopes) {
      SafeFuture<Void> sent = SafeFuture.COMPLETE;
      for (final SignedExecutionPayloadEnvelope executionPayloadEnvelope :
          executionPayloadEnvelopes) {
        sent = sent.thenCompose(__ -> sendExecutionPayloadEnvelope(executionPayloadEnvelope));
      }
      return sent;
    }

    SafeFuture<Optional<SignedExecutionPayloadEnvelope>> loadNextHotExecutionPayloadEnvelope() {
      final UInt64 slot = this.currentSlot;
      final ForkChoiceNode knownNode = knownNodes.get(slot);
      if (knownNode == null) {
        // Unknown root above the finalized slots means this is an empty slot (or payload absent)
        return completedFuture(Optional.empty());
      }
      if (knownNode.payloadStatus() != PAYLOAD_STATUS_FULL) {
        // The payload is not part of the head chain: the next block built on the EMPTY variant,
        // or the head itself is EMPTY or PENDING because fork choice has not selected its
        // payload. Its envelope must not be served even if we hold it.
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
  }
}
