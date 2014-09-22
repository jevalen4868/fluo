/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.core.impl;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.fluo.accumulo.util.LongUtil;
import io.fluo.accumulo.util.ZookeeperConstants;
import io.fluo.core.oracle.OracleClient;
import io.fluo.core.util.CuratorUtil;
import org.apache.curator.framework.recipes.nodes.PersistentEphemeralNode;
import org.apache.curator.framework.recipes.nodes.PersistentEphemeralNode.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allocates timestamps from Oracle for transactions and tracks 
 * the oldest active timestamp in Zookeeper for garbage collection
 */
public class TimestampTracker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TimestampTracker.class);
  private volatile long zkTimestamp = -1;
  private final Environment env;
  private final SortedSet<Long> timestamps = new TreeSet<>();
  private volatile PersistentEphemeralNode node = null;
  private final TransactorID tid;
  private final Timer timer;

  private boolean closed = false;
  private int allocationsInProgress = 0;
  private boolean updatingZk = false;

  public TimestampTracker(Environment env, TransactorID tid, long updatePeriodMs) {
    Preconditions.checkNotNull(env, "environment cannot be null");
    Preconditions.checkNotNull(tid, "tid cannot be null");
    Preconditions.checkArgument(updatePeriodMs > 0, "update period must be positive");
    this.env = env;
    this.tid = tid;

    TimerTask tt = new TimerTask() {

      private int sawZeroCount = 0;

      @Override
      public void run() {
        try {
          long ts = 0;

          synchronized(TimestampTracker.this) {
            if (closed)
              return;

            if (allocationsInProgress > 0) {
              sawZeroCount = 0;
              if (timestamps.size() > 0) {
                if (updatingZk)
                  throw new IllegalStateException("expected updatingZk to be false");
                ts = timestamps.first();
                updatingZk = true;
              }
            } else if (allocationsInProgress == 0) {
              sawZeroCount++;
              if (sawZeroCount >= 2) {
                sawZeroCount = 0;
                closeZkNode();
              }
            } else {
              throw new IllegalStateException("allocationsInProgress = " + allocationsInProgress);
            }

          }

          // update can be done outside of sync block as timer has one thread and future
          // executions of run method will block until this method returns
          if (updatingZk) {
            try {
              updateZkNode(ts);
            } finally {
              synchronized (TimestampTracker.this) {
                updatingZk = false;
              }
            }
          }
        } catch (Exception e) {
          log.error("Exception occurred in Zookeeper update thread", e);
        }
      }
    };
    timer = new Timer("TimestampTracker timer", true);
    timer.schedule(tt, updatePeriodMs, updatePeriodMs);
  }

  public TimestampTracker(Environment env, TransactorID tid) {
    this(env, tid, ZookeeperConstants.ZK_UPDATE_PERIOD_MS);
  }

  /**
   * Allocate a timestamp
   */
  public long allocateTimestamp() {

    synchronized (this) {
      Preconditions.checkState(!closed, "tracker closed ");

      if (node == null) {
        Preconditions.checkState(allocationsInProgress == 0, "expected allocationsInProgress == 0 when node == null");
        Preconditions.checkState(!updatingZk, "unexpected concurrent ZK update");

        createZkNode(getTimestamp());
      }

      allocationsInProgress++;
    }

    try {
      long ts = getTimestamp();

      synchronized (this) {
        timestamps.add(ts);
      }

      return ts;
    } catch (RuntimeException re) {
      synchronized (this) {
        allocationsInProgress--;
      }
      throw re;
    } 
  }

  /**
   * Remove a timestamp (of completed transaction)
   */
  public synchronized void removeTimestamp(long ts) throws NoSuchElementException {
    Preconditions.checkState(!closed, "tracker closed ");
    Preconditions.checkState(allocationsInProgress > 0, "allocationsInProgress should be > 0 " + allocationsInProgress);
    Preconditions.checkNotNull(node);
    if (timestamps.remove(ts) == false) {
      throw new NoSuchElementException("Timestamp "+ts+" was previously removed or does not exist");
    }

    allocationsInProgress--;
  }

  private long getTimestamp() {
    try {
      return OracleClient.getInstance(env).getTimestamp();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void createZkNode(long ts) {
    Preconditions.checkState(node == null, "expected node to be null");
    node = new PersistentEphemeralNode(env.getSharedResources().getCurator(), Mode.EPHEMERAL, getNodePath(), LongUtil.toByteArray(ts));
    CuratorUtil.startAndWait(node, 10);
    zkTimestamp = ts;
  }

  private void updateZkNode(long ts) {
    if (ts != zkTimestamp) {
      try {
        node.setData(LongUtil.toByteArray(ts));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    zkTimestamp = ts;
  }

  private void closeZkNode() {
    try {
      if (node != null) {
        node.close();
        node = null;
      }
    } catch (IOException e) {
      log.error("Failed to close timestamp tracker ephemeral node");
      throw new IllegalStateException(e);
    }
  }

  @VisibleForTesting
  synchronized void updateZkNode() {
    Preconditions.checkState(!updatingZk, "unexpected concurrent ZK update");

    if (allocationsInProgress > 0) {
      if (timestamps.size() > 0) {
        updateZkNode(timestamps.first());
      }
    } else if (allocationsInProgress == 0) {
      closeZkNode();
    } else {
      throw new IllegalStateException("allocationsInProgress = " + allocationsInProgress);
    }
  }

  @VisibleForTesting
  long getOldestActiveTimestamp() {
    return timestamps.first();
  }

  @VisibleForTesting
  long getZookeeperTimestamp() {
    return zkTimestamp;
  }

  @VisibleForTesting
  boolean isEmpty() {
    return timestamps.isEmpty();
  }

  @VisibleForTesting
  String getNodePath() {
    return ZookeeperConstants.transactorTsRoot(env.getZookeeperRoot()) + "/" + tid.toString();
  }

  @Override
  public synchronized void close() {
    Preconditions.checkState(!closed, "tracker already closed");
    closed = true;
    timer.cancel();
    closeZkNode();
  }
}