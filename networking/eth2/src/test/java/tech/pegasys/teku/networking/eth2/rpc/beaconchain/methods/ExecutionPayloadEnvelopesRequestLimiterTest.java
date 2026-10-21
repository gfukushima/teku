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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecutionPayloadEnvelopesRequestLimiterTest {

  private final ExecutionPayloadEnvelopesRequestLimiter limiter =
      new ExecutionPayloadEnvelopesRequestLimiter(2);

  @Test
  void shouldAdmitRequestsUpToTheLimit() {
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();

    assertThat(limiter.tryAcquire()).isFalse();
  }

  @Test
  void shouldAdmitAnotherRequestOnceOneIsReleased() {
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();

    limiter.release();

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isFalse();
  }

  @Test
  void shouldNotAdmitMoreThanTheLimitAfterSpuriousRelease() {
    // A release without a matching acquisition must not raise the limit
    limiter.release();

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isFalse();
  }
}
