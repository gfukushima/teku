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

package tech.pegasys.teku.networking.p2p.libp2p.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.assertThatSafeFuture;

import io.libp2p.core.P2PChannel;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.networking.p2p.peer.NodeId;
import tech.pegasys.teku.networking.p2p.rpc.StreamClosedException;

class LibP2PRpcStreamTest {

  private static final int HIGH_WATER_MARK = 64;
  private static final int LOW_WATER_MARK = 16;

  private final NodeId nodeId = mock(NodeId.class);
  private final P2PChannel p2pChannel = mock(P2PChannel.class);

  private EmbeddedChannel channel;
  private ChannelHandlerContext ctx;
  private LibP2PRpcStream stream;

  @BeforeEach
  void setUp() {
    channel = new EmbeddedChannel();
    channel
        .config()
        .setWriteBufferWaterMark(new WriteBufferWaterMark(LOW_WATER_MARK, HIGH_WATER_MARK));
    final AtomicReference<LibP2PRpcStream> streamRef = new AtomicReference<>();
    channel
        .pipeline()
        .addLast(
            "rpc",
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelWritabilityChanged(final ChannelHandlerContext c) {
                final LibP2PRpcStream s = streamRef.get();
                if (s != null) {
                  s.onWritabilityChanged();
                }
                c.fireChannelWritabilityChanged();
              }
            });
    ctx = channel.pipeline().context("rpc");
    stream = new LibP2PRpcStream(nodeId, p2pChannel, ctx);
    streamRef.set(stream);
  }

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void writeBytes_completesImmediatelyWhenChannelIsWritable() {
    final SafeFuture<Void> result = unchecked(() -> stream.writeBytes(Bytes.of(1, 2, 3)));
    channel.runPendingTasks();
    channel.flushOutbound();
    drainOutbound();

    assertThatSafeFuture(result).isCompleted();
    assertThat(channel.isWritable()).isTrue();
  }

  @Test
  void writeBytes_throwsWhenStreamAlreadyClosed() {
    final SafeFuture<Void> ignored = stream.closeWriteStream();

    assertThatThrownBy(() -> stream.writeBytes(Bytes.of(1, 2, 3)))
        .isInstanceOf(StreamClosedException.class);
  }

  @Test
  void writeBytes_defersUntilChannelBecomesWritableAgain() {
    // Push the outbound buffer above the high water mark so isWritable() flips to false.
    final ByteBuf filler = channel.alloc().buffer(HIGH_WATER_MARK + 1);
    filler.writeZero(HIGH_WATER_MARK + 1);
    final var unusedChannelFuture = channel.write(filler);
    channel.runPendingTasks();
    assertThat(channel.isWritable()).isFalse();

    final SafeFuture<Void> deferred = unchecked(() -> stream.writeBytes(Bytes.of(9, 9, 9)));
    channel.runPendingTasks();
    assertThatSafeFuture(deferred).isNotDone();

    // Drain the outbound to flip writability back to true.
    channel.flushOutbound();
    drainOutbound();
    channel.runPendingTasks();

    assertThat(channel.isWritable()).isTrue();
    channel.flushOutbound();
    drainOutbound();
    assertThatSafeFuture(deferred).isCompleted();
  }

  @Test
  void closeWriteStream_failsPendingWaiterWithStreamClosedException() {
    final ByteBuf filler = channel.alloc().buffer(HIGH_WATER_MARK + 1);
    filler.writeZero(HIGH_WATER_MARK + 1);
    final var unusedChannelFuture = channel.write(filler);
    channel.runPendingTasks();
    assertThat(channel.isWritable()).isFalse();

    final SafeFuture<Void> deferred = unchecked(() -> stream.writeBytes(Bytes.of(1)));
    channel.runPendingTasks();
    assertThatSafeFuture(deferred).isNotDone();

    final SafeFuture<Void> ignored = stream.closeWriteStream();
    channel.runPendingTasks();

    assertThatSafeFuture(deferred).isCompletedExceptionallyWith(StreamClosedException.class);
  }

  private void drainOutbound() {
    Object discarded;
    while ((discarded = channel.readOutbound()) != null) {
      if (discarded instanceof ByteBuf buf) {
        buf.release();
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private static <T> T unchecked(final ThrowingSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }
}
