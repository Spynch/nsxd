package org.jraft.server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.jraft.node.RaftNode;
import org.jraft.rpc.AppendEntriesRequest;
import org.jraft.rpc.AppendEntriesResponse;
import org.jraft.rpc.InstallSnapshotRequest;
import org.jraft.rpc.InstallSnapshotResponse;
import org.jraft.rpc.RaftGrpc;
import org.jraft.rpc.RequestVoteRequest;
import org.jraft.rpc.RequestVoteResponse;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

public class RaftRpcServer {

  private final int port;
  private final RaftNode node;
  private Server server;

  public RaftRpcServer(int port, RaftNode node) {
    this.port = port;
    this.node = node;
  }

  public void start() throws IOException {
    server = NettyServerBuilder.forPort(port)
      .addService(new RaftService())
      .build()
      .start();
    System.out.printf("Raft RPC server started on %d%n", port);
  }

  public void stop() {
    if (server == null) return;
    server.shutdown();
    try {
      if (!server.awaitTermination(3, TimeUnit.SECONDS)) {
        server.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      server.shutdownNow();
    }
  }

  public void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  private class RaftService extends RaftGrpc.RaftImplBase {

    @Override
    public void requestVote(RequestVoteRequest request, StreamObserver<RequestVoteResponse> responseObserver) {
      try {
        RequestVoteResponse response = node.onRequestVoteRequest(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (Exception e) {
        responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
      }
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
      try {
        AppendEntriesResponse response = node.onAppendEntriesRequest(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (Exception e) {
        responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
      }
    }

    @Override
    public StreamObserver<InstallSnapshotRequest> installSnapshot(StreamObserver<InstallSnapshotResponse> responseObserver) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("InstallSnapshot not implemented").asRuntimeException());
      return new StreamObserver<InstallSnapshotRequest>() {
        @Override public void onNext(InstallSnapshotRequest value) {}
        @Override public void onError(Throwable t) {}
        @Override public void onCompleted() {}
      };
    }
  }
}
