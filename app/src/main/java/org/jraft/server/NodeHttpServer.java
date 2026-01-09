package org.jraft.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.jraft.core.StateMachine;
import org.jraft.kv.Cas;
import org.jraft.kv.Command;
import org.jraft.kv.Del;
import org.jraft.kv.KvStateMachine;
import org.jraft.kv.Put;
import org.jraft.metrics.RaftMetrics;
import org.jraft.node.RaftNode;
import org.jraft.state.RaftState;

public class NodeHttpServer {
  private static final long DEFAULT_KV_TIMEOUT_MS = 3_000;

  private final HttpServer server;
  private final RaftNode node;
  private final KvStateMachine kv;
  private final String nodeId;
  private final Map<String, String> httpPeers;
  private final List<String> allNodeIds;
  private final RaftMetrics metrics;
  private final Gson gson;
  private final long kvTimeoutMs;
  private final AtomicLong opCounter = new AtomicLong();

  public NodeHttpServer(
      int port,
      String nodeId,
      RaftNode node,
      KvStateMachine kv,
      Map<String, String> httpPeers,
      List<String> allNodeIds,
      RaftMetrics metrics,
      Long kvTimeoutMs) throws IOException {
    this.node = node;
    this.kv = kv;
    this.nodeId = nodeId;
    this.httpPeers = httpPeers;
    this.allNodeIds = allNodeIds;
    this.metrics = metrics;
    this.gson = new GsonBuilder().setPrettyPrinting().create();
    this.kvTimeoutMs = kvTimeoutMs != null ? kvTimeoutMs : DEFAULT_KV_TIMEOUT_MS;

    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", this::handleRoot);
    server.createContext("/status", this::handleStatus);
    server.createContext("/metrics", this::handleMetrics);
    server.createContext("/kv/cas", this::handleCas);
    server.createContext("/kv", this::handleKv);
    server.setExecutor(null);
  }

  public void start() {
    server.start();
    System.out.printf("HTTP API for node %s started on port %d%n", nodeId, server.getAddress().getPort());
  }

  public void stop() {
    server.stop(0);
  }

  private void handleRoot(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    String path = exchange.getRequestURI().getPath();
    if (!"/".equals(path)) {
      sendError(exchange, 404, "not found");
      return;
    }

    RootResponse response = new RootResponse();
    response.message = "jjraft node HTTP API";
    response.status = "/status";
    response.metrics = "/metrics";
    response.kv = "/kv/{key}";
    response.cas = "/kv/cas";
    sendJson(exchange, 200, response);
  }

  private void handleStatus(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    RaftState state = node.getRaftState();
    StatusResponse response = new StatusResponse();
    response.nodeId = nodeId;
    response.role = state.getRole().name();
    response.term = state.getCurrentTerm();
    response.leaderId = state.getLeader();
    response.commitIndex = state.getCommitIndex();
    response.lastApplied = state.getLastApplied();
    response.lastLogIndex = node.getLog().lastIndex();
    response.peers = allNodeIds;

    sendJson(exchange, 200, response);
  }

  private void handleMetrics(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    MetricsResponse response = new MetricsResponse();
    if (metrics != null) {
      response.electionsTotal = metrics.getElectionsTotal();
      response.leaderChangesTotal = metrics.getLeaderChangesTotal();
      response.appendEntriesSent = metrics.getAppendEntriesSent();
      response.appendEntriesFailed = metrics.getAppendEntriesFailed();
      response.requestVoteSent = metrics.getRequestVoteSent();
      response.requestVoteFailed = metrics.getRequestVoteFailed();
    }
    sendJson(exchange, 200, response);
  }

  private void handleKv(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if (!method.equals("GET") && !method.equals("PUT") && !method.equals("DELETE")) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    String key = extractKey(exchange.getRequestURI());
    if (key == null || key.isBlank()) {
      sendError(exchange, 400, "missing key");
      return;
    }

    if (!isLeader()) {
      redirectToLeader(exchange);
      return;
    }

    if (method.equals("GET")) {
      byte[] value = kv.get(key);
      if (value == null) {
        sendError(exchange, 404, "key not found");
        return;
      }
      sendText(exchange, 200, new String(value, StandardCharsets.UTF_8));
      return;
    }

    if (method.equals("PUT")) {
      byte[] value = exchange.getRequestBody().readAllBytes();
      Command cmd = Command.newBuilder()
        .setClientId("http-" + nodeId)
        .setOpId(opCounter.incrementAndGet())
        .setPut(Put.newBuilder()
          .setKey(key)
          .setValue(ByteString.copyFrom(value))
          .build())
        .build();
      StateMachine.ApplyResult result = handleWrite(exchange, cmd);
      if (result != null) {
        sendWriteResponse(exchange, result);
      }
      return;
    }

    if (method.equals("DELETE")) {
      Command cmd = Command.newBuilder()
        .setClientId("http-" + nodeId)
        .setOpId(opCounter.incrementAndGet())
        .setDel(Del.newBuilder().setKey(key).build())
        .build();
      StateMachine.ApplyResult result = handleWrite(exchange, cmd);
      if (result != null) {
        sendWriteResponse(exchange, result);
      }
    }
  }

