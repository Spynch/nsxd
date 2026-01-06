package org.jraft.server;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jraft.kv.KvStateMachine;
import org.jraft.net.grpc.GrpcRaftTransport;
import org.jraft.node.RaftNode;
import org.jraft.node.RaftNodeFactory;

public class NodeMain {

  public static void main(String[] args) throws Exception {
    Map<String, String> cli = parseArgs(args);

    String nodeId = require("NODE_ID", cli);
    int raftPort = Integer.parseInt(require("RAFT_PORT", cli));
    String peersRaw = require("PEERS", cli);
    String dataDirRaw = require("DATA_DIR", cli);

    long minElection = parseLong("ELECTION_TIMEOUT_MIN_MS", cli, RaftNode.DEFAULT_MIN_ELECTION_MS);
    long maxElection = parseLong("ELECTION_TIMEOUT_MAX_MS", cli, RaftNode.DEFAULT_MAX_ELECTION_MS);
    long heartbeatMs = parseLong("HEARTBEAT_MS", cli, RaftNode.DEFAULT_HEARTBEAT_PERIOD_MS);
    long rpcTimeoutMs = parseLong("RPC_TIMEOUT_MS", cli, 2_000);

    Map<String, String> peerTargets = parsePeers(peersRaw, nodeId);
    List<String> peerIds = new ArrayList<>(peerTargets.keySet());
    Collections.sort(peerIds);

    Path dataDir = Paths.get(dataDirRaw);
    System.out.printf("Starting node %s on port %d%n", nodeId, raftPort);
    System.out.printf("Data dir: %s%n", dataDir);
    System.out.printf("Peers: %s%n", peerTargets);
    System.out.printf("Timers: election [%d, %d] ms, heartbeat %d ms%n", minElection, maxElection, heartbeatMs);

    try (GrpcRaftTransport transport = new GrpcRaftTransport(peerTargets, rpcTimeoutMs)) {
      RaftNode node = RaftNodeFactory.create(
        nodeId,
        peerIds,
        dataDir,
        new KvStateMachine(),
        transport,
        minElection,
        maxElection,
        heartbeatMs
      );

      RaftRpcServer server = new RaftRpcServer(raftPort, node);
      server.start();

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("Shutting down node " + nodeId);
        server.stop();
      }));

      System.out.println("Node is running. Waiting for shutdown...");
      server.blockUntilShutdown();
    }
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> values = new HashMap<>();
    if (args == null) return values;
    for (String arg : args) {
      if (arg == null) continue;
      String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
      String[] parts = trimmed.split("=", 2);
      if (parts.length == 2) {
        values.put(parts[0].toUpperCase(), parts[1]);
      }
    }
    return values;
  }

  private static String require(String key, Map<String, String> cli) {
    String val = cli.get(key);
    if (val == null) {
      val = System.getenv(key);
    }
    if (val == null || val.isBlank()) {
      throw new IllegalArgumentException("Missing required configuration: " + key);
    }
    return val;
  }

  private static long parseLong(String key, Map<String, String> cli, long defaultVal) {
    String value = cli.getOrDefault(key, System.getenv(key));
    if (value == null || value.isBlank()) return defaultVal;
    return Long.parseLong(value);
  }

  private static Map<String, String> parsePeers(String peers, String selfId) {
    Objects.requireNonNull(peers, "peers");
    Map<String, String> map = new HashMap<>();
    if (peers.isBlank()) return map;

    String[] entries = peers.split(",");
    for (String entry : entries) {
      if (entry.isBlank()) continue;
      String[] kv = entry.split("=", 2);
      if (kv.length != 2) {
        throw new IllegalArgumentException("Invalid peer entry: " + entry);
      }
      String peerId = kv[0].trim();
      String address = kv[1].trim();
      if (!peerId.equals(selfId)) {
        map.put(peerId, address);
      }
    }
    return map;
  }
}
