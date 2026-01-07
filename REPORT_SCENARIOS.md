# Scenario Report Guide

This document describes each scripted scenario, its goal, key steps, expected behavior, and the artifacts captured.

## scenario_smoke.sh

**Goal:** Ensure a single leader is elected after startup.  
**Steps:** Start cluster, wait for a leader via `/status`.  
**Expected:** Exactly one leader is reported.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_kv_replication.sh

**Goal:** Verify log replication and state machine application on all nodes.  
**Steps:** Write a key via KV API; check `lastApplied` on each node.  
**Expected:** All nodes advance `lastApplied` after the write.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_cas.sh

**Goal:** Validate CAS success and mismatch behavior.  
**Steps:** PUT value, CAS with correct expected, CAS with wrong expected.  
**Expected:** First CAS succeeds (200); second fails (409).  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_kill_leader.sh

**Goal:** Failover after leader crash and recovery.  
**Steps:** Stop leader container, wait for new leader, write, restart old leader.  
**Expected:** New leader elected; old leader catches up after restart.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_pause_follower.sh

**Goal:** Validate follower pause and catch-up.  
**Steps:** Pause a follower, write a key, unpause follower.  
**Expected:** Follower applies entries after unpause.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_pause_leader.sh

**Goal:** Validate leader pause and new election.  
**Steps:** Pause leader, wait for new leader, unpause old leader.  
**Expected:** New leader elected; old leader steps down.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_crash_recovery.sh

**Goal:** Validate crash recovery and persistence.  
**Steps:** Stop a follower, write data, restart follower.  
**Expected:** Data remains available; follower catches up.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_no_quorum.sh

**Goal:** Validate lack of quorum prevents commit.  
**Steps:** Stop two nodes, attempt write.  
**Expected:** Write fails (timeout/503/409) due to no quorum.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_partition_2_1.sh

**Goal:** Validate majority vs minority partition behavior.  
**Steps:** Partition node3 from nodes1/2, write on majority, heal.  
**Expected:** Majority commits; isolated node does not apply until heal.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_partition_isolate_leader.sh

**Goal:** Validate leader isolation and re-election.  
**Steps:** Partition current leader from others, wait for new leader, heal.  
**Expected:** New leader elected; old leader steps down after heal.  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_latency_instability.sh

**Goal:** Observe leader stability under latency.  
**Steps:** Apply latency toxics, observe leader changes counters; increase latency.  
**Expected:** Leader changes are recorded (may increase under heavy latency).  
**Artifacts:** `/status` from each node, docker compose logs.

## scenario_catchup_after_degradation.sh

**Goal:** Validate catch-up after degraded network.  
**Steps:** Apply latency/loss to node3 links, write multiple keys, heal.  
**Expected:** Node3 catches up and applies all entries.  
**Artifacts:** `/status` from each node, docker compose logs.
