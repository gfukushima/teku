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

package tech.pegasys.teku.storage.server.pruner;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.Cancellable;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.SettableLabelledGauge;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.service.serviceutils.Service;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.storage.server.Database;
import tech.pegasys.teku.storage.server.ShuttingDownException;

public class StatePruner extends Service {
  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final Database database;
  private final AsyncRunner asyncRunner;
  private final Duration pruneInterval;
  private final long pruneLimit;
  private final SettableLabelledGauge pruningTimingsLabelledGauge;
  private final SettableLabelledGauge pruningActiveLabelledGauge;
  private final String pruningMetricsType;

  private Optional<Cancellable> scheduledPruner = Optional.empty();

  public StatePruner(
      final Spec spec,
      final Database database,
      final AsyncRunner asyncRunner,
      final Duration pruneInterval,
      final long pruneLimit,
      final String pruningMetricsType,
      final SettableLabelledGauge pruningTimingsLabelledGauge,
      final SettableLabelledGauge pruningActiveLabelledGauge) {
    this.spec = spec;
    this.database = database;
    this.asyncRunner = asyncRunner;
    this.pruneInterval = pruneInterval;
    this.pruningMetricsType = pruningMetricsType;
    this.pruningTimingsLabelledGauge = pruningTimingsLabelledGauge;
    this.pruningActiveLabelledGauge = pruningActiveLabelledGauge;
    this.pruneLimit = pruneLimit;
    LOG.info("Creating Pruner with prune interval of {} and limit of {}", pruneInterval, pruneLimit);
  }

  @Override
  protected synchronized SafeFuture<?> doStart() {
    scheduledPruner =
        Optional.of(
            asyncRunner.runWithFixedDelay(
                () -> {
                  pruningActiveLabelledGauge.set(1, pruningMetricsType);
                  final long start = System.currentTimeMillis();
                  pruneStates();
                  pruningTimingsLabelledGauge.set(
                      System.currentTimeMillis() - start, pruningMetricsType);
                  pruningActiveLabelledGauge.set(0, pruningMetricsType);
                },
                Duration.ZERO,
                pruneInterval,
                error -> LOG.error("Failed to prune old states", error)));
    return SafeFuture.COMPLETE;
  }

  @Override
  protected synchronized SafeFuture<?> doStop() {
    scheduledPruner.ifPresent(Cancellable::cancel);
    return SafeFuture.COMPLETE;
  }

  private void pruneStates() {
    final Optional<Checkpoint> finalizedCheckpoint = database.getFinalizedCheckpoint();
    if (finalizedCheckpoint.isEmpty()) {
      LOG.info("Not pruning as no finalized checkpoint is available.");
      return;
    }
    final UInt64 finalizedEpoch = finalizedCheckpoint.get().getEpoch();
    final UInt64 earliestEpochToKeep =
        finalizedEpoch.minusMinZero(spec.getNetworkingConfig().getMinEpochsForBlockRequests());
    final UInt64 earliestSlotToKeep = spec.computeStartSlotAtEpoch(earliestEpochToKeep);
    if (earliestSlotToKeep.isZero()) {
      LOG.info("Pruning is not performed as the epochs to retain include the genesis epoch.");
      return;
    }
    LOG.info("Initiating pruning of finalized states prior to slot {}.", earliestSlotToKeep);
    try {
      final UInt64 lastPrunedSlot =
          database.pruneFinalizedStates(earliestSlotToKeep.decrement(), pruneLimit);
      LOG.info(
          "Pruned {} finalized states prior to slot {}, last pruned slot was {}.",
          pruneLimit,
          earliestSlotToKeep,
          lastPrunedSlot);
    } catch (final ShuttingDownException | RejectedExecutionException ex) {
      LOG.debug("Shutting down", ex);
    }
  }
}
