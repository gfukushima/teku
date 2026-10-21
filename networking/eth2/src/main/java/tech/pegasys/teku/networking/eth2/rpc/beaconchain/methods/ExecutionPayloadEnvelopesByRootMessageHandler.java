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

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.peers.Eth2Peer;
import tech.pegasys.teku.networking.eth2.peers.RequestKey;
import tech.pegasys.teku.networking.eth2.rpc.core.PeerRequiredLocalMessageHandler;
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback;
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException.ResourceUnavailableException;
import tech.pegasys.teku.networking.p2p.rpc.StreamClosedException;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.ExecutionPayloadEnvelopesByRootRequestMessage;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;

/**
 * <a
 * href="https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/p2p-interface.md#executionpayloadenvelopesbyroot-v1">ExecutionPayloadEnvelopesByRoot</a>
 *
 * <p>Count validation is done as part of {@link ExecutionPayloadEnvelopesByRootRequestMessage}
 * schema limits
 */
public class ExecutionPayloadEnvelopesByRootMessageHandler
    extends PeerRequiredLocalMessageHandler<
        ExecutionPayloadEnvelopesByRootRequestMessage, SignedExecutionPayloadEnvelope> {

  private static final Logger LOG = LogManager.getLogger();

  /** Number of roots whose envelopes are unblinded with a single execution layer round trip. */
  static final int DEFAULT_UNBLINDING_BATCH_SIZE = 32;

  private final Spec spec;
  private final CombinedChainDataClient combinedChainDataClient;
  private final LabelledMetric<Counter> requestCounter;
  private final Counter totalExecutionPayloadEnvelopesRequestedCounter;
  private final ExecutionPayloadEnvelopesRequestLimiter requestLimiter;
  private final int unblindingBatchSize;

  public ExecutionPayloadEnvelopesByRootMessageHandler(
      final Spec spec,
      final CombinedChainDataClient combinedChainDataClient,
      final MetricsSystem metricsSystem,
      final ExecutionPayloadEnvelopesRequestLimiter requestLimiter) {
    this(
        spec,
        combinedChainDataClient,
        metricsSystem,
        requestLimiter,
        DEFAULT_UNBLINDING_BATCH_SIZE);
  }

  @VisibleForTesting
  ExecutionPayloadEnvelopesByRootMessageHandler(
      final Spec spec,
      final CombinedChainDataClient combinedChainDataClient,
      final MetricsSystem metricsSystem,
      final ExecutionPayloadEnvelopesRequestLimiter requestLimiter,
      final int unblindingBatchSize) {
    this.spec = spec;
    this.combinedChainDataClient = combinedChainDataClient;
    this.requestLimiter = requestLimiter;
    this.unblindingBatchSize = unblindingBatchSize;
    requestCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.NETWORK,
            "rpc_execution_payload_envelopes_by_root_requests_total",
            "Total number of execution payload envelopes by root requests received",
            "status");
    totalExecutionPayloadEnvelopesRequestedCounter =
        metricsSystem.createCounter(
            TekuMetricCategory.NETWORK,
            "rpc_execution_payload_envelopes_by_root_requested_envelopes_total",
            "Total number of execution payload envelopes requested in accepted execution payload envelopes by root requests from peers");
  }

  @Override
  public void onIncomingMessage(
      final String protocolId,
      final Eth2Peer peer,
      final ExecutionPayloadEnvelopesByRootRequestMessage message,
      final ResponseCallback<SignedExecutionPayloadEnvelope> callback) {
    LOG.trace(
        "Peer {} requested {} execution payload envelopes with roots: {}",
        peer.getId(),
        message.size(),
        message);

    final Optional<RequestKey> maybeRequestKey =
        peer.approveExecutionPayloadEnvelopesRequest(callback, message.size());

    if (!peer.approveRequest() || maybeRequestKey.isEmpty()) {
      requestCounter.labels("rate_limited").inc();
      return;
    }

    // Any of the roots may be that of a finalized block, whose envelope is only retained blinded
    // and must be unblinded through the execution layer, so only a bounded number of requests is
    // served at a time.
    if (!requestLimiter.tryAcquire()) {
      requestCounter.labels("concurrency_limited").inc();
      callback.completeWithErrorResponse(
          new ResourceUnavailableException(
              "Too many execution payload envelope requests are being served"));
      return;
    }

    requestCounter.labels("ok").inc();
    totalExecutionPayloadEnvelopesRequestedCounter.inc(message.size());

    final UInt64 currentEpoch =
        combinedChainDataClient.getRecentChainData().getCurrentEpoch().orElse(UInt64.ZERO);
    final UInt64 minServableEpoch =
        currentEpoch
            .minusMinZero(spec.getNetworkingConfig().getMinEpochsForBlockRequests())
            .max(spec.getSpecConfig(currentEpoch).getGloasForkEpoch());

    final List<Bytes32> blockRoots = new ArrayList<>();
    for (final SszBytes32 beaconBlockRoot : message) {
      blockRoots.add(beaconBlockRoot.get());
    }
    final RequestState requestState = new RequestState(callback, blockRoots, minServableEpoch);

    sendNextBatch(requestState)
        .alwaysRun(requestLimiter::release)
        .finish(
            () -> {
              // The peer is charged for every root whose envelope was retrieved rather than for
              // the envelopes sent: each retrieval cost a database and, for a finalized block, an
              // execution layer lookup, whether or not an envelope came out of it. Only roots
              // dropped before retrieval are refunded.
              if (requestState.getRetrievedBlockRoots() != message.size()) {
                peer.adjustExecutionPayloadEnvelopesRequest(
                    maybeRequestKey.get(), requestState.getRetrievedBlockRoots());
              }
              callback.completeSuccessfully();
            },
            err -> handleError(err, callback, "execution payload envelopes by root"));
  }

  /**
   * Serves the next batch of requested roots: the roots are checked against the servable range
   * first, the envelopes of the whole batch are then retrieved with a single execution layer round
   * trip and sent in request order. Nothing more is retrieved once the peer has closed the stream.
   */
  private SafeFuture<Void> sendNextBatch(final RequestState requestState) {
    if (!requestState.hasMoreBlockRoots()) {
      return SafeFuture.COMPLETE;
    }
    if (requestState.isStreamClosed()) {
      return SafeFuture.failedFuture(new StreamClosedException());
    }
    final List<Bytes32> blockRoots = requestState.takeNextBlockRoots(unblindingBatchSize);
    return selectRetrievableBlockRoots(blockRoots, requestState.getMinServableEpoch())
        .thenCompose(
            retrievableBlockRoots -> {
              requestState.onBlockRootsRetrieved(retrievableBlockRoots.size());
              return loadExecutionPayloadEnvelopes(
                  retrievableBlockRoots, requestState.getMinServableEpoch());
            })
        .thenCompose(requestState::sendExecutionPayloadEnvelopes)
        .thenCompose(__ -> sendNextBatch(requestState));
  }

  /**
   * Resolves the slot of each root before retrieving its envelope. Retrieving an envelope for a
   * finalized block requires reading the blinded envelope from the database and unblinding it with
   * an execution layer round trip, so a root already known to be outside the servable range is
   * dropped up front rather than after that work has been done. A root whose slot can't be resolved
   * still goes through retrieval, where the range is enforced against the envelope itself.
   */
  private SafeFuture<List<Bytes32>> selectRetrievableBlockRoots(
      final List<Bytes32> blockRoots, final UInt64 minServableEpoch) {
    SafeFuture<List<Bytes32>> retrievableBlockRoots = completedFuture(new ArrayList<>());
    for (final Bytes32 blockRoot : blockRoots) {
      retrievableBlockRoots =
          retrievableBlockRoots.thenCompose(
              retrievable ->
                  combinedChainDataClient
                      .getSlotByBlockRoot(blockRoot)
                      .thenApply(
                          maybeSlot -> {
                            if (maybeSlot.isEmpty()
                                || isWithinServableRange(maybeSlot.get(), minServableEpoch)) {
                              retrievable.add(blockRoot);
                            }
                            return retrievable;
                          }));
    }
    return retrievableBlockRoots;
  }

  /**
   * Retrieves the envelopes of all the roots with one lookup and returns them in request order,
   * leaving out any envelope outside the servable range.
   */
  private SafeFuture<List<SignedExecutionPayloadEnvelope>> loadExecutionPayloadEnvelopes(
      final List<Bytes32> blockRoots, final UInt64 minServableEpoch) {
    if (blockRoots.isEmpty()) {
      return completedFuture(List.of());
    }
    return combinedChainDataClient
        .getExecutionPayloadsByBlockRoots(new HashSet<>(blockRoots))
        .thenApply(
            executionPayloads ->
                blockRoots.stream()
                    .map(
                        blockRoot ->
                            Optional.ofNullable(executionPayloads.get(blockRoot))
                                .filter(
                                    envelope ->
                                        isWithinServableRange(
                                            envelope.getMessage().getSlot(), minServableEpoch)))
                    .flatMap(Optional::stream)
                    .toList());
  }

  private boolean isWithinServableRange(final UInt64 slot, final UInt64 minServableEpoch) {
    return spec.computeEpochAtSlot(slot).isGreaterThanOrEqualTo(minServableEpoch);
  }

  private static class RequestState {

    private final ResponseCallback<SignedExecutionPayloadEnvelope> callback;
    private final List<Bytes32> blockRoots;
    private final UInt64 minServableEpoch;
    private int nextBlockRootIndex = 0;
    private long retrievedBlockRoots = 0;

    RequestState(
        final ResponseCallback<SignedExecutionPayloadEnvelope> callback,
        final List<Bytes32> blockRoots,
        final UInt64 minServableEpoch) {
      this.callback = callback;
      this.blockRoots = blockRoots;
      this.minServableEpoch = minServableEpoch;
    }

    UInt64 getMinServableEpoch() {
      return minServableEpoch;
    }

    boolean hasMoreBlockRoots() {
      return nextBlockRootIndex < blockRoots.size();
    }

    boolean isStreamClosed() {
      return callback.isClosed();
    }

    long getRetrievedBlockRoots() {
      return retrievedBlockRoots;
    }

    /** Takes up to {@code maxBlockRoots} of the next requested roots, in request order. */
    List<Bytes32> takeNextBlockRoots(final int maxBlockRoots) {
      final int endIndex = Math.min(nextBlockRootIndex + maxBlockRoots, blockRoots.size());
      final List<Bytes32> batch = List.copyOf(blockRoots.subList(nextBlockRootIndex, endIndex));
      nextBlockRootIndex = endIndex;
      return batch;
    }

    void onBlockRootsRetrieved(final int count) {
      retrievedBlockRoots += count;
    }

    SafeFuture<Void> sendExecutionPayloadEnvelopes(
        final List<SignedExecutionPayloadEnvelope> executionPayloadEnvelopes) {
      SafeFuture<Void> sent = SafeFuture.COMPLETE;
      for (final SignedExecutionPayloadEnvelope executionPayloadEnvelope :
          executionPayloadEnvelopes) {
        sent = sent.thenCompose(__ -> callback.respond(executionPayloadEnvelope));
      }
      return sent;
    }
  }
}
