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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many execution payload envelope requests may be served at the same time when serving
 * them can involve the execution layer. Envelopes of finalized blocks are only retained blinded and
 * are unblinded with an {@code engine_getPayloadBodiesByHash} round trip, so without a cap peers
 * could pile up unbounded engine work by opening many requests at once. Requests over the cap are
 * rejected rather than queued: the peer is told the resource is unavailable and can retry later or
 * elsewhere.
 *
 * <p>Shared by the by-range and by-root handlers so that the cap covers both request types.
 */
public class ExecutionPayloadEnvelopesRequestLimiter {

  private final int maxConcurrentRequests;
  private final AtomicInteger activeRequests = new AtomicInteger(0);

  public ExecutionPayloadEnvelopesRequestLimiter(final int maxConcurrentRequests) {
    this.maxConcurrentRequests = maxConcurrentRequests;
  }

  /**
   * Admits a request if the cap has not been reached. Every successful acquisition must be followed
   * by exactly one {@link #release()} once the request has been served or has failed.
   *
   * @return true if the request was admitted
   */
  public boolean tryAcquire() {
    while (true) {
      final int current = activeRequests.get();
      if (current >= maxConcurrentRequests) {
        return false;
      }
      if (activeRequests.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  public void release() {
    // Never let an unmatched release raise the cap
    activeRequests.updateAndGet(current -> Math.max(current - 1, 0));
  }
}