  private void handleCas(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    if (!isLeader()) {
      redirectToLeader(exchange);
      return;
    }

    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    CasRequest request = gson.fromJson(body, CasRequest.class);
    if (request == null || request.key == null) {
      sendError(exchange, 400, "missing key");
      return;
    }

    byte[] expected = request.expected == null ? new byte[0] : request.expected.getBytes(StandardCharsets.UTF_8);
    byte[] update = request.value == null ? new byte[0] : request.value.getBytes(StandardCharsets.UTF_8);

    Command cmd = Command.newBuilder()
      .setClientId("http-" + nodeId)
      .setOpId(opCounter.incrementAndGet())
      .setCas(Cas.newBuilder()
        .setKey(request.key)
        .setExpected(ByteString.copyFrom(expected))
        .setUpdate(ByteString.copyFrom(update))
        .build())
      .build();

    StateMachine.ApplyResult result = handleWrite(exchange, cmd);
    if (result == null) {
      return;
    }

    if (!result.ok()) {
      sendError(exchange, 409, "cas mismatch");
      return;
    }

    sendWriteResponse(exchange, result);
  }

  private StateMachine.ApplyResult handleWrite(HttpExchange exchange, Command cmd) throws IOException {
    long index = node.propose(cmd.toByteArray());
    if (index < 0) {
      redirectToLeader(exchange);
      return null;
    }

    StateMachine.ApplyResult result = waitForResult(index, kvTimeoutMs);
    if (result == null) {
      sendError(exchange, 504, "timeout waiting for commit");
      return null;
    }

    return result;
  }

  private void sendWriteResponse(HttpExchange exchange, StateMachine.ApplyResult result) throws IOException {
    WriteResponse response = new WriteResponse();
    response.ok = result.ok();
    response.index = result.index();
    if (result.value() != null && result.value().length > 0) {
      response.value = new String(result.value(), StandardCharsets.UTF_8);
    }
    sendJson(exchange, 200, response);
  }

  private StateMachine.ApplyResult waitForResult(long index, long timeoutMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      StateMachine.ApplyResult result = kv.getResult(index);
      if (result != null) {
        return result;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  private boolean isLeader() {
    return node.getRaftState().getRole() == RaftState.Role.LEADER;
  }

  private void redirectToLeader(HttpExchange exchange) throws IOException {
    String leaderId = node.getRaftState().getLeader();
    if (leaderId != null && httpPeers != null && httpPeers.containsKey(leaderId)) {
      String leaderAddress = httpPeers.get(leaderId);
      URI uri = exchange.getRequestURI();
      String redirectUrl = "http://" + leaderAddress + uri.getPath();
      if (uri.getRawQuery() != null) {
        redirectUrl += "?" + uri.getRawQuery();
      }
      exchange.getResponseHeaders().set("Location", redirectUrl);
      exchange.sendResponseHeaders(307, -1);
      return;
    }

    LeaderResponse response = new LeaderResponse();
    response.error = "no leader";
    response.leader = leaderId;
    sendJson(exchange, 503, response);
  }

  private String extractKey(URI uri) {
    String path = uri.getPath();
    if (!path.startsWith("/kv/")) return null;
    String encoded = path.substring("/kv/".length());
    if (encoded.isEmpty()) return null;
    return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
  }

  private void sendError(HttpExchange exchange, int status, String message) throws IOException {
    ErrorResponse response = new ErrorResponse();
    response.error = message;
    sendJson(exchange, status, response);
  }

  private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] data = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, data.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(data);
    }
  }

  private void sendText(HttpExchange exchange, int status, String text) throws IOException {
    byte[] data = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(status, data.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(data);
    }
  }

  private static class StatusResponse {
    String nodeId;
    String role;
    long term;
    String leaderId;
    long commitIndex;
    long lastApplied;
    long lastLogIndex;
    List<String> peers;
  }

  private static class MetricsResponse {
    long electionsTotal;
    long leaderChangesTotal;
    long appendEntriesSent;
    long appendEntriesFailed;
    long requestVoteSent;
    long requestVoteFailed;
  }

  private static class WriteResponse {
    boolean ok;
    long index;
    String value;
  }

  private static class ErrorResponse {
    String error;
  }

  private static class RootResponse {
    String message;
    String status;
    String metrics;
    String kv;
    String cas;
  }

  private static class LeaderResponse {
    String error;
    String leader;
  }

  private static class CasRequest {
    String key;
    String expected;
    String value;
  }
}
