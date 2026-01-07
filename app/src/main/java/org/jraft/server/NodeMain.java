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
import org.jraft.metrics.RaftMetrics;
import org.jraft.net.grpc.GrpcRaftTransport;
import org.jraft.node.RaftNode;
import org.jraft.node.RaftNodeFactory;

public class NodeMain {

  public static void main(String[] args) throws Exception {
    Map<String, String> cli = parseArgs(args);

    String nodeId = require("NODE_ID", cli);
    int raftPort = Integer.parseInt(require("RAFT_PORT", cli));
    int httpPort = parseInt("HTTP_PORT", cli, parseInt("API_PORT", cli, 8080));
    String peersRaw = require("PEERS", cli);
    String httpPeersRaw = cli.getOrDefault("HTTP_PEERS", System.getenv("HTTP_PEERS"));
    String dataDirRaw = require("DATA_DIR", cli);

    long minElection = parseLong("ELECTION_TIMEOUT_MIN_MS", cli, RaftNode.DEFAULT_MIN_ELECTION_MS);
    long maxElection = parseLong("ELECTION_TIMEOUT_MAX_MS", cli, RaftNode.DEFAULT_MAX_ELECTION_MS);
    long heartbeatMs = parseLong("HEARTBEAT_MS", cli, RaftNode.DEFAULT_HEARTBEAT_PERIOD_MS);
    long rpcTimeoutMs = parseLong("RPC_TIMEOUT_MS", cli, 2_000);

    Map<String, String> peerTargets = parsePeers(peersRaw, nodeId);
    Map<String, String> httpPeers = parseAllPeers(httpPeersRaw);
    List<String> peerIds = new ArrayList<>(peerTargets.keySet());
    Collections.sort(peerIds);
    List<String> allNodeIds = new ArrayList<>(peerTargets.keySet());
    allNodeIds.add(nodeId);
    Collections.sort(allNodeIds);

    Path dataDir = Paths.get(dataDirRaw);
    System.out.printf("Starting node %s on port %d%n", nodeId, raftPort);
    System.out.printf("Data dir: %s%n", dataDir);
    System.out.printf("Peers: %s%n", peerTargets);
    System.out.printf("Timers: election [%d, %d] ms, heartbeat %d ms%n", minElection, maxElection, heartbeatMs);
    System.out.printf("HTTP port: %d%n", httpPort);

    RaftMetrics metrics = new RaftMetrics();
    try (GrpcRaftTransport transport = new GrpcRaftTransport(peerTargets, rpcTimeoutMs, metrics)) {
      KvStateMachine kvStateMachine = new KvStateMachine();
      RaftNode node = RaftNodeFactory.create(
        nodeId,
        peerIds,
        dataDir,
        kvStateMachine,
        transport,
        minElection,
        maxElection,
        heartbeatMs,
        metrics
      );

      RaftRpcServer server = new RaftRpcServer(raftPort, node);
      server.start();

      NodeHttpServer httpServer = new NodeHttpServer(
        httpPort,
        nodeId,
        node,
        kvStateMachine,
        httpPeers,
        allNodeIds,
        metrics,
        parseOptionalLong("HTTP_TIMEOUT_MS", cli)
      );
      httpServer.start();

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("Shutting down node " + nodeId);
        httpServer.stop();
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

  private static int parseInt(String key, Map<String, String> cli, int defaultVal) {
    String value = cli.getOrDefault(key, System.getenv(key));
    if (value == null || value.isBlank()) return defaultVal;
    return Integer.parseInt(value);
  }

  private static Long parseOptionalLong(String key, Map<String, String> cli) {
    String value = cli.getOrDefault(key, System.getenv(key));
    if (value == null || value.isBlank()) return null;
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

  private static Map<String, String> parseAllPeers(String peers) {
    Map<String, String> map = new HashMap<>();
    if (peers == null || peers.isBlank()) return map;

    String[] entries = peers.split(",");
    for (String entry : entries) {
      if (entry.isBlank()) continue;
      String[] kv = entry.split("=", 2);
      if (kv.length != 2) {
        throw new IllegalArgumentException("Invalid peer entry: " + entry);
      }
      String peerId = kv[0].trim();
      String address = kv[1].trim();
      map.put(peerId, address);
    }
    return map;
  }
}
