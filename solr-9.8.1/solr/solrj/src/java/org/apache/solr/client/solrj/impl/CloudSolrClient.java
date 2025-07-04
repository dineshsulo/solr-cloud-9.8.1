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

package org.apache.solr.client.solrj.impl;

import static org.apache.solr.common.params.CommonParams.ADMIN_PATHS;
import static org.apache.solr.common.params.CommonParams.ID;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.V2RequestSupport;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.IsUpdateRequest;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.client.solrj.routing.ReplicaListTransformer;
import org.apache.solr.client.solrj.routing.RequestReplicaListTransformerGenerator;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.ToleratedUpdateError;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.ImplicitDocRouter;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.CollectionUtil;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.Hash;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public abstract class CloudSolrClient extends SolrClient {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  // no of times collection state to be reloaded if stale state error is received
  private static final int MAX_STALE_RETRIES =
      Integer.parseInt(System.getProperty("cloudSolrClientMaxStaleRetries", "5"));
  private final Random rand = new Random();

  private final boolean updatesToLeaders;
  private final boolean directUpdatesToLeadersOnly;
  private final RequestReplicaListTransformerGenerator requestRLTGenerator;
  private final boolean parallelUpdates;
  private ExecutorService threadPool =
      ExecutorUtil.newMDCAwareCachedThreadPool(
          new SolrNamedThreadFactory("CloudSolrClient ThreadPool"));

  public static final String STATE_VERSION = "_stateVer_";
  protected long retryExpiryTimeNano =
      TimeUnit.NANOSECONDS.convert(3, TimeUnit.SECONDS); // 3 seconds or 3 million nanos
  private static final Set<String> NON_ROUTABLE_PARAMS =
      Set.of(
          UpdateParams.EXPUNGE_DELETES,
          UpdateParams.MAX_OPTIMIZE_SEGMENTS,
          UpdateParams.COMMIT,
          UpdateParams.WAIT_SEARCHER,
          UpdateParams.OPEN_SEARCHER,
          UpdateParams.SOFT_COMMIT,
          UpdateParams.PREPARE_COMMIT,
          UpdateParams.OPTIMIZE

          // Not supported via SolrCloud
          // UpdateParams.ROLLBACK
          );

  protected volatile Object[] locks = objectList(3);

  /** Constructs {@link CloudSolrClient} instances from provided configuration. */
  public static class Builder extends CloudHttp2SolrClient.Builder {

    /**
     * Provide a series of Solr URLs to be used when configuring {@link CloudSolrClient} instances.
     * The solr client will use these urls to understand the cluster topology, which solr nodes are
     * active etc.
     *
     * <p>Provided Solr URLs are expected to point to the root Solr path
     * ("http://hostname:8983/solr"); they should not include any collections, cores, or other path
     * components.
     *
     * <p>Usage example:
     *
     * <pre>
     *   final List&lt;String&gt; solrBaseUrls = new ArrayList&lt;String&gt;();
     *   solrBaseUrls.add("http://solr1:8983/solr"); solrBaseUrls.add("http://solr2:8983/solr"); solrBaseUrls.add("http://solr3:8983/solr");
     *   final SolrClient client = new CloudSolrClient.Builder(solrBaseUrls).build();
     * </pre>
     */
    public Builder(List<String> solrUrls) {
      super(solrUrls);
    }

    /**
     * Provide a series of ZK hosts which will be used when configuring {@link CloudSolrClient}
     * instances. This requires a dependency on {@code solr-solrj-zookeeper} which transitively
     * depends on more JARs. The ZooKeeper based connection is the most reliable and performant
     * means for CloudSolrClient to work. On the other hand, it means exposing ZooKeeper more
     * broadly than to Solr nodes, which is a security risk.
     *
     * <p>Usage example when Solr stores data at the ZooKeeper root ('/'):
     *
     * <pre>
     *   final List&lt;String&gt; zkServers = new ArrayList&lt;String&gt;();
     *   zkServers.add("zookeeper1:2181"); zkServers.add("zookeeper2:2181"); zkServers.add("zookeeper3:2181");
     *   final SolrClient client = new CloudSolrClient.Builder(zkServers, Optional.empty()).build();
     * </pre>
     *
     * Usage example when Solr data is stored in a ZooKeeper chroot:
     *
     * <pre>
     *    final List&lt;String&gt; zkServers = new ArrayList&lt;String&gt;();
     *    zkServers.add("zookeeper1:2181"); zkServers.add("zookeeper2:2181"); zkServers.add("zookeeper3:2181");
     *    final SolrClient client = new CloudSolrClient.Builder(zkServers, Optional.of("/solr")).build();
     *  </pre>
     *
     * @param zkHosts a List of at least one ZooKeeper host and port (e.g. "zookeeper1:2181")
     * @param zkChroot the path to the root ZooKeeper node containing Solr data. Provide {@code
     *     java.util.Optional.empty()} if no ZK chroot is used.
     */
    public Builder(List<String> zkHosts, Optional<String> zkChroot) {
      super(zkHosts, zkChroot);
    }

    /** for an expert use-case */
    public Builder(ClusterStateProvider stateProvider) {
      super(stateProvider);
    }
  }

  static class StateCache extends ConcurrentHashMap<String, ExpiringCachedDocCollection> {
    final AtomicLong puts = new AtomicLong();
    final AtomicLong hits = new AtomicLong();
    final Lock evictLock = new ReentrantLock(true);
    protected volatile long timeToLiveMs = 60 * 1000L;

    @Override
    public ExpiringCachedDocCollection get(Object key) {
      ExpiringCachedDocCollection val = super.get(key);
      if (val == null) {
        // a new collection is likely to be added now.
        // check if there are stale items and remove them
        evictStale();
        return null;
      }
      if (val.isExpired(timeToLiveMs)) {
        super.remove(key);
        return null;
      }
      hits.incrementAndGet();
      return val;
    }

    @Override
    public ExpiringCachedDocCollection put(String key, ExpiringCachedDocCollection value) {
      puts.incrementAndGet();
      return super.put(key, value);
    }

    void evictStale() {
      if (!evictLock.tryLock()) return;
      try {
        for (Entry<String, ExpiringCachedDocCollection> e : entrySet()) {
          if (e.getValue().isExpired(timeToLiveMs)) {
            super.remove(e.getKey());
          }
        }
      } finally {
        evictLock.unlock();
      }
    }
  }

  /**
   * This is the time to wait to refetch the state after getting the same state version from ZK
   *
   * <p>secs
   *
   * @deprecated use {@link CloudSolrClient.Builder#setRetryExpiryTime(int)} instead
   */
  @Deprecated
  public void setRetryExpiryTime(int secs) {
    this.retryExpiryTimeNano = TimeUnit.NANOSECONDS.convert(secs, TimeUnit.SECONDS);
  }

  protected final StateCache collectionStateCache = new StateCache();

  class ExpiringCachedDocCollection {
    final DocCollection cached;
    final long cachedAtNano;
    // This is the time at which the collection is retried and got the same old version
    volatile long retriedAtNano = -1;
    // flag that suggests that this is potentially to be rechecked
    volatile boolean maybeStale = false;

    ExpiringCachedDocCollection(DocCollection cached) {
      this.cached = cached;
      this.cachedAtNano = System.nanoTime();
    }

    boolean isExpired(long timeToLiveMs) {
      return (System.nanoTime() - cachedAtNano)
          > TimeUnit.NANOSECONDS.convert(timeToLiveMs, TimeUnit.MILLISECONDS);
    }

    boolean shouldRetry() {
      if (maybeStale) { // we are not sure if it is stale so check with retry time
        if ((retriedAtNano == -1 || (System.nanoTime() - retriedAtNano) > retryExpiryTimeNano)) {
          return true; // we retried a while back. and we could not get anything new.
          // it's likely that it is not going to be available now also.
        }
      }
      return false;
    }

    void setRetriedAt() {
      retriedAtNano = System.nanoTime();
    }
  }

  protected CloudSolrClient(
      boolean updatesToLeaders, boolean parallelUpdates, boolean directUpdatesToLeadersOnly) {
    this.updatesToLeaders = updatesToLeaders;
    this.parallelUpdates = parallelUpdates;
    this.directUpdatesToLeadersOnly = directUpdatesToLeadersOnly;
    this.requestRLTGenerator = new RequestReplicaListTransformerGenerator();
  }

  /**
   * Sets the cache ttl for DocCollection Objects cached.
   *
   * @param seconds ttl value in seconds
   * @deprecated use {@link CloudSolrClient.Builder#withCollectionCacheTtl(int)} instead
   */
  @Deprecated
  public void setCollectionCacheTTl(int seconds) {
    assert seconds > 0;
    this.collectionStateCache.timeToLiveMs = seconds * 1000L;
  }

  protected abstract LBSolrClient getLbClient();

  public abstract ClusterStateProvider getClusterStateProvider();

  public ClusterState getClusterState() {
    return getClusterStateProvider().getClusterState();
  }

  protected abstract boolean wasCommError(Throwable t);

  @Override
  public void close() throws IOException {
    if (this.threadPool != null && !ExecutorUtil.isShutdown(this.threadPool)) {
      ExecutorUtil.shutdownAndAwaitTermination(this.threadPool);
      this.threadPool = null;
    }
  }

  public ResponseParser getParser() {
    return getLbClient().getParser();
  }

  /**
   * Note: This setter method is <b>not thread-safe</b>.
   *
   * @param processor Default Response Parser chosen to parse the response if the parser were not
   *     specified as part of the request.
   * @see org.apache.solr.client.solrj.SolrRequest#getResponseParser()
   * @deprecated use {@link CloudHttp2SolrClient.Builder} instead
   */
  @Deprecated
  public void setParser(ResponseParser processor) {
    getLbClient().setParser(processor);
  }

  public RequestWriter getRequestWriter() {
    return getLbClient().getRequestWriter();
  }

  /**
   * Choose the {@link RequestWriter} to use.
   *
   * <p>Note: This setter method is <b>not thread-safe</b>.
   *
   * @deprecated use {@link CloudHttp2SolrClient.Builder} instead
   */
  @Deprecated
  public void setRequestWriter(RequestWriter requestWriter) {
    getLbClient().setRequestWriter(requestWriter);
  }

  /**
   * Sets the default collection for request.
   *
   * @deprecated use {@link CloudHttp2SolrClient.Builder#withDefaultCollection(String)} instead
   */
  @Deprecated
  public void setDefaultCollection(String collection) {
    this.defaultCollection = collection;
  }

  /** Gets whether direct updates are sent in parallel */
  public boolean isParallelUpdates() {
    return parallelUpdates;
  }

  /**
   * Connect to the zookeeper ensemble. This is an optional method that may be used to force a
   * connection before any other requests are sent.
   */
  public void connect() {
    getClusterStateProvider().connect();
  }

  /**
   * Connect to a cluster. If the cluster is not ready, retry connection up to a given timeout.
   *
   * @param duration the timeout
   * @param timeUnit the units of the timeout
   * @throws TimeoutException if the cluster is not ready after the timeout
   * @throws InterruptedException if the wait is interrupted
   */
  public void connect(long duration, TimeUnit timeUnit)
      throws TimeoutException, InterruptedException {
    if (log.isInfoEnabled()) {
      log.info(
          "Waiting for {} {} for cluster at {} to be ready",
          duration,
          timeUnit,
          getClusterStateProvider());
    }
    long timeout = System.nanoTime() + timeUnit.toNanos(duration);
    while (System.nanoTime() < timeout) {
      try {
        connect();
        if (log.isInfoEnabled()) {
          log.info("Cluster at {} ready", getClusterStateProvider());
        }
        return;
      } catch (RuntimeException e) {
        // not ready yet, then...
      }
      TimeUnit.MILLISECONDS.sleep(250);
    }
    throw new TimeoutException("Timed out waiting for cluster");
  }

  @SuppressWarnings({"unchecked"})
  private NamedList<Object> directUpdate(AbstractUpdateRequest request, String collection)
      throws SolrServerException {
    UpdateRequest updateRequest = (UpdateRequest) request;
    SolrParams params = request.getParams();
    ModifiableSolrParams routableParams = new ModifiableSolrParams();
    ModifiableSolrParams nonRoutableParams = new ModifiableSolrParams();

    if (params != null) {
      nonRoutableParams.add(params);
      routableParams.add(params);
      for (String param : NON_ROUTABLE_PARAMS) {
        routableParams.remove(param);
      }
    } else {
      params = new ModifiableSolrParams();
    }

    if (collection == null) {
      throw new SolrServerException(
          "No collection param specified on request and no default collection has been set.");
    }

    // Check to see if the collection is an alias. Updates to multi-collection aliases are ok as
    // long as they are routed aliases
    List<String> aliasedCollections =
        new ArrayList<>(resolveAliases(Collections.singletonList(collection)));
    if (aliasedCollections.size() == 1 || getClusterStateProvider().isRoutedAlias(collection)) {
      collection = aliasedCollections.get(0); // pick 1st (consistent with HttpSolrCall behavior)
    } else {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Update request to non-routed multi-collection alias not supported: "
              + collection
              + " -> "
              + aliasedCollections);
    }

    DocCollection col = getDocCollection(collection, null);

    DocRouter router = col.getRouter();

    if (router instanceof ImplicitDocRouter) {
      // short circuit as optimization
      return null;
    }

    ReplicaListTransformer replicaListTransformer =
        requestRLTGenerator.getReplicaListTransformer(params);

    // Create the URL map, which is keyed on slice name.
    // The value is a list of URLs for each replica in the slice.
    // The first value in the list is the leader for the slice.
    final Map<String, List<String>> urlMap = buildUrlMap(col, replicaListTransformer);
    String routeField =
        (col.getRouter().getRouteField(col) == null) ? ID : col.getRouter().getRouteField(col);
    final Map<String, ? extends LBSolrClient.Req> routes =
        createRoutes(updateRequest, routableParams, col, router, urlMap, routeField);
    if (routes == null) {
      if (directUpdatesToLeadersOnly && hasInfoToFindLeaders(updateRequest, routeField)) {
        // we have info (documents with ids and/or ids to delete) with
        // which to find the leaders, but we could not find (all of) them
        throw new SolrException(
            SolrException.ErrorCode.SERVICE_UNAVAILABLE,
            "directUpdatesToLeadersOnly==true but could not find leader(s)");
      } else {
        // we could not find a leader or routes yet - use unoptimized general path
        return null;
      }
    }

    final NamedList<Throwable> exceptions = new NamedList<>();
    final NamedList<NamedList<?>> shardResponses =
        new NamedList<>(routes.size() + 1); // +1 for deleteQuery

    long start = System.nanoTime();

    if (parallelUpdates) {
      final Map<String, Future<NamedList<?>>> responseFutures =
          CollectionUtil.newHashMap(routes.size());
      for (final Map.Entry<String, ? extends LBSolrClient.Req> entry : routes.entrySet()) {
        final String url = entry.getKey();
        final LBSolrClient.Req lbRequest = entry.getValue();
        try {
          MDC.put("CloudSolrClient.url", url);
          responseFutures.put(
              url,
              threadPool.submit(
                  () -> {
                    return getLbClient().request(lbRequest).getResponse();
                  }));
        } finally {
          MDC.remove("CloudSolrClient.url");
        }
      }

      for (final Map.Entry<String, Future<NamedList<?>>> entry : responseFutures.entrySet()) {
        final String url = entry.getKey();
        final Future<NamedList<?>> responseFuture = entry.getValue();
        try {
          shardResponses.add(url, responseFuture.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        } catch (ExecutionException e) {
          exceptions.add(url, e.getCause());
        }
      }

      if (exceptions.size() > 0) {
        Throwable firstException = exceptions.getVal(0);
        if (firstException instanceof SolrException) {
          SolrException e = (SolrException) firstException;
          throw getRouteException(
              SolrException.ErrorCode.getErrorCode(e.code()), exceptions, routes);
        } else {
          throw getRouteException(SolrException.ErrorCode.SERVER_ERROR, exceptions, routes);
        }
      }
    } else {
      for (Map.Entry<String, ? extends LBSolrClient.Req> entry : routes.entrySet()) {
        String url = entry.getKey();
        LBSolrClient.Req lbRequest = entry.getValue();
        try {
          NamedList<Object> rsp = getLbClient().request(lbRequest).getResponse();
          shardResponses.add(url, rsp);
        } catch (Exception e) {
          if (e instanceof SolrException) {
            throw (SolrException) e;
          } else {
            throw new SolrServerException(e);
          }
        }
      }
    }

    UpdateRequest nonRoutableRequest = null;
    List<String> deleteQuery = updateRequest.getDeleteQuery();
    if (deleteQuery != null && deleteQuery.size() > 0) {
      UpdateRequest deleteQueryRequest = new UpdateRequest();
      deleteQueryRequest.setDeleteQuery(deleteQuery);
      nonRoutableRequest = deleteQueryRequest;
    }

    Set<String> paramNames = nonRoutableParams.getParameterNames();

    Set<String> intersection = new HashSet<>(paramNames);
    intersection.retainAll(NON_ROUTABLE_PARAMS);

    if (nonRoutableRequest != null || intersection.size() > 0) {
      if (nonRoutableRequest == null) {
        nonRoutableRequest = new UpdateRequest();
      }
      nonRoutableRequest.setParams(nonRoutableParams);
      nonRoutableRequest.setBasicAuthCredentials(
          request.getBasicAuthUser(), request.getBasicAuthPassword());
      List<String> urlList = new ArrayList<>(routes.keySet());
      Collections.shuffle(urlList, rand);
      LBSolrClient.Req req = new LBSolrClient.Req(nonRoutableRequest, urlList);
      try {
        LBSolrClient.Rsp rsp = getLbClient().request(req);
        shardResponses.add(urlList.get(0), rsp.getResponse());
      } catch (Exception e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, urlList.get(0), e);
      }
    }

    long end = System.nanoTime();

    @SuppressWarnings({"rawtypes"})
    RouteResponse rr =
        condenseResponse(
            shardResponses, (int) TimeUnit.MILLISECONDS.convert(end - start, TimeUnit.NANOSECONDS));
    rr.setRouteResponses(shardResponses);
    rr.setRoutes(routes);
    return rr;
  }

  protected RouteException getRouteException(
      SolrException.ErrorCode serverError,
      NamedList<Throwable> exceptions,
      Map<String, ? extends LBSolrClient.Req> routes) {
    return new RouteException(serverError, exceptions, routes);
  }

  protected Map<String, ? extends LBSolrClient.Req> createRoutes(
      UpdateRequest updateRequest,
      ModifiableSolrParams routableParams,
      DocCollection col,
      DocRouter router,
      Map<String, List<String>> urlMap,
      String routeField) {
    return urlMap == null
        ? null
        : updateRequest.getRoutesToCollection(router, col, urlMap, routableParams, routeField);
  }

  private Map<String, List<String>> buildUrlMap(
      DocCollection col, ReplicaListTransformer replicaListTransformer) {
    Map<String, List<String>> urlMap = new HashMap<>();
    Slice[] slices = col.getActiveSlicesArr();
    for (Slice slice : slices) {
      String name = slice.getName();
      List<Replica> sortedReplicas = new ArrayList<>();
      Replica leader = slice.getLeader();
      if (directUpdatesToLeadersOnly && leader == null) {
        for (Replica replica :
            slice.getReplicas(
                replica ->
                    replica.isActive(getClusterStateProvider().getLiveNodes())
                        && replica.getType() == Replica.Type.NRT)) {
          leader = replica;
          break;
        }
      }
      if (leader == null) {
        if (directUpdatesToLeadersOnly) {
          continue;
        }
        // take unoptimized general path - we cannot find a leader yet
        return null;
      }

      if (!directUpdatesToLeadersOnly) {
        for (Replica replica : slice.getReplicas()) {
          if (!replica.equals(leader)) {
            sortedReplicas.add(replica);
          }
        }
      }

      // Sort the non-leader replicas according to the request parameters
      replicaListTransformer.transform(sortedReplicas);

      // put the leaderUrl first.
      sortedReplicas.add(0, leader);

      urlMap.put(
          name, sortedReplicas.stream().map(Replica::getCoreUrl).collect(Collectors.toList()));
    }
    return urlMap;
  }

  protected <T extends RouteResponse<?>> T condenseResponse(
      NamedList<?> response, int timeMillis, Supplier<T> supplier) {
    T condensed = supplier.get();
    int status = 0;
    Integer rf = null;

    // TolerantUpdateProcessor
    List<SimpleOrderedMap<String>> toleratedErrors = null;
    int maxToleratedErrors = Integer.MAX_VALUE;

    // For "adds", "deletes", "deleteByQuery" etc.
    Map<String, NamedList<Object>> versions = new HashMap<>();

    for (int i = 0; i < response.size(); i++) {
      NamedList<?> shardResponse = (NamedList<?>) response.getVal(i);
      NamedList<?> header = (NamedList<?>) shardResponse.get("responseHeader");
      Integer shardStatus = (Integer) header.get("status");
      int s = shardStatus.intValue();
      if (s > 0) {
        status = s;
      }
      Object rfObj = header.get(UpdateRequest.REPFACT);
      if (rfObj != null && rfObj instanceof Integer) {
        Integer routeRf = (Integer) rfObj;
        if (rf == null || routeRf < rf) rf = routeRf;
      }

      @SuppressWarnings("unchecked")
      List<SimpleOrderedMap<String>> shardTolerantErrors =
          (List<SimpleOrderedMap<String>>) header.get("errors");
      if (null != shardTolerantErrors) {
        Integer shardMaxToleratedErrors = (Integer) header.get("maxErrors");
        assert null != shardMaxToleratedErrors
            : "TolerantUpdateProcessor reported errors but not maxErrors";
        // if we get into some weird state where the nodes disagree about the effective maxErrors,
        // assume the min value seen to decide if we should fail.
        maxToleratedErrors =
            Math.min(
                maxToleratedErrors,
                ToleratedUpdateError.getEffectiveMaxErrors(shardMaxToleratedErrors.intValue()));

        if (null == toleratedErrors) {
          toleratedErrors = new ArrayList<SimpleOrderedMap<String>>(shardTolerantErrors.size());
        }
        for (SimpleOrderedMap<String> err : shardTolerantErrors) {
          toleratedErrors.add(err);
        }
      }
      for (String updateType : Arrays.asList("adds", "deletes", "deleteByQuery")) {
        Object obj = shardResponse.get(updateType);
        if (obj instanceof NamedList) {
          NamedList<Object> versionsList =
              versions.containsKey(updateType) ? versions.get(updateType) : new NamedList<>();
          NamedList<?> nl = (NamedList<?>) obj;
          versionsList.addAll(nl);
          versions.put(updateType, versionsList);
        }
      }
    }

    NamedList<Object> cheader = new NamedList<>();
    cheader.add("status", status);
    cheader.add("QTime", timeMillis);
    if (rf != null) cheader.add(UpdateRequest.REPFACT, rf);
    if (null != toleratedErrors) {
      cheader.add("maxErrors", ToleratedUpdateError.getUserFriendlyMaxErrors(maxToleratedErrors));
      cheader.add("errors", toleratedErrors);
      if (maxToleratedErrors < toleratedErrors.size()) {
        // cumulative errors are too high, we need to throw a client exception w/correct metadata

        // NOTE: it shouldn't be possible for 1 == toleratedErrors.size(), because if that were the
        // case then at least one shard should have thrown a real error before this, so we don't
        // worry about having a more "singular" exception msg for that situation
        StringBuilder msgBuf =
            new StringBuilder()
                .append(toleratedErrors.size())
                .append(" Async failures during distributed update: ");

        NamedList<String> metadata = new NamedList<>();
        for (SimpleOrderedMap<String> err : toleratedErrors) {
          ToleratedUpdateError te = ToleratedUpdateError.parseMap(err);
          metadata.add(te.getMetadataKey(), te.getMetadataValue());

          msgBuf.append("\n").append(te.getMessage());
        }

        SolrException toThrow =
            new SolrException(SolrException.ErrorCode.BAD_REQUEST, msgBuf.toString());
        toThrow.setMetadata(metadata);
        throw toThrow;
      }
    }
    for (Map.Entry<String, NamedList<Object>> entry : versions.entrySet()) {
      condensed.add(entry.getKey(), entry.getValue());
    }
    condensed.add("responseHeader", cheader);
    return condensed;
  }

  @SuppressWarnings({"rawtypes"})
  public RouteResponse condenseResponse(NamedList<?> response, int timeMillis) {
    return condenseResponse(response, timeMillis, RouteResponse::new);
  }

  @SuppressWarnings({"rawtypes"})
  public static class RouteResponse<T extends LBSolrClient.Req> extends NamedList<Object> {
    private NamedList<NamedList<?>> routeResponses;
    private Map<String, T> routes;

    public void setRouteResponses(NamedList<NamedList<?>> routeResponses) {
      this.routeResponses = routeResponses;
    }

    public NamedList<NamedList<?>> getRouteResponses() {
      return routeResponses;
    }

    public void setRoutes(Map<String, T> routes) {
      this.routes = routes;
    }

    public Map<String, T> getRoutes() {
      return routes;
    }
  }

  public static class RouteException extends SolrException {

    private NamedList<Throwable> throwables;
    private Map<String, ? extends LBSolrClient.Req> routes;

    public RouteException(
        ErrorCode errorCode,
        NamedList<Throwable> throwables,
        Map<String, ? extends LBSolrClient.Req> routes) {
      super(errorCode, throwables.getVal(0).getMessage(), throwables.getVal(0));
      this.throwables = throwables;
      this.routes = routes;

      // create a merged copy of the metadata from all wrapped exceptions
      NamedList<String> metadata = new NamedList<String>();
      for (int i = 0; i < throwables.size(); i++) {
        Throwable t = throwables.getVal(i);
        if (t instanceof SolrException) {
          SolrException e = (SolrException) t;
          NamedList<String> eMeta = e.getMetadata();
          if (null != eMeta) {
            metadata.addAll(eMeta);
          }
        }
      }
      if (0 < metadata.size()) {
        this.setMetadata(metadata);
      }
    }

    public NamedList<Throwable> getThrowables() {
      return throwables;
    }

    public Map<String, ? extends LBSolrClient.Req> getRoutes() {
      return this.routes;
    }
  }

  @Override
  public NamedList<Object> request(SolrRequest<?> request, String collection)
      throws SolrServerException, IOException {
    // the collection parameter of the request overrides that of the parameter to this method
    String requestCollection = request.getCollection();
    if (requestCollection != null) {
      collection = requestCollection;
    } else if (collection == null) {
      collection = defaultCollection;
    }

    List<String> inputCollections =
        collection == null ? Collections.emptyList() : StrUtils.splitSmart(collection, ",", true);
    return requestWithRetryOnStaleState(request, 0, inputCollections);
  }

  /**
   * As this class doesn't watch external collections on the client side, there's a chance that the
   * request will fail due to cached stale state, which means the state must be refreshed from ZK
   * and retried.
   */
  protected NamedList<Object> requestWithRetryOnStaleState(
      SolrRequest<?> request, int retryCount, List<String> inputCollections)
      throws SolrServerException, IOException {
    connect(); // important to call this before you start working with the ZkStateReader

    // build up a _stateVer_ param to pass to the server containing all the
    // external collection state versions involved in this request, which allows
    // the server to notify us that our cached state for one or more of the external
    // collections is stale and needs to be refreshed ... this code has no impact on internal
    // collections
    String stateVerParam = null;
    List<DocCollection> requestedCollections = null;
    boolean isCollectionRequestOfV2 = false;
    if (request instanceof V2RequestSupport) {
      request = ((V2RequestSupport) request).getV2Request();
    }
    if (request instanceof V2Request) {
      isCollectionRequestOfV2 = ((V2Request) request).isPerCollectionRequest();
    }
    boolean isAdmin = ADMIN_PATHS.contains(request.getPath());
    if (!inputCollections.isEmpty()
        && !isAdmin
        && !isCollectionRequestOfV2) { // don't do _stateVer_ checking for admin, v2 api requests
      Set<String> requestedCollectionNames = resolveAliases(inputCollections);

      StringBuilder stateVerParamBuilder = null;
      for (String requestedCollection : requestedCollectionNames) {
        // track the version of state we're using on the client side using the _stateVer_ param
        DocCollection coll = getDocCollection(requestedCollection, null);
        if (coll == null) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST, "Collection not found: " + requestedCollection);
        }
        int collVer = coll.getZNodeVersion();
        if (requestedCollections == null)
          requestedCollections = new ArrayList<>(requestedCollectionNames.size());
        requestedCollections.add(coll);

        if (stateVerParamBuilder == null) {
          stateVerParamBuilder = new StringBuilder();
        } else {
          stateVerParamBuilder.append(
              "|"); // hopefully pipe is not an allowed char in a collection name
        }

        stateVerParamBuilder.append(coll.getName()).append(":").append(collVer);
      }

      if (stateVerParamBuilder != null) {
        stateVerParam = stateVerParamBuilder.toString();
      }
    }

    if (request.getParams() instanceof ModifiableSolrParams) {
      ModifiableSolrParams params = (ModifiableSolrParams) request.getParams();
      if (stateVerParam != null) {
        params.set(STATE_VERSION, stateVerParam);
      } else {
        params.remove(STATE_VERSION);
      }
    } // else: ??? how to set this ???

    NamedList<Object> resp = null;
    try {
      resp = sendRequest(request, inputCollections);
      // to avoid an O(n) operation we always add STATE_VERSION to the last and try to read it from
      // there
      Object o = resp == null || resp.size() == 0 ? null : resp.get(STATE_VERSION, resp.size() - 1);
      if (o != null && o instanceof Map) {
        // remove this because no one else needs this and tests would fail if they are comparing
        // responses
        resp.remove(resp.size() - 1);
        Map<?, ?> invalidStates = (Map<?, ?>) o;
        for (Map.Entry<?, ?> e : invalidStates.entrySet()) {
          getDocCollection((String) e.getKey(), (Integer) e.getValue());
        }
      }
    } catch (Exception exc) {

      Throwable rootCause = SolrException.getRootCause(exc);
      // don't do retry support for admin requests
      // or if the request doesn't have a collection specified
      // or request is v2 api and its method is not GET
      if (inputCollections.isEmpty()
          || isAdmin
          || (request.getApiVersion() == SolrRequest.ApiVersion.V2
              && request.getMethod() != SolrRequest.METHOD.GET)) {
        if (exc instanceof SolrServerException) {
          throw (SolrServerException) exc;
        } else if (exc instanceof IOException) {
          throw (IOException) exc;
        } else if (exc instanceof RuntimeException) {
          throw (RuntimeException) exc;
        } else {
          throw new SolrServerException(rootCause);
        }
      }

      int errorCode =
          (rootCause instanceof SolrException)
              ? ((SolrException) rootCause).code()
              : SolrException.ErrorCode.UNKNOWN.code;

      boolean wasCommError =
          (rootCause instanceof ConnectException
              || rootCause instanceof SocketException
              || wasCommError(rootCause));

      if (wasCommError
          || (exc instanceof RouteException
              && (errorCode == 503)) // 404 because the core does not exist 503 service unavailable
      // TODO there are other reasons for 404. We need to change the solr response format from HTML
      // to structured data to know that
      ) {
        // it was a communication error. it is likely that
        // the node to which the request to be sent is down . So , expire the state
        // so that the next attempt would fetch the fresh state
        // just re-read state for all of them, if it has not been retried
        // in retryExpiryTime time
        if (requestedCollections != null) {
          for (DocCollection ext : requestedCollections) {
            ExpiringCachedDocCollection cacheEntry = collectionStateCache.get(ext.getName());
            if (cacheEntry == null) continue;
            cacheEntry.maybeStale = true;
          }
        }
        if (retryCount < MAX_STALE_RETRIES) { // if it is a communication error , we must try again
          // may be, we have a stale version of the collection state,
          // and we could not get any information from the server
          // it is probably not worth trying again and again because
          // the state would not have been updated
          log.info(
              "Request to collection {} failed due to ({}) {}, retry={} maxRetries={} commError={} errorCode={} - retrying",
              inputCollections,
              errorCode,
              rootCause,
              retryCount,
              MAX_STALE_RETRIES,
              wasCommError,
              errorCode);
          return requestWithRetryOnStaleState(request, retryCount + 1, inputCollections);
        }
      } else {
        log.info("request was not communication error it seems");
      }
      log.info(
          "Request to collection {} failed due to ({}) {}, retry={} maxRetries={} commError={} errorCode={} ",
          inputCollections,
          errorCode,
          rootCause,
          retryCount,
          MAX_STALE_RETRIES,
          wasCommError,
          errorCode);

      boolean stateWasStale = false;
      if (retryCount < MAX_STALE_RETRIES
          && requestedCollections != null
          && !requestedCollections.isEmpty()
          && (SolrException.ErrorCode.getErrorCode(errorCode)
                  == SolrException.ErrorCode.INVALID_STATE
              || errorCode == 404)) {
        // cached state for one or more external collections was stale
        // re-issue request using updated state
        stateWasStale = true;

        // just re-read state for all of them, which is a little heavy-handed but hopefully a rare
        // occurrence
        for (DocCollection ext : requestedCollections) {
          collectionStateCache.remove(ext.getName());
        }
      }

      // if we experienced a communication error, it's worth checking the state
      // with ZK just to make sure the node we're trying to hit is still part of the collection
      if (retryCount < MAX_STALE_RETRIES
          && !stateWasStale
          && requestedCollections != null
          && !requestedCollections.isEmpty()
          && wasCommError) {
        for (DocCollection ext : requestedCollections) {
          DocCollection latestStateFromZk = getDocCollection(ext.getName(), null);
          if (latestStateFromZk.getZNodeVersion() != ext.getZNodeVersion()) {
            // looks like we couldn't reach the server because the state was stale == retry
            stateWasStale = true;
            // we just pulled state from ZK, so update the cache so that the retry uses it
            collectionStateCache.put(
                ext.getName(), new ExpiringCachedDocCollection(latestStateFromZk));
          }
        }
      }

      if (requestedCollections != null) {
        requestedCollections.clear(); // done with this
      }

      // if the state was stale, then we retry the request once with new state pulled from Zk
      if (stateWasStale) {
        log.warn(
            "Re-trying request to collection(s) {} after stale state error from server.",
            inputCollections);
        resp = requestWithRetryOnStaleState(request, retryCount + 1, inputCollections);
      } else {
        if (exc instanceof SolrException
            || exc instanceof SolrServerException
            || exc instanceof IOException) {
          throw exc;
        } else {
          throw new SolrServerException(rootCause);
        }
      }
    }

    return resp;
  }

  protected NamedList<Object> sendRequest(SolrRequest<?> request, List<String> inputCollections)
      throws SolrServerException, IOException {
    connect();

    boolean sendToLeaders = false;

    if (request instanceof IsUpdateRequest) {
      sendToLeaders = ((IsUpdateRequest) request).isSendToLeaders() && this.isUpdatesToLeaders();

      // Check if we can do a "directUpdate" ...
      if (sendToLeaders && request instanceof UpdateRequest) {
        if (inputCollections.size() > 1) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "Update request must be sent to a single collection "
                  + "or an alias: "
                  + inputCollections);
        }
        String collection =
            inputCollections.isEmpty()
                ? null
                : inputCollections.get(0); // getting first mimics HttpSolrCall
        NamedList<Object> response = directUpdate((AbstractUpdateRequest) request, collection);
        if (response != null) {
          return response;
        }
      }
    }

    SolrParams reqParams = request.getParams();
    if (reqParams == null) { // TODO fix getParams to never return null!
      reqParams = new ModifiableSolrParams();
    }

    ReplicaListTransformer replicaListTransformer =
        requestRLTGenerator.getReplicaListTransformer(reqParams);

    final ClusterStateProvider provider = getClusterStateProvider();
    final String urlScheme = provider.getClusterProperty(ClusterState.URL_SCHEME, "http");
    final Set<String> liveNodes = provider.getLiveNodes();

    final List<String> theUrlList = new ArrayList<>(); // we populate this as follows...

    if (request.getApiVersion() == SolrRequest.ApiVersion.V2) {
      if (!liveNodes.isEmpty()) {
        List<String> liveNodesList = new ArrayList<>(liveNodes);
        Collections.shuffle(liveNodesList, rand);
        theUrlList.add(Utils.getBaseUrlForNodeName(liveNodesList.get(0), urlScheme));
      }

    } else if (ADMIN_PATHS.contains(request.getPath())) {
      for (String liveNode : liveNodes) {
        theUrlList.add(Utils.getBaseUrlForNodeName(liveNode, urlScheme));
      }

    } else { // Typical...
      Set<String> collectionNames = resolveAliases(inputCollections);
      if (collectionNames.isEmpty()) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "No collection param specified on request and no default collection has been set: "
                + inputCollections);
      }

      List<String> preferredNodes = request.getPreferredNodes();
      if (preferredNodes != null && !preferredNodes.isEmpty()) {
        String joinedInputCollections = StrUtils.join(inputCollections, ',');
        List<String> urlList = new ArrayList<>(preferredNodes.size());
        for (String nodeName : preferredNodes) {
          urlList.add(
              Utils.getBaseUrlForNodeName(nodeName, urlScheme) + "/" + joinedInputCollections);
        }
        if (!urlList.isEmpty()) {
          LBSolrClient.Req req = new LBSolrClient.Req(request, urlList);
          LBSolrClient.Rsp rsp = getLbClient().request(req);
          return rsp.getResponse();
        }
      }

      // TODO: not a big deal because of the caching, but we could avoid looking
      //   at every shard when getting leaders if we tweaked some things

      // Retrieve slices from the cloud state and, for each collection specified, add it to the Map
      // of slices.
      Map<String, Slice> slices = new HashMap<>();
      String shardKeys = reqParams.get(ShardParams._ROUTE_);
      for (String collectionName : collectionNames) {
        DocCollection col = getDocCollection(collectionName, null);
        if (col == null) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST, "Collection not found: " + collectionName);
        }
        Collection<Slice> routeSlices = col.getRouter().getSearchSlices(shardKeys, reqParams, col);
        ClientUtils.addSlices(slices, collectionName, routeSlices, true);
      }

      // Gather URLs, grouped by leader or replica
      List<Replica> sortedReplicas = new ArrayList<>();
      List<Replica> replicas = new ArrayList<>();
      for (Slice slice : slices.values()) {
        Replica leader = slice.getLeader();
        for (Replica replica : slice.getReplicas()) {
          String node = replica.getNodeName();
          if (!liveNodes.contains(node) // Must be a live node to continue
              || replica.getState()
                  != Replica.State.ACTIVE) { // Must be an ACTIVE replica to continue
            continue;
          }
          if (sendToLeaders && replica.equals(leader)) {
            sortedReplicas.add(replica); // put leaders here eagerly (if sendToLeader mode)
          } else {
            replicas.add(replica); // replicas here
          }
        }
      }

      // Sort the leader replicas, if any, according to the request preferences    (none if
      // !sendToLeaders)
      replicaListTransformer.transform(sortedReplicas);

      // Sort the replicas, if any, according to the request preferences and append to our list
      replicaListTransformer.transform(replicas);

      sortedReplicas.addAll(replicas);

      String joinedInputCollections = StrUtils.join(inputCollections, ',');
      Set<String> seenNodes = new HashSet<>();
      sortedReplicas.forEach(
          replica -> {
            if (seenNodes.add(replica.getNodeName())) {
              if (inputCollections.size() == 1 && collectionNames.size() == 1) {
                // If we have a single collection name (and not an alias to multiple collection),
                // send the query directly to a replica of this collection.
                theUrlList.add(replica.getCoreUrl());
              } else {
                theUrlList.add(
                    ZkCoreNodeProps.getCoreUrl(replica.getBaseUrl(), joinedInputCollections));
              }
            }
          });

      if (theUrlList.isEmpty()) {
        collectionStateCache.keySet().removeAll(collectionNames);
        throw new SolrException(
            SolrException.ErrorCode.INVALID_STATE,
            "Could not find a healthy node to handle the request.");
      }
    }

    LBSolrClient.Req req = new LBSolrClient.Req(request, theUrlList);
    LBSolrClient.Rsp rsp = getLbClient().request(req);
    return rsp.getResponse();
  }

  /**
   * Resolves the input collections to their possible aliased collections. Doesn't validate
   * collection existence.
   */
  private Set<String> resolveAliases(List<String> inputCollections) {
    if (inputCollections.isEmpty()) {
      return Collections.emptySet();
    }
    LinkedHashSet<String> uniqueNames = new LinkedHashSet<>(); // consistent ordering
    for (String collectionName : inputCollections) {
      if (getDocCollection(collectionName, -1) == null) {
        // perhaps it's an alias
        uniqueNames.addAll(getClusterStateProvider().resolveAlias(collectionName));
      } else {
        uniqueNames.add(collectionName); // it's a collection
      }
    }
    return uniqueNames;
  }

  /**
   * If true, this client has been configured such that it will generally prefer to send {@link
   * IsUpdateRequest} requests to a shard leader, if and only if {@link
   * IsUpdateRequest#isSendToLeaders} is also true. If false, then this client has been configured
   * to obey normal routing preferences when dealing with {@link IsUpdateRequest} requests.
   *
   * @see #isDirectUpdatesToLeadersOnly
   */
  public boolean isUpdatesToLeaders() {
    return updatesToLeaders;
  }

  /**
   * If true, this client has been configured such that "direct updates" will <em>only</em> be sent
   * to the current leader of the corresponding shard, and will not be retried with other replicas.
   * This method has no effect if {@link #isUpdatesToLeaders()} or {@link
   * IsUpdateRequest#isSendToLeaders} returns false.
   *
   * <p>A "direct update" is any update that can be sent directly to a single shard, and does not
   * need to be broadcast to every shard. (Example: document updates or "delete by id" when using
   * the default router; non-direct updates are things like commits and "delete by query").
   *
   * <p>NOTE: If a single {@link UpdateRequest} contains multiple "direct updates" for different
   * shards, this client may break the request up and merge the responses.
   *
   * @return true if direct updates are sent to shard leaders only
   */
  public boolean isDirectUpdatesToLeadersOnly() {
    return directUpdatesToLeadersOnly;
  }

  /**
   * If caches are expired they are refreshed after acquiring a lock. use this to set the number of
   * locks
   *
   * @deprecated use {@link CloudHttp2SolrClient.Builder#setParallelCacheRefreshes(int)} instead
   */
  @Deprecated
  public void setParallelCacheRefreshes(int n) {
    locks = objectList(n);
  }

  protected static Object[] objectList(int n) {
    Object[] l = new Object[n];
    for (int i = 0; i < n; i++) {
      l[i] = new Object();
    }
    return l;
  }

  protected DocCollection getDocCollection(String collection, Integer expectedVersion)
      throws SolrException {
    if (expectedVersion == null) expectedVersion = -1;
    if (collection == null) return null;
    ExpiringCachedDocCollection cacheEntry = collectionStateCache.get(collection);
    DocCollection col = cacheEntry == null ? null : cacheEntry.cached;
    if (col != null) {
      if (expectedVersion <= col.getZNodeVersion() && !cacheEntry.shouldRetry()) return col;
    }

    Object[] locks = this.locks;
    int lockId =
        Math.abs(Hash.murmurhash3_x86_32(collection, 0, collection.length(), 0) % locks.length);
    final Object lock = locks[lockId];
    synchronized (lock) {
      /*we have waited for some time just check once again*/
      cacheEntry = collectionStateCache.get(collection);
      col = cacheEntry == null ? null : cacheEntry.cached;
      if (col != null) {
        if (expectedVersion <= col.getZNodeVersion() && !cacheEntry.shouldRetry()) return col;
      }
      ClusterState.CollectionRef ref = getCollectionRef(collection);
      if (ref == null) {
        // no such collection exists
        return null;
      }
      // We are going to fetch a new version
      // we MUST try to get a new version
      DocCollection fetchedCol = ref.get(); // this is a call to ZK
      if (fetchedCol == null) return null; // this collection no more exists
      if (col != null && fetchedCol.getZNodeVersion() == col.getZNodeVersion()) {
        cacheEntry.setRetriedAt(); // we retried and found that it is the same version
        cacheEntry.maybeStale = false;
      } else {
        collectionStateCache.put(collection, new ExpiringCachedDocCollection(fetchedCol));
      }
      return fetchedCol;
    }
  }

  ClusterState.CollectionRef getCollectionRef(String collection) {
    return getClusterStateProvider().getState(collection);
  }

  /**
   * Useful for determining the minimum achieved replication factor across all shards involved in
   * processing an update request, typically useful for gauging the replication factor of a batch.
   */
  public int getMinAchievedReplicationFactor(String collection, NamedList<?> resp) {
    // it's probably already on the top-level header set by condense
    NamedList<?> header = (NamedList<?>) resp.get("responseHeader");
    Integer achRf = (Integer) header.get(UpdateRequest.REPFACT);
    if (achRf != null) return achRf.intValue();

    // not on the top-level header, walk the shard route tree
    Map<String, Integer> shardRf = getShardReplicationFactor(collection, resp);
    for (Integer rf : shardRf.values()) {
      if (achRf == null || rf < achRf) {
        achRf = rf;
      }
    }
    return (achRf != null) ? achRf.intValue() : -1;
  }

  /**
   * Walks the NamedList response after performing an update request looking for the replication
   * factor that was achieved in each shard involved in the request. For single doc updates, there
   * will be only one shard in the return value.
   */
  public Map<String, Integer> getShardReplicationFactor(String collection, NamedList<?> resp) {
    connect();

    Map<String, Integer> results = new HashMap<>();
    if (resp instanceof RouteResponse) {
      NamedList<NamedList<?>> routes = ((RouteResponse<?>) resp).getRouteResponses();
      DocCollection coll = getDocCollection(collection, null);
      Map<String, String> leaders = new HashMap<>();
      for (Slice slice : coll.getActiveSlicesArr()) {
        Replica leader = slice.getLeader();
        if (leader != null) {
          ZkCoreNodeProps zkProps = new ZkCoreNodeProps(leader);
          String leaderUrl = zkProps.getBaseUrl() + "/" + zkProps.getCoreName();
          leaders.put(leaderUrl, slice.getName());
          String altLeaderUrl = zkProps.getBaseUrl() + "/" + collection;
          leaders.put(altLeaderUrl, slice.getName());
        }
      }

      Iterator<Map.Entry<String, NamedList<?>>> routeIter = routes.iterator();
      while (routeIter.hasNext()) {
        Map.Entry<String, NamedList<?>> next = routeIter.next();
        String host = next.getKey();
        NamedList<?> hostResp = next.getValue();
        Integer rf =
            (Integer) ((NamedList<?>) hostResp.get("responseHeader")).get(UpdateRequest.REPFACT);
        if (rf != null) {
          String shard = leaders.get(host);
          if (shard == null) {
            if (host.endsWith("/")) shard = leaders.get(host.substring(0, host.length() - 1));
            if (shard == null) {
              shard = host;
            }
          }
          results.put(shard, rf);
        }
      }
    }
    return results;
  }

  private static boolean hasInfoToFindLeaders(UpdateRequest updateRequest, String idField) {
    final Map<SolrInputDocument, Map<String, Object>> documents = updateRequest.getDocumentsMap();
    final Map<String, Map<String, Object>> deleteById = updateRequest.getDeleteByIdMap();

    final boolean hasNoDocuments = (documents == null || documents.isEmpty());
    final boolean hasNoDeleteById = (deleteById == null || deleteById.isEmpty());
    if (hasNoDocuments && hasNoDeleteById) {
      // no documents and no delete-by-id, so no info to find leader(s)
      return false;
    }

    if (documents != null) {
      for (final Map.Entry<SolrInputDocument, Map<String, Object>> entry : documents.entrySet()) {
        final SolrInputDocument doc = entry.getKey();
        final Object fieldValue = doc.getFieldValue(idField);
        if (fieldValue == null) {
          // a document with no id field value, so can't find leader for it
          return false;
        }
      }
    }

    return true;
  }
}
