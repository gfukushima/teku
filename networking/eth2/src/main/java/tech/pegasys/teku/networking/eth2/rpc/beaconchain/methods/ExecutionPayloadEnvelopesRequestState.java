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

import java.util.List;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;

/**
 * The state of an execution payload envelopes request being served, shared by the by-range and
 * by-root handlers: the stream the envelopes are sent on and the amount of work the peer is charged
 * for. Subclasses add the cursor over what the request asked for, slots or block roots.
 */
abstract class ExecutionPayloadEnvelopesRequestState {

  private final ResponseCallback<SignedExecutionPayloadEnvelope> callback;
  private final long requestedCount;
  private long chargedCount = 0;

  protected ExecutionPayloadEnvelopesRequestState(
      final ResponseCallback<SignedExecutionPayloadEnvelope> callback, final long requestedCount) {
    this.callback = callback;
    this.requestedCount = requestedCount;
  }

  /**
   * Whether serving the request may need envelopes of finalized blocks, which are only retained
   * blinded and are unblinded through the execution layer.
   */
  abstract boolean needsUnblinding();

  /** Whether the peer has closed the stream, so that nothing sent would be received any more. */
  boolean isStreamClosed() {
    return callback.isClosed();
  }

  /** The number of envelopes the request asked for, charged to the peer up front. */
  long getRequestedCount() {
    return requestedCount;
  }

  /**
   * Charges the peer for work done on its behalf, whether or not an envelope came out of it: every
   * slot walked or root retrieved cost a lookup and, for a finalized block, an execution layer
   * round trip. The peer's rate limit is adjusted to the charged count once the request has been
   * served, so only what was never charged is refunded.
   */
  void charge(final long count) {
    chargedCount += count;
  }

  long getChargedCount() {
    return chargedCount;
  }

  SafeFuture<Void> sendExecutionPayloadEnvelope(
      final SignedExecutionPayloadEnvelope executionPayloadEnvelope) {
    return callback.respond(executionPayloadEnvelope);
  }

  /** Sends the envelopes one after the other, in the given order. */
  SafeFuture<Void> sendExecutionPayloadEnvelopes(
      final List<SignedExecutionPayloadEnvelope> executionPayloadEnvelopes) {
    SafeFuture<Void> sent = SafeFuture.COMPLETE;
    for (final SignedExecutionPayloadEnvelope executionPayloadEnvelope :
        executionPayloadEnvelopes) {
      sent = sent.thenCompose(__ -> sendExecutionPayloadEnvelope(executionPayloadEnvelope));
    }
    return sent;
  }
}
