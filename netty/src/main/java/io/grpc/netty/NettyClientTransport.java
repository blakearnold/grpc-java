/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.netty;

import static io.grpc.internal.GrpcUtil.AUTHORITY_KEY;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;

import com.google.common.base.Preconditions;
import com.google.common.base.Ticker;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

import java.net.SocketAddress;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.GuardedBy;

/**
 * A Netty-based {@link ClientTransport} implementation.
 */
class NettyClientTransport implements ClientTransport {
  private final SocketAddress address;
  private final Class<? extends Channel> channelType;
  private final EventLoopGroup group;
  private final ProtocolNegotiator negotiator;
  private final AsciiString authority;
  private final int flowControlWindow;
  private final int maxMessageSize;
  private final int maxHeaderListSize;
  private ProtocolNegotiator.Handler negotiationHandler;
  private NettyClientHandler handler;
  // We should not send on the channel until negotiation completes. This is a hard requirement
  // by SslHandler but is appropriate for HTTP/1.1 Upgrade as well.
  private Channel channel;
  private Listener listener;
  /** Whether the transport started shutting down. */
  @GuardedBy("this")
  private boolean shutdown;
  /** Whether the transport completed shutting down. */
  @GuardedBy("this")
  private boolean terminated;

  NettyClientTransport(SocketAddress address, Class<? extends Channel> channelType,
                       EventLoopGroup group, ProtocolNegotiator negotiator,
                       int flowControlWindow, int maxMessageSize, int maxHeaderListSize,
                       String authority) {
    this.negotiator = Preconditions.checkNotNull(negotiator, "negotiator");
    this.address = Preconditions.checkNotNull(address, "address");
    this.group = Preconditions.checkNotNull(group, "group");
    this.channelType = Preconditions.checkNotNull(channelType, "channelType");
    this.flowControlWindow = flowControlWindow;
    this.maxMessageSize = maxMessageSize;
    this.maxHeaderListSize = maxHeaderListSize;
    this.authority = new AsciiString(authority);
  }

  @Override
  public void ping(PingCallback callback, Executor executor) {
    // Write the command requesting the ping
    handler.getWriteQueue().enqueue(new SendPingCommand(callback, executor), true);
  }

  @Override
  public ClientStream newStream(final MethodDescriptor<?, ?> method, final Metadata headers) {
    Preconditions.checkNotNull(method, "method");
    Preconditions.checkNotNull(headers, "headers");

    // Convert the headers into Netty HTTP/2 headers.
    AsciiString defaultPath = new AsciiString("/" + method.getFullMethodName());
    AsciiString defaultAuthority = new AsciiString(headers.containsKey(AUTHORITY_KEY)
        ? headers.get(AUTHORITY_KEY) : authority);
    headers.removeAll(AUTHORITY_KEY);
    final Http2Headers http2Headers = Utils.convertClientHeaders(
        headers, negotiationHandler.scheme(), defaultPath, defaultAuthority);

    class StartCallback implements Runnable {
      final NettyClientStream clientStream =
          new NettyClientStream(channel, handler, this, maxMessageSize);

      final ChannelFutureListener failureListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
          if (!future.isSuccess()) {
            // Stream creation failed. Close the stream if not already closed.
            clientStream.transportReportStatus(Utils.statusFromThrowable(future.cause()), true,
                new Metadata());
          }
        }
      };

      @Override
      public void run() {
        // Write the command requesting the creation of the stream.
        handler.getWriteQueue().enqueue(new CreateStreamCommand(http2Headers, clientStream),
            !method.getType().clientSendsOneMessage()).addListener(failureListener);
      }
    }

    return new StartCallback().clientStream;
  }

  @Override
  public void start(Listener transportListener) {
    listener = Preconditions.checkNotNull(transportListener, "listener");

    handler = newHandler();
    negotiationHandler = negotiator.newHandler(handler);

    Bootstrap b = new Bootstrap();
    b.group(group);
    b.channel(channelType);
    if (NioSocketChannel.class.isAssignableFrom(channelType)) {
      b.option(SO_KEEPALIVE, true);
    }
    /**
     * We don't use a ChannelInitializer in the client bootstrap because its "initChannel" method
     * is executed in the event loop and we need this handler to be in the pipeline immediately so
     * that it may begin buffering writes.
     */
    b.handler(negotiationHandler);
    // Start the connection operation to the server.
    channel = b.connect(address).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          ChannelHandlerContext ctx = channel.pipeline().context(handler);
          if (ctx != null) {
            // NettyClientHandler doesn't propagate exceptions, but the negotiator will need the
            // exception to fail any writes. Note that this fires after handler, because it is as if
            // handler was propagating the notification.
            ctx.fireExceptionCaught(future.cause());
          }
          channel.pipeline().fireExceptionCaught(future.cause());
        }
      }
    }).channel();
    // Start the write queue as soon as the channel is constructed
    handler.startWriteQueue(channel);
    // This write will have no effect, yet it will only complete once the negotiationHandler
    // flushes any pending writes.
    channel.write(NettyClientHandler.NOOP_MESSAGE).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          // Need to notify of this failure, because handler.connectionError() is not guaranteed to
          // have seen this cause.
          notifyTerminated(Utils.statusFromThrowable(future.cause()));
        }
      }
    });
    // Handle transport shutdown when the channel is closed.
    channel.closeFuture().addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        Status status = handler.errorStatus();
        if (status == null) {
          // We really only expect this to happen if shutdown() was called, but in that case this
          // status is ignored.
          status = Status.INTERNAL.withDescription("Connection closed with unknown cause");
        }
        notifyTerminated(status);
      }
    });
  }

  @Override
  public void shutdown() {
    notifyShutdown(Status.OK.withDescription("Channel requested transport to shut down"));
    // Notifying of termination is automatically done when the channel closes.
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + address + ")";
  }

  private void notifyShutdown(Status status) {
    Preconditions.checkNotNull(status, "status");
    boolean notifyShutdown;
    synchronized (this) {
      notifyShutdown = !shutdown;
      shutdown = true;
    }
    if (notifyShutdown) {
      listener.transportShutdown(status);
    }
  }

  private void notifyTerminated(Status status) {
    notifyShutdown(status);
    boolean notifyTerminated;
    synchronized (this) {
      notifyTerminated = !terminated;
      terminated = true;
    }
    if (notifyTerminated) {
      listener.transportTerminated();
    }
  }

  private NettyClientHandler newHandler() {
    return NettyClientHandler.newHandler(listener, flowControlWindow, maxHeaderListSize,
        Ticker.systemTicker());
  }
}
