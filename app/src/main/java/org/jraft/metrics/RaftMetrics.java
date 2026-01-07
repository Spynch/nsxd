package org.jraft.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class RaftMetrics {
  private final AtomicLong electionsTotal = new AtomicLong();
  private final AtomicLong leaderChangesTotal = new AtomicLong();
  private final AtomicLong appendEntriesSent = new AtomicLong();
  private final AtomicLong appendEntriesFailed = new AtomicLong();
  private final AtomicLong requestVoteSent = new AtomicLong();
  private final AtomicLong requestVoteFailed = new AtomicLong();

  public void incElections() { electionsTotal.incrementAndGet(); }
  public void incLeaderChanges() { leaderChangesTotal.incrementAndGet(); }
  public void incAppendEntriesSent() { appendEntriesSent.incrementAndGet(); }
  public void incAppendEntriesFailed() { appendEntriesFailed.incrementAndGet(); }
  public void incRequestVoteSent() { requestVoteSent.incrementAndGet(); }
  public void incRequestVoteFailed() { requestVoteFailed.incrementAndGet(); }

  public long getElectionsTotal() { return electionsTotal.get(); }
  public long getLeaderChangesTotal() { return leaderChangesTotal.get(); }
  public long getAppendEntriesSent() { return appendEntriesSent.get(); }
  public long getAppendEntriesFailed() { return appendEntriesFailed.get(); }
  public long getRequestVoteSent() { return requestVoteSent.get(); }
  public long getRequestVoteFailed() { return requestVoteFailed.get(); }
}
