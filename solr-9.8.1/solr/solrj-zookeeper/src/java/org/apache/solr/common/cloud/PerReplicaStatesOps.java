/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.common.cloud;

import static java.util.Collections.singletonList;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.CommonTestInjection;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a helper class that encapsulates various operations performed on the per-replica states
 * Do not directly manipulate the per replica states as it can become difficult to debug them
 */
public class PerReplicaStatesOps {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private PerReplicaStates rs;
  List<PerReplicaStates.Operation> ops;
  private boolean preOp = true;
  final Function<PerReplicaStates, List<PerReplicaStates.Operation>> fun;

  PerReplicaStatesOps(Function<PerReplicaStates, List<PerReplicaStates.Operation>> fun) {
    this.fun = fun;
  }

  /**
   * Fetch the latest {@link PerReplicaStates} . It fetches data after checking the {@link
   * Stat#getCversion()} of state.json. If this is not modified, the same object is returned
   */
  public static PerReplicaStates fetch(
      String path, SolrZkClient zkClient, PerReplicaStates current) {
    try {
      assert CommonTestInjection.injectBreakpoint(
          PerReplicaStatesOps.class.getName() + "/beforePrsFetch");
      if (current != null) {
        Stat stat = zkClient.exists(current.path, null, true);
        if (stat == null) return new PerReplicaStates(path, 0, Collections.emptyList());
        if (current.cversion == stat.getCversion()) return current; // not modifiedZkStateReaderTest
      }
      Stat stat = new Stat();
      List<String> children = zkClient.getChildren(path, null, stat, true);
      return new PerReplicaStates(path, stat.getCversion(), Collections.unmodifiableList(children));
    } catch (KeeperException.NoNodeException e) {
      throw new PrsZkNodeNotFoundException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Error fetching per-replica states. The node [" + path + "] is not found",
          e);
    } catch (KeeperException e) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR, "Error fetching per-replica states", e);
    } catch (InterruptedException e) {
      SolrZkClient.checkInterrupted(e);
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Thread interrupted when loading per-replica states from " + path,
          e);
    }
  }

  public static class PrsZkNodeNotFoundException extends SolrException {
    private PrsZkNodeNotFoundException(ErrorCode code, String msg, Throwable cause) {
      super(code, msg, cause);
    }
  }

  public static DocCollection.PrsSupplier getZkClientPrsSupplier(
      SolrZkClient zkClient, String collectionPath) {
    return () -> fetch(collectionPath, zkClient, null);
  }

  /** Persist a set of operations to Zookeeper */
  private void persist(
      List<PerReplicaStates.Operation> operations, String znode, SolrZkClient zkClient)
      throws KeeperException, InterruptedException {
    if (operations == null || operations.isEmpty()) return;
    if (log.isDebugEnabled()) {
      log.debug("Per-replica state being persisted for : '{}', ops: {}", znode, operations);
    }

    List<Op> ops = new ArrayList<>(operations.size());
    for (PerReplicaStates.Operation op : operations) {
      // the state of the replica is being updated
      String path = znode + "/" + op.state.asString;
      ops.add(
          op.typ == PerReplicaStates.Operation.Type.ADD
              ? Op.create(
                  path, null, zkClient.getZkACLProvider().getACLsToAdd(path), CreateMode.PERSISTENT)
              : Op.delete(path, -1));
    }
    try {
      zkClient.multi(ops, true);
    } catch (KeeperException e) {
      log.error("Multi-op exception: {}", zkClient.getChildren(znode, null, true));
      throw e;
    }
  }

  /** There is a possibility that a replica may have some leftover entries. Delete them too. */
  private static List<PerReplicaStates.Operation> addDeleteStaleNodes(
      List<PerReplicaStates.Operation> ops, PerReplicaStates.State rs) {
    while (rs != null) {
      ops.add(new PerReplicaStates.Operation(PerReplicaStates.Operation.Type.DELETE, rs));
      rs = rs.duplicate;
    }
    return ops;
  }

  /** This is a persist operation with retry if a write fails due to stale state */
  public void persist(String znode, SolrZkClient zkClient)
      throws KeeperException, InterruptedException {
    List<PerReplicaStates.Operation> operations = ops;
    for (int i = 0; i < PerReplicaStates.MAX_RETRIES; i++) {
      try {
        persist(operations, znode, zkClient);
        return;
      } catch (KeeperException.NodeExistsException | KeeperException.NoNodeException e) {
        // state is stale
        if (log.isInfoEnabled()) {
          log.info("Stale state for {}, attempt: {}. retrying...", znode, i);
        }
        operations = refresh(fetch(znode, zkClient, null));
      }
    }
  }

  public PerReplicaStates getPerReplicaStates() {
    return rs;
  }

  /**
   * Change the state of a replica
   *
   * @param newState the new state
   */
  public static PerReplicaStatesOps flipState(
      String replica, Replica.State newState, PerReplicaStates rs) {
    return new PerReplicaStatesOps(
            prs -> {
              List<PerReplicaStates.Operation> operations = new ArrayList<>(2);
              PerReplicaStates.State existing = prs.get(replica);
              if (existing == null) {
                operations.add(
                    new PerReplicaStates.Operation(
                        PerReplicaStates.Operation.Type.ADD,
                        new PerReplicaStates.State(replica, newState, Boolean.FALSE, 0)));
              } else {
                operations.add(
                    new PerReplicaStates.Operation(
                        PerReplicaStates.Operation.Type.ADD,
                        new PerReplicaStates.State(
                            replica, newState, existing.isLeader, existing.version + 1)));
                addDeleteStaleNodes(operations, existing);
              }
              if (log.isDebugEnabled()) {
                log.debug(
                    "flipState on {}, {} -> {}, ops :{}", prs.path, replica, newState, operations);
              }
              return operations;
            })
        .init(rs);
  }

  /** Switch a collection /to perReplicaState=true */
  public static PerReplicaStatesOps enable(DocCollection coll, PerReplicaStates rs) {
    return new PerReplicaStatesOps(
            prs -> {
              List<PerReplicaStates.Operation> result = new ArrayList<>();
              coll.forEachReplica(
                  (s, r) -> {
                    PerReplicaStates.State old = prs.states.get(r.getName());
                    int version = old == null ? 0 : old.version + 1;
                    result.add(
                        new PerReplicaStates.Operation(
                            PerReplicaStates.Operation.Type.ADD,
                            new PerReplicaStates.State(
                                r.getName(), r.getState(), r.isLeader(), version)));
                    addDeleteStaleNodes(result, old);
                  });
              return result;
            })
        .init(rs);
  }

  /** Switch a collection /to perReplicaState=false */
  public static PerReplicaStatesOps disable(PerReplicaStates rs) {
    PerReplicaStatesOps ops =
        new PerReplicaStatesOps(
            prs -> {
              List<PerReplicaStates.Operation> result = new ArrayList<>();
              prs.states.forEach(
                  (s, state) ->
                      result.add(
                          new PerReplicaStates.Operation(
                              PerReplicaStates.Operation.Type.DELETE, state)));
              return result;
            });
    ops.preOp = false;
    return ops.init(rs);
  }

  /**
   * Flip the leader replica to a new one
   *
   * @param allReplicas allReplicas of the shard
   * @param next next leader
   */
  public static PerReplicaStatesOps flipLeader(
      Set<String> allReplicas, String next, PerReplicaStates rs) {
    return new PerReplicaStatesOps(
            prs -> {
              List<PerReplicaStates.Operation> ops = new ArrayList<>();
              if (next != null) {
                PerReplicaStates.State st = prs.get(next);
                if (st != null) {
                  if (!st.isLeader) {
                    ops.add(
                        new PerReplicaStates.Operation(
                            PerReplicaStates.Operation.Type.ADD,
                            new PerReplicaStates.State(
                                st.replica, Replica.State.ACTIVE, Boolean.TRUE, st.version + 1)));
                    ops.add(
                        new PerReplicaStates.Operation(PerReplicaStates.Operation.Type.DELETE, st));
                  }
                  // else do not do anything, that node is the leader
                } else {
                  // there is no entry for the new leader.
                  // create one
                  ops.add(
                      new PerReplicaStates.Operation(
                          PerReplicaStates.Operation.Type.ADD,
                          new PerReplicaStates.State(next, Replica.State.ACTIVE, Boolean.TRUE, 0)));
                }
              }

              // now go through all other replicas and unset previous leader
              for (String r : allReplicas) {
                PerReplicaStates.State st = prs.get(r);
                if (st == null) continue; // unlikely
                if (!Objects.equals(r, next)) {
                  if (st.isLeader) {
                    // some other replica is the leader now. unset
                    ops.add(
                        new PerReplicaStates.Operation(
                            PerReplicaStates.Operation.Type.ADD,
                            new PerReplicaStates.State(
                                st.replica, st.state, Boolean.FALSE, st.version + 1)));
                    ops.add(
                        new PerReplicaStates.Operation(PerReplicaStates.Operation.Type.DELETE, st));
                  }
                }
              }
              if (log.isDebugEnabled()) {
                log.debug("flipLeader on:{}, {} -> {}, ops: {}", prs.path, allReplicas, next, ops);
              }
              return ops;
            })
        .init(rs);
  }

  /**
   * Delete a replica entry from per-replica states
   *
   * @param replica name of the replica to be deleted
   */
  public static PerReplicaStatesOps deleteReplica(String replica, PerReplicaStates rs) {
    return new PerReplicaStatesOps(
            prs -> {
              List<PerReplicaStates.Operation> result;
              if (prs == null) {
                result = Collections.emptyList();
              } else {
                PerReplicaStates.State state = prs.get(replica);
                result = addDeleteStaleNodes(new ArrayList<>(), state);
              }
              return result;
            })
        .init(rs);
  }

  public static PerReplicaStatesOps addReplica(
      String replica, Replica.State state, boolean isLeader, PerReplicaStates rs) {
    return new PerReplicaStatesOps(
            perReplicaStates ->
                singletonList(
                    new PerReplicaStates.Operation(
                        PerReplicaStates.Operation.Type.ADD,
                        new PerReplicaStates.State(replica, state, isLeader, 0))))
        .init(rs);
  }

  /** Mark the given replicas as DOWN */
  public static PerReplicaStatesOps downReplicas(List<String> replicas, PerReplicaStates rs) {
    return new PerReplicaStatesOps(
            prs -> {
              List<PerReplicaStates.Operation> operations = new ArrayList<>();
              for (String replica : replicas) {
                PerReplicaStates.State r = prs.get(replica);
                if (r != null) {
                  if (r.state == Replica.State.DOWN && !r.isLeader) continue;
                  operations.add(
                      new PerReplicaStates.Operation(
                          PerReplicaStates.Operation.Type.ADD,
                          new PerReplicaStates.State(
                              replica, Replica.State.DOWN, r.isLeader, r.version + 1)));
                  addDeleteStaleNodes(operations, r);
                } else {
                  operations.add(
                      new PerReplicaStates.Operation(
                          PerReplicaStates.Operation.Type.ADD,
                          new PerReplicaStates.State(
                              replica, Replica.State.DOWN, Boolean.FALSE, 0)));
                }
              }
              if (log.isDebugEnabled()) {
                log.debug("for coll: {} down replicas {}, ops {}", prs, replicas, operations);
              }
              return operations;
            })
        .init(rs);
  }

  PerReplicaStatesOps init(PerReplicaStates rs) {
    if (rs == null) return null;
    get(rs);
    return this;
  }

  public List<PerReplicaStates.Operation> get() {
    return ops;
  }

  public List<PerReplicaStates.Operation> get(PerReplicaStates rs) {
    ops = refresh(rs);
    if (ops == null) ops = Collections.emptyList();
    this.rs = rs;
    return ops;
  }

  /**
   * This method should compute the set of ZK operations for a given action for instance, a state
   * change may result in 2 operations on per-replica states (1 CREATE and 1 DELETE) if a multi
   * operation fails because the state got modified from behind, refresh the operation and try again
   *
   * @param prs The latest state
   */
  List<PerReplicaStates.Operation> refresh(PerReplicaStates prs) {
    if (fun != null) return fun.apply(prs);
    return null;
  }

  @Override
  public String toString() {
    return ops.toString();
  }
}
