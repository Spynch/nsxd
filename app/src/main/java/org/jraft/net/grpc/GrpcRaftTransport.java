package org.jraft.net.grpc;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jraft.net.RaftTransport;
import org.jraft.metrics.RaftMetrics;
import org.jraft.rpc.AppendEntriesRequest;
import org.jraft.rpc.AppendEntriesResponse;
import org.jraft.rpc.RaftGrpc;
import org.jraft.rpc.RequestVoteRequest;
import org.jraft.rpc.RequestVoteResponse;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/**
 * gRPC-based implementation of the Raft transport.
 *
 * Minimal, best-effort client that forwards RPCs to peers with a per-call timeout.
 */
public class GrpcRaftTransport implements RaftTransport, AutoCloseable {

  private final Map<String, ManagedChannel> channels = new HashMap<>();
  private final Map<String, RaftGrpc.RaftBlockingStub> stubs = new HashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final long rpcTimeoutMs;
  private final RaftMetrics metrics;

  public GrpcRaftTransport(Map<String, String> peerAddressById) {
    this(peerAddressById, 2_000, null);
  }

  public GrpcRaftTransport(Map<String, String> peerAddressById, long rpcTimeoutMs) {
    this(peerAddressById, rpcTimeoutMs, null);
  }

  public GrpcRaftTransport(Map<String, String> peerAddressById, long rpcTimeoutMs, RaftMetrics metrics) {
    this.rpcTimeoutMs = rpcTimeoutMs;
    this.metrics = metrics;
    peerAddressById.forEach((peerId, address) -> {
      HostPort hostPort = parseAddress(address);
      String target = "dns:///" + hostPort.host() + ":" + hostPort.port();
      ManagedChannel channel = NettyChannelBuilder.forTarget(target)
        .usePlaintext()
        .build();
      channels.put(peerId, channel);
      stubs.put(peerId, RaftGrpc.newBlockingStub(channel));
    });
  }

  @Override
  public void requestVote(String peerId, RequestVoteRequest req, Consumer<RequestVoteResponse> cb) {
    RaftGrpc.RaftBlockingStub stub = stubs.get(peerId);
    if (stub == null) {
      System.err.printf("requestVote: unknown peer %s%n", peerId);
      return;
    }
    if (metrics != null) metrics.incRequestVoteSent();
    dispatch(() -> stub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS).requestVote(req), cb, "RequestVote");
  }

  @Override
  public void appendEntries(String peerId, AppendEntriesRequest req, Consumer<AppendEntriesResponse> cb) {
    RaftGrpc.RaftBlockingStub stub = stubs.get(peerId);
    if (stub == null) {
      System.err.printf("appendEntries: unknown peer %s%n", peerId);
      return;
    }
    if (metrics != null) metrics.incAppendEntriesSent();
    dispatch(() -> stub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS).appendEntries(req), cb, "AppendEntries");
  }

  private <T> void dispatch(Supplier<T> call, Consumer<T> cb, String opName) {
    executor.submit(() -> {
      try {
        T resp = call.get();
        cb.accept(resp);
      } catch (StatusRuntimeException e) {
        System.err.printf("%s RPC failed: %s%n", opName, e.getStatus());
        markFailure(opName);
      } catch (Exception e) {
        System.err.printf("%s RPC error: %s%n", opName, e.getMessage());
        markFailure(opName);
      }
    });
  }

  private void markFailure(String opName) {
    if (metrics == null) return;
    if ("AppendEntries".equals(opName)) {
      metrics.incAppendEntriesFailed();
    } else if ("RequestVote".equals(opName)) {
      metrics.incRequestVoteFailed();
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    channels.values().forEach(ch -> {
      ch.shutdown();
      try {
        if (!ch.awaitTermination(2, TimeUnit.SECONDS)) {
          ch.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        ch.shutdownNow();
      }
    });
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static HostPort parseAddress(String address) {
    Objects.requireNonNull(address, "peer address");
    String[] parts = address.split(":", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("address must be host:port, got: " + address);
    }
    int port = Integer.parseInt(parts[1]);
    return new HostPort(parts[0], port);
  }

  private record HostPort(String host, int port) {}
}
