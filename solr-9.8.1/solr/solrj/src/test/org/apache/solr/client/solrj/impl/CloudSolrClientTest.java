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

import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.RequestStatusState;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.cloud.AbstractDistribZkTestBase;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.PerReplicaStates;
import org.apache.solr.common.cloud.PerReplicaStatesOps;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.URLUtil;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.handler.admin.CollectionsHandler;
import org.apache.solr.handler.admin.ConfigSetsHandler;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.util.LogLevel;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This test would be faster if we simulated the zk state instead. */
@LogLevel(
    "org.apache.solr.cloud.Overseer=INFO;org.apache.solr.common.cloud=INFO;org.apache.solr.cloud.api.collections=INFO;org.apache.solr.cloud.overseer=INFO")
public class CloudSolrClientTest extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String id = "id";

  private static final int TIMEOUT = 30;
  private static final int NODE_COUNT = 3;

  private static CloudSolrClient httpBasedCloudSolrClient = null;

  @BeforeClass
  public static void setupCluster() throws Exception {
    System.setProperty("metricsEnabled", "true");
    configureCluster(NODE_COUNT)
        .addConfig(
            "conf",
            getFile("solrj")
                .toPath()
                .resolve("solr")
                .resolve("configsets")
                .resolve("streaming")
                .resolve("conf"))
        .configure();

    final List<String> solrUrls = new ArrayList<>();
    solrUrls.add(cluster.getJettySolrRunner(0).getBaseUrl().toString());
    httpBasedCloudSolrClient = new CloudLegacySolrClient.Builder(solrUrls).build();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    if (httpBasedCloudSolrClient != null) {
      try {
        httpBasedCloudSolrClient.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    httpBasedCloudSolrClient = null;

    shutdownCluster();
  }

  /** Randomly return the cluster's ZK based CSC, or HttpClusterProvider based CSC. */
  private CloudSolrClient getRandomClient() {
    return random().nextBoolean() ? cluster.getSolrClient() : httpBasedCloudSolrClient;
  }

  @Test
  public void testParallelUpdateQTime() throws Exception {
    String COLLECTION = getSaferTestName();

    CollectionAdminRequest.createCollection(COLLECTION, "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION, 2, 2);
    UpdateRequest req = new UpdateRequest();
    for (int i = 0; i < 10; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", String.valueOf(TestUtil.nextInt(random(), 1000, 1100)));
      req.add(doc);
    }
    UpdateResponse response = req.process(getRandomClient(), COLLECTION);
    // See SOLR-6547, we just need to ensure that no exception is thrown here
    assertTrue(response.getQTime() >= 0);
  }

  @Test
  public void testOverwriteOption() throws Exception {

    CollectionAdminRequest.createCollection("overwrite", "conf", 1, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .processAndWait(cluster.getSolrClient(), TIMEOUT);
    cluster.waitForActiveCollection("overwrite", 1, 1);

    new UpdateRequest()
        .add("id", "0", "a_t", "hello1")
        .add("id", "0", "a_t", "hello2")
        .commit(cluster.getSolrClient(), "overwrite");

    QueryResponse resp = cluster.getSolrClient().query("overwrite", new SolrQuery("*:*"));
    assertEquals(
        "There should be one document because overwrite=true", 1, resp.getResults().getNumFound());

    new UpdateRequest()
        .add(new SolrInputDocument(id, "1", "a_t", "hello1"), /* overwrite= */ false)
        .add(new SolrInputDocument(id, "1", "a_t", "hello2"), false)
        .commit(cluster.getSolrClient(), "overwrite");

    resp = getRandomClient().query("overwrite", new SolrQuery("*:*"));
    assertEquals(
        "There should be 3 documents because there should be two id=1 docs due to overwrite=false",
        3,
        resp.getResults().getNumFound());
  }

  @Test
  public void testAliasHandling() throws Exception {
    String COLLECTION = getSaferTestName();
    String COLLECTION2 = "2nd_collection";

    CollectionAdminRequest.createCollection(COLLECTION, "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION, 2, 2);

    CollectionAdminRequest.createCollection(COLLECTION2, "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION2, 2, 2);

    CloudSolrClient client = getRandomClient();
    SolrInputDocument doc = new SolrInputDocument("id", "1", "title_s", "my doc");
    client.add(COLLECTION, doc);
    client.commit(COLLECTION);
    CollectionAdminRequest.createAlias("testalias", COLLECTION).process(cluster.getSolrClient());

    SolrInputDocument doc2 = new SolrInputDocument("id", "2", "title_s", "my doc too");
    client.add(COLLECTION2, doc2);
    client.commit(COLLECTION2);
    CollectionAdminRequest.createAlias("testalias2", COLLECTION2).process(cluster.getSolrClient());

    CollectionAdminRequest.createAlias("testaliascombined", COLLECTION + "," + COLLECTION2)
        .process(cluster.getSolrClient());

    // ensure that the aliases have been registered
    Map<String, String> aliases =
        new CollectionAdminRequest.ListAliases().process(cluster.getSolrClient()).getAliases();
    assertEquals(COLLECTION, aliases.get("testalias"));
    assertEquals(COLLECTION2, aliases.get("testalias2"));
    assertEquals(COLLECTION + "," + COLLECTION2, aliases.get("testaliascombined"));

    assertEquals(1, client.query(COLLECTION, params("q", "*:*")).getResults().getNumFound());
    assertEquals(1, client.query("testalias", params("q", "*:*")).getResults().getNumFound());

    assertEquals(1, client.query(COLLECTION2, params("q", "*:*")).getResults().getNumFound());
    assertEquals(1, client.query("testalias2", params("q", "*:*")).getResults().getNumFound());

    assertEquals(
        2, client.query("testaliascombined", params("q", "*:*")).getResults().getNumFound());

    ModifiableSolrParams paramsWithBothCollections =
        params("q", "*:*", "collection", COLLECTION + "," + COLLECTION2);
    assertEquals(2, client.query(null, paramsWithBothCollections).getResults().getNumFound());

    ModifiableSolrParams paramsWithBothAliases =
        params("q", "*:*", "collection", "testalias,testalias2");
    assertEquals(2, client.query(null, paramsWithBothAliases).getResults().getNumFound());

    ModifiableSolrParams paramsWithCombinedAlias =
        params("q", "*:*", "collection", "testaliascombined");
    assertEquals(2, client.query(null, paramsWithCombinedAlias).getResults().getNumFound());

    ModifiableSolrParams paramsWithMixedCollectionAndAlias =
        params("q", "*:*", "collection", "testalias," + COLLECTION2);
    assertEquals(
        2, client.query(null, paramsWithMixedCollectionAndAlias).getResults().getNumFound());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testRouting() throws Exception {
    CollectionAdminRequest.createCollection("routing_collection", "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection("routing_collection", 2, 2);

    AbstractUpdateRequest request =
        new UpdateRequest()
            .add(id, "0", "a_t", "hello1")
            .add(id, "2", "a_t", "hello2")
            .setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);

    // Test single threaded routed updates for UpdateRequest
    NamedList<Object> response = getRandomClient().request(request, "routing_collection");
    if (getRandomClient().isDirectUpdatesToLeadersOnly()) {
      checkSingleServer(response);
    }
    CloudSolrClient.RouteResponse rr = (CloudSolrClient.RouteResponse) response;
    Map<String, LBSolrClient.Req> routes = rr.getRoutes();
    Iterator<Map.Entry<String, LBSolrClient.Req>> it = routes.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, LBSolrClient.Req> entry = it.next();
      String coreUrl = entry.getKey();
      final String baseUrl = URLUtil.extractBaseUrl(coreUrl);
      final String coreName = URLUtil.extractCoreFromCoreUrl(coreUrl);
      UpdateRequest updateRequest = (UpdateRequest) entry.getValue().getRequest();
      SolrInputDocument doc = updateRequest.getDocuments().get(0);
      String id = doc.getField("id").getValue().toString();
      ModifiableSolrParams params = new ModifiableSolrParams();
      params.add("q", "id:" + id);
      params.add("distrib", "false");
      QueryRequest queryRequest = new QueryRequest(params);
      try (SolrClient solrClient = getHttpSolrClient(baseUrl, coreName)) {
        QueryResponse queryResponse = queryRequest.process(solrClient);
        SolrDocumentList docList = queryResponse.getResults();
        assertEquals(1, docList.getNumFound());
      }
    }

    // Test the deleteById routing for UpdateRequest

    final UpdateResponse uResponse =
        new UpdateRequest()
            .deleteById("0")
            .deleteById("2")
            .commit(cluster.getSolrClient(), "routing_collection");
    if (getRandomClient().isDirectUpdatesToLeadersOnly()) {
      checkSingleServer(uResponse.getResponse());
    }

    QueryResponse qResponse = getRandomClient().query("routing_collection", new SolrQuery("*:*"));
    SolrDocumentList docs = qResponse.getResults();
    assertEquals(0, docs.getNumFound());

    // Test Multi-Threaded routed updates for UpdateRequest
    try (CloudSolrClient threadedClient =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(cluster.getZkServer().getZkAddress()), Optional.empty())
            .sendUpdatesOnlyToShardLeaders()
            .withParallelUpdates(true)
            .withDefaultCollection("routing_collection")
            .build()) {
      response = threadedClient.request(request);
      if (threadedClient.isDirectUpdatesToLeadersOnly()) {
        checkSingleServer(response);
      }
      rr = (CloudSolrClient.RouteResponse) response;
      routes = rr.getRoutes();
      it = routes.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, LBSolrClient.Req> entry = it.next();
        String coreUrl = entry.getKey();
        final String baseUrl = URLUtil.extractBaseUrl(coreUrl);
        final String coreName = URLUtil.extractCoreFromCoreUrl(coreUrl);
        UpdateRequest updateRequest = (UpdateRequest) entry.getValue().getRequest();
        SolrInputDocument doc = updateRequest.getDocuments().get(0);
        String id = doc.getField("id").getValue().toString();
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.add("q", "id:" + id);
        params.add("distrib", "false");
        QueryRequest queryRequest = new QueryRequest(params);
        try (SolrClient solrClient = getHttpSolrClient(baseUrl, coreName)) {
          QueryResponse queryResponse = queryRequest.process(solrClient);
          SolrDocumentList docList = queryResponse.getResults();
          assertEquals(1, docList.getNumFound());
        }
      }
    }

    // Test that queries with _route_ params are routed by the client

    // Track request counts on each node before query calls
    ClusterState clusterState = cluster.getSolrClient().getClusterState();
    DocCollection col = clusterState.getCollection("routing_collection");
    Map<String, Long> requestCountsMap = new HashMap<>();
    for (Slice slice : col.getSlices()) {
      for (Replica replica : slice.getReplicas()) {
        String baseURL = replica.getBaseUrl();
        requestCountsMap.put(baseURL, getNumRequests(baseURL, "routing_collection"));
      }
    }

    // Collect the base URLs of the replicas of shard that's expected to be hit
    DocRouter router = col.getRouter();
    Collection<Slice> expectedSlices = router.getSearchSlicesSingle("0", null, col);
    Set<String> expectedBaseURLs = new HashSet<>();
    for (Slice expectedSlice : expectedSlices) {
      for (Replica replica : expectedSlice.getReplicas()) {
        expectedBaseURLs.add(replica.getBaseUrl());
      }
    }

    assertTrue(
        "expected urls is not fewer than all urls! expected="
            + expectedBaseURLs
            + "; all="
            + requestCountsMap.keySet(),
        expectedBaseURLs.size() < requestCountsMap.size());

    // Calculate a number of shard keys that route to the same shard.
    int n;
    if (TEST_NIGHTLY) {
      n = random().nextInt(999) + 2;
    } else {
      n = random().nextInt(9) + 2;
    }

    List<Slice> expectedSlicesList = List.copyOf(expectedSlices);
    List<String> sameShardRoutes = new ArrayList<>();
    sameShardRoutes.add("0");
    for (int i = 1; i < n; i++) {
      String shardKey = Integer.toString(i);
      List<Slice> slices = List.copyOf(router.getSearchSlicesSingle(shardKey, null, col));
      log.info("Expected Slices {}", slices);
      if (expectedSlicesList.equals(slices)) {
        sameShardRoutes.add(shardKey);
      }
    }

    assertTrue(sameShardRoutes.size() > 1);

    // Do N queries with _route_ parameter to the same shard
    for (int i = 0; i < n; i++) {
      ModifiableSolrParams solrParams = new ModifiableSolrParams();
      solrParams.set(CommonParams.Q, "*:*");
      solrParams.set(
          ShardParams._ROUTE_, sameShardRoutes.get(random().nextInt(sameShardRoutes.size())));
      if (log.isInfoEnabled()) {
        log.info("output: {}", getRandomClient().query("routing_collection", solrParams));
      }
    }

    // Request counts increase from expected nodes should aggregate to 1000, while there should be
    // no increase in unexpected nodes.
    long increaseFromExpectedUrls = 0;
    long increaseFromUnexpectedUrls = 0;
    Map<String, Long> numRequestsToUnexpectedUrls = new HashMap<>();
    for (Slice slice : col.getSlices()) {
      for (Replica replica : slice.getReplicas()) {
        String baseURL = replica.getBaseUrl();

        Long prevNumRequests = requestCountsMap.get(baseURL);
        Long curNumRequests = getNumRequests(baseURL, "routing_collection");

        long delta = curNumRequests - prevNumRequests;
        if (expectedBaseURLs.contains(baseURL)) {
          increaseFromExpectedUrls += delta;
        } else {
          increaseFromUnexpectedUrls += delta;
          numRequestsToUnexpectedUrls.put(baseURL, delta);
        }
      }
    }

    assertEquals("Unexpected number of requests to expected URLs", n, increaseFromExpectedUrls);
    assertEquals(
        "Unexpected number of requests to unexpected URLs: " + numRequestsToUnexpectedUrls,
        0,
        increaseFromUnexpectedUrls);
  }

  /**
   * Tests if the specification of 'shards.preference=replica.location:local' in the query-params
   * limits the distributed query to locally hosted shards only
   */
  @Test
  public void queryWithLocalShardsPreferenceRulesTest() throws Exception {

    String collectionName = "localShardsTestColl";

    int liveNodes = cluster.getJettySolrRunners().size();

    // For this case every shard should have all its cores on the same node.
    // Hence, the below configuration for our collection
    CollectionAdminRequest.createCollection(collectionName, "conf", liveNodes, liveNodes)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .processAndWait(cluster.getSolrClient(), TIMEOUT);
    cluster.waitForActiveCollection(collectionName, liveNodes, liveNodes * liveNodes);
    // Add some new documents
    new UpdateRequest()
        .add(id, "0", "a_t", "hello1")
        .add(id, "2", "a_t", "hello2")
        .add(id, "3", "a_t", "hello2")
        .commit(getRandomClient(), collectionName);

    queryWithShardsPreferenceRules(getRandomClient(), collectionName);
  }

  @SuppressWarnings("deprecation")
  private void queryWithShardsPreferenceRules(CloudSolrClient cloudClient, String collectionName)
      throws Exception {
    SolrQuery qRequest = new SolrQuery("*:*");

    ModifiableSolrParams qParams = new ModifiableSolrParams();
    qParams.add(
        ShardParams.SHARDS_PREFERENCE,
        ShardParams.SHARDS_PREFERENCE_REPLICA_LOCATION + ":" + ShardParams.REPLICA_LOCAL);
    qParams.add(ShardParams.SHARDS_INFO, "true");
    qRequest.add(qParams);

    // CloudSolrClient sends the request to some node.
    // And since all the nodes are hosting cores from all shards, the
    // distributed query formed by this node will select cores from the
    // local shards only
    QueryResponse qResponse = cloudClient.query(collectionName, qRequest);

    Object shardsInfo = qResponse.getResponse().get(ShardParams.SHARDS_INFO);
    assertNotNull("Unable to obtain " + ShardParams.SHARDS_INFO, shardsInfo);

    // Iterate over shards-info and check what cores responded
    SimpleOrderedMap<?> shardsInfoMap = (SimpleOrderedMap<?>) shardsInfo;
    @SuppressWarnings({"unchecked"})
    Iterator<Map.Entry<String, ?>> itr = shardsInfoMap.asMap(100).entrySet().iterator();
    List<String> shardAddresses = new ArrayList<String>();
    while (itr.hasNext()) {
      Map.Entry<String, ?> e = itr.next();
      assertTrue(
          "Did not find map-type value in " + ShardParams.SHARDS_INFO, e.getValue() instanceof Map);
      String shardAddress = (String) ((Map) e.getValue()).get("shardAddress");
      assertNotNull(
          ShardParams.SHARDS_INFO + " did not return 'shardAddress' parameter", shardAddress);
      shardAddresses.add(shardAddress);
    }
    if (log.isInfoEnabled()) {
      log.info("Shards giving the response: {}", Arrays.toString(shardAddresses.toArray()));
    }

    // Make sure the distributed queries were directed to a single node only
    Set<Integer> ports = new HashSet<Integer>();
    for (String shardAddr : shardAddresses) {
      URI uri = URI.create(shardAddr);
      ports.add(uri.getPort());
    }

    // This assertion would hold true as long as every shard has a core on each node
    assertTrue(
        "Response was not received from shards on a single node",
        shardAddresses.size() > 1 && ports.size() == 1);
  }

  /** Tests if the 'shards.preference' parameter works with single-sharded collections. */
  @Test
  public void singleShardedPreferenceRules() throws Exception {
    String collectionName = "singleShardPreferenceTestColl";

    int liveNodes = cluster.getJettySolrRunners().size();

    // For testing replica.type, we want to have all replica types available for the collection
    CollectionAdminRequest.createCollection(
            collectionName, "conf", 1, liveNodes / 3, liveNodes / 3, liveNodes / 3)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .processAndWait(cluster.getSolrClient(), TIMEOUT);
    cluster.waitForActiveCollection(collectionName, 1, liveNodes);

    // Add some new documents
    new UpdateRequest()
        .add(id, "0", "a_t", "hello1")
        .add(id, "2", "a_t", "hello2")
        .add(id, "3", "a_t", "hello2")
        .commit(getRandomClient(), collectionName);

    // Run the actual test for 'queryReplicaType'
    queryReplicaType(getRandomClient(), Replica.Type.PULL, collectionName);
    queryReplicaType(getRandomClient(), Replica.Type.TLOG, collectionName);
    queryReplicaType(getRandomClient(), Replica.Type.NRT, collectionName);
  }

  private void queryReplicaType(
      CloudSolrClient cloudClient, Replica.Type typeToQuery, String collectionName)
      throws Exception {
    SolrQuery qRequest = new SolrQuery("*:*");

    ModifiableSolrParams qParams = new ModifiableSolrParams();
    qParams.add(
        ShardParams.SHARDS_PREFERENCE,
        ShardParams.SHARDS_PREFERENCE_REPLICA_TYPE + ":" + typeToQuery.toString());
    qParams.add(ShardParams.SHARDS_INFO, "true");
    qRequest.add(qParams);

    Map<String, String> replicaTypeToReplicas =
        mapReplicasToReplicaType(getCollectionState(collectionName));

    QueryResponse qResponse = cloudClient.query(collectionName, qRequest);

    Object shardsInfo = qResponse.getResponse().get(ShardParams.SHARDS_INFO);
    assertNotNull("Unable to obtain " + ShardParams.SHARDS_INFO, shardsInfo);

    // Iterate over shards-info and check what cores responded
    SimpleOrderedMap<?> shardsInfoMap = (SimpleOrderedMap<?>) shardsInfo;
    @SuppressWarnings({"unchecked"})
    Iterator<Map.Entry<String, ?>> itr = shardsInfoMap.asMap(100).entrySet().iterator();
    List<String> shardAddresses = new ArrayList<String>();
    while (itr.hasNext()) {
      Map.Entry<String, ?> e = itr.next();
      assertTrue(
          "Did not find map-type value in " + ShardParams.SHARDS_INFO, e.getValue() instanceof Map);
      String shardAddress = (String) ((Map) e.getValue()).get("shardAddress");
      if (shardAddress.endsWith("/")) {
        shardAddress = shardAddress.substring(0, shardAddress.length() - 1);
      }
      assertNotNull(
          ShardParams.SHARDS_INFO + " did not return 'shardAddress' parameter", shardAddress);
      shardAddresses.add(shardAddress);
    }
    assertEquals(
        "Shard addresses must be of size 1, since there is only 1 shard in the collection",
        1,
        shardAddresses.size());

    assertEquals(
        "Make sure that the replica queried was the replicaType desired",
        typeToQuery.toString().toUpperCase(Locale.ROOT),
        replicaTypeToReplicas.get(shardAddresses.get(0)).toUpperCase(Locale.ROOT));
  }

  private Long getNumRequests(String baseUrl, String collectionName)
      throws SolrServerException, IOException {
    return getNumRequests(baseUrl, collectionName, "QUERY", "/select", null, false);
  }

  private Long getNumRequests(
      String baseUrl,
      String collectionName,
      String category,
      String key,
      String scope,
      boolean returnNumErrors)
      throws SolrServerException, IOException {

    NamedList<Object> resp;
    try (SolrClient client =
        new HttpSolrClient.Builder(baseUrl)
            .withDefaultCollection(collectionName)
            .withConnectionTimeout(15000, TimeUnit.MILLISECONDS)
            .withSocketTimeout(60000, TimeUnit.MILLISECONDS)
            .build()) {
      ModifiableSolrParams params = new ModifiableSolrParams();
      params.set("qt", "/admin/mbeans");
      params.set("stats", "true");
      params.set("key", key);
      params.set("cat", category);
      // use generic request to avoid extra processing of queries
      QueryRequest req = new QueryRequest(params);
      resp = client.request(req);
    }
    String name;
    if (returnNumErrors) {
      name = category + "." + (scope != null ? scope : key) + ".errors";
    } else {
      name = category + "." + (scope != null ? scope : key) + ".requests";
    }
    @SuppressWarnings({"unchecked"})
    Map<String, Object> map =
        (Map<String, Object>) resp.findRecursive("solr-mbeans", category, key, "stats");
    if (map == null) {
      return null;
    }
    if (scope != null) { // admin handler uses a meter instead of counter here
      return (Long) map.get(name + ".count");
    } else {
      return (Long) map.get(name);
    }
  }

  @Test
  public void testNonRetryableRequests() throws Exception {
    String collection = getSaferTestName();

    try (CloudSolrClient client =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(cluster.getZkServer().getZkAddress()), Optional.empty())
            .withDefaultCollection(collection)
            .build()) {
      // important to have one replica on each node
      RequestStatusState state =
          CollectionAdminRequest.createCollection(collection, "conf", 1, NODE_COUNT)
              .processAndWait(client, 60);
      if (state == RequestStatusState.COMPLETED) {
        cluster.waitForActiveCollection(collection, 1, NODE_COUNT);

        Map<String, String> adminPathToMbean = new HashMap<>(CommonParams.ADMIN_PATHS.size());
        adminPathToMbean.put(
            CommonParams.COLLECTIONS_HANDLER_PATH, CollectionsHandler.class.getName());
        adminPathToMbean.put(CommonParams.CORES_HANDLER_PATH, CoreAdminHandler.class.getName());
        adminPathToMbean.put(
            CommonParams.CONFIGSETS_HANDLER_PATH, ConfigSetsHandler.class.getName());
        // we do not add the authc/authz handlers because they do not currently expose any mbeans

        for (String adminPath : adminPathToMbean.keySet()) {
          long errorsBefore = 0;
          for (JettySolrRunner runner : cluster.getJettySolrRunners()) {
            Long numRequests =
                getNumRequests(
                    runner.getBaseUrl().toString(),
                    collection,
                    "ADMIN",
                    adminPathToMbean.get(adminPath),
                    adminPath,
                    true);
            errorsBefore += numRequests;
            if (log.isInfoEnabled()) {
              log.info(
                  "Found {} requests to {} on {}", numRequests, adminPath, runner.getBaseUrl());
            }
          }

          ModifiableSolrParams params = new ModifiableSolrParams();
          params.set("qt", adminPath);
          params.set("action", "foobar"); // this should cause an error
          QueryRequest req = new QueryRequest(params);
          try {
            NamedList<Object> resp = client.request(req);
            fail("call to foo for admin path " + adminPath + " should have failed");
          } catch (Exception e) {
            // expected
          }
          long errorsAfter = 0;
          for (JettySolrRunner runner : cluster.getJettySolrRunners()) {
            Long numRequests =
                getNumRequests(
                    runner.getBaseUrl().toString(),
                    collection,
                    "ADMIN",
                    adminPathToMbean.get(adminPath),
                    adminPath,
                    true);
            errorsAfter += numRequests;
            if (log.isInfoEnabled()) {
              log.info(
                  "Found {} requests to {} on {}", numRequests, adminPath, runner.getBaseUrl());
            }
          }
          assertEquals(errorsBefore + 1, errorsAfter);
        }
      } else {
        fail("Collection could not be created within 60 seconds");
      }
    }
  }

  @Test
  public void checkCollectionParameters() throws Exception {

    try (CloudSolrClient client =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(cluster.getZkServer().getZkAddress()), Optional.empty())
            .withDefaultCollection("multicollection1")
            .build()) {

      String async1 =
          CollectionAdminRequest.createCollection("multicollection1", "conf", 2, 1)
              .setPerReplicaState(USE_PER_REPLICA_STATE)
              .processAsync(client);
      String async2 =
          CollectionAdminRequest.createCollection("multicollection2", "conf", 2, 1)
              .setPerReplicaState(USE_PER_REPLICA_STATE)
              .processAsync(client);

      CollectionAdminRequest.waitForAsyncRequest(async1, client, TIMEOUT);
      CollectionAdminRequest.waitForAsyncRequest(async2, client, TIMEOUT);
      cluster.waitForActiveCollection("multicollection1", 2, 2);
      cluster.waitForActiveCollection("multicollection2", 2, 2);

      List<SolrInputDocument> docs = new ArrayList<>(3);
      for (int i = 0; i < 3; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(id, Integer.toString(i));
        doc.addField("a_t", "hello");
        docs.add(doc);
      }

      client.add(docs); // default - will add them to multicollection1
      client.commit();

      ModifiableSolrParams queryParams = new ModifiableSolrParams();
      queryParams.add("q", "*:*");
      assertEquals(3, client.query(queryParams).getResults().size());
      assertEquals(0, client.query("multicollection2", queryParams).getResults().size());

      SolrQuery query = new SolrQuery("*:*");
      query.set("collection", "multicollection2");
      assertEquals(0, client.query(query).getResults().size());

      client.add("multicollection2", docs);
      client.commit("multicollection2");

      assertEquals(3, client.query("multicollection2", queryParams).getResults().size());
    }
  }

  @Test
  public void stateVersionParamTest() throws Exception {
    String COLLECTION = getSaferTestName();

    CollectionAdminRequest.createCollection(COLLECTION, "conf", 2, 1)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION, 2, 2);

    DocCollection coll = cluster.getSolrClient().getClusterState().getCollection(COLLECTION);
    Replica r = coll.getSlices().iterator().next().getReplicas().iterator().next();

    SolrQuery q = new SolrQuery().setQuery("*:*");
    BaseHttpSolrClient.RemoteSolrException sse = null;

    try (SolrClient solrClient = getHttpSolrClient(r.getBaseUrl(), COLLECTION)) {

      if (log.isInfoEnabled()) {
        log.info("should work query, result {}", solrClient.query(q));
      }
      // no problem
      q.setParam(CloudSolrClient.STATE_VERSION, COLLECTION + ":" + coll.getZNodeVersion());
      if (log.isInfoEnabled()) {
        log.info("2nd query , result {}", solrClient.query(q));
      }
      // no error yet good

      q.setParam(
          CloudSolrClient.STATE_VERSION,
          COLLECTION + ":" + (coll.getZNodeVersion() - 1)); // an older version expect error

      QueryResponse rsp = solrClient.query(q);
      @SuppressWarnings({"rawtypes"})
      Map m =
          (Map) rsp.getResponse().get(CloudSolrClient.STATE_VERSION, rsp.getResponse().size() - 1);
      assertNotNull(
          "Expected an extra information from server with the list of invalid collection states",
          m);
      assertNotNull(m.get(COLLECTION));
    }

    // now send the request to another node that does not serve the collection

    Set<String> allNodesOfColl = new HashSet<>();
    for (Slice slice : coll.getSlices()) {
      for (Replica replica : slice.getReplicas()) {
        allNodesOfColl.add(replica.getBaseUrl());
      }
    }
    String theNode = null;
    Set<String> liveNodes = cluster.getSolrClient().getClusterState().getLiveNodes();
    for (String s : liveNodes) {
      String n = cluster.getZkStateReader().getBaseUrlForNodeName(s);
      if (!allNodesOfColl.contains(n)) {
        theNode = n;
        break;
      }
    }
    log.info("the node which does not serve this collection{} ", theNode);
    assertNotNull(theNode);

    try (SolrClient solrClient = getHttpSolrClient(theNode, COLLECTION)) {

      q.setParam(CloudSolrClient.STATE_VERSION, COLLECTION + ":" + (coll.getZNodeVersion() - 1));
      try {
        QueryResponse rsp = solrClient.query(q);
        log.info("error was expected");
      } catch (BaseHttpSolrClient.RemoteSolrException e) {
        sse = e;
      }
      assertNotNull(sse);
      assertEquals(
          " Error code should be 510", SolrException.ErrorCode.INVALID_STATE.code, sse.code());
    }
  }

  @Test
  public void testShutdown() throws IOException {
    try (CloudSolrClient client =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(DEAD_HOST_1), Optional.empty())
            .build()) {
      try (ZkClientClusterStateProvider zkClientClusterStateProvider =
          ZkClientClusterStateProvider.from(client)) {
        zkClientClusterStateProvider.setZkConnectTimeout(100);
        SolrException ex = expectThrows(SolrException.class, client::connect);
        assertTrue(ex.getCause() instanceof TimeoutException);
      }
    }
  }

  @Test
  public void testWrongZkChrootTest() throws IOException {
    try (CloudSolrClient client =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(cluster.getZkServer().getZkAddress() + "/xyz/foo"),
                Optional.empty())
            .build()) {
      try (ZkClientClusterStateProvider zkClientClusterStateProvider =
          ZkClientClusterStateProvider.from(client)) {
        zkClientClusterStateProvider.setZkConnectTimeout(1000 * 60);
        SolrException ex = expectThrows(SolrException.class, client::connect);
        assertThat(
            "Wrong error message for empty chRoot",
            ex.getMessage(),
            Matchers.containsString("cluster not found/not ready"));
        assertThat(
            "Wrong node missing message for empty chRoot",
            ex.getMessage(),
            Matchers.containsString(
                "Expected node '" + ZkStateReader.ALIASES + "' does not exist"));
      }
    }
  }

  @Test
  public void customHttpClientTest() throws IOException {
    CloseableHttpClient client = HttpClientUtil.createClient(null);
    try (CloudSolrClient solrClient =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(cluster.getZkServer().getZkAddress()), Optional.empty())
            .withHttpClient(client)
            .build()) {

      assertSame(((CloudLegacySolrClient) solrClient).getLbClient().getHttpClient(), client);

    } finally {
      HttpClientUtil.close(client);
    }
  }

  @Test
  public void testVersionsAreReturned() throws Exception {
    String collection = getSaferTestName();

    CollectionAdminRequest.createCollection(collection, "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(collection, 2, 2);

    // assert that "adds" are returned
    UpdateRequest updateRequest =
        new UpdateRequest().add("id", "1", "a_t", "hello1").add("id", "2", "a_t", "hello2");
    updateRequest.setParam(UpdateParams.VERSIONS, Boolean.TRUE.toString());

    NamedList<Object> response = updateRequest.commit(getRandomClient(), collection).getResponse();
    Object addsObject = response.get("adds");

    assertNotNull("There must be a adds parameter", addsObject);
    assertTrue(addsObject instanceof NamedList<?>);
    NamedList<?> adds = (NamedList<?>) addsObject;
    assertEquals("There must be 2 versions (one per doc)", 2, adds.size());

    Map<String, Long> versions = new HashMap<>();
    Object object = adds.get("1");
    assertNotNull("There must be a version for id 1", object);
    assertTrue("Version for id 1 must be a long", object instanceof Long);
    versions.put("1", (Long) object);

    object = adds.get("2");
    assertNotNull("There must be a version for id 2", object);
    assertTrue("Version for id 2 must be a long", object instanceof Long);
    versions.put("2", (Long) object);

    QueryResponse resp = getRandomClient().query(collection, new SolrQuery("*:*"));
    assertEquals(
        "There should be one document because overwrite=true", 2, resp.getResults().getNumFound());

    for (SolrDocument doc : resp.getResults()) {
      Long version = versions.get(doc.getFieldValue("id"));
      assertEquals(
          "Version on add must match _version_ field", version, doc.getFieldValue("_version_"));
    }

    // assert that "deletes" are returned
    UpdateRequest deleteRequest = new UpdateRequest().deleteById("1");
    deleteRequest.setParam(UpdateParams.VERSIONS, Boolean.TRUE.toString());
    response = deleteRequest.commit(getRandomClient(), collection).getResponse();
    Object deletesObject = response.get("deletes");
    assertNotNull("There must be a deletes parameter", deletesObject);
    NamedList<?> deletes = (NamedList<?>) deletesObject;
    assertEquals("There must be 1 version", 1, deletes.size());
  }

  @Test
  public void testInitializationWithSolrUrls() throws Exception {
    String COLLECTION = getSaferTestName();

    CollectionAdminRequest.createCollection(COLLECTION, "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION, 2, 2);
    CloudSolrClient client = httpBasedCloudSolrClient;
    SolrInputDocument doc = new SolrInputDocument("id", "1", "title_s", "my doc");
    client.add(COLLECTION, doc);
    client.commit(COLLECTION);
    assertEquals(1, client.query(COLLECTION, params("q", "*:*")).getResults().getNumFound());
  }

  @Test
  public void testCollectionDoesntExist() throws Exception {
    CloudSolrClient client = getRandomClient();
    SolrInputDocument doc = new SolrInputDocument("id", "1", "title_s", "my doc");
    SolrException ex =
        expectThrows(SolrException.class, () -> client.add("boguscollectionname", doc));
    assertEquals("Collection not found: boguscollectionname", ex.getMessage());
  }

  public void testRetryUpdatesWhenClusterStateIsStale() throws Exception {
    final String COL = "stale_state_test_col";
    assertTrue(cluster.getJettySolrRunners().size() >= 2);

    final JettySolrRunner old_leader_node = cluster.getJettySolrRunners().get(0);
    final JettySolrRunner new_leader_node = cluster.getJettySolrRunners().get(1);

    // start with exactly 1 shard/replica...
    assertEquals(
        "Couldn't create collection",
        0,
        CollectionAdminRequest.createCollection(COL, "conf", 1, 1)
            .setCreateNodeSet(old_leader_node.getNodeName())
            .process(cluster.getSolrClient())
            .getStatus());
    cluster.waitForActiveCollection(COL, 1, 1);

    // determine the coreNodeName of only current replica
    Collection<Slice> slices =
        cluster.getSolrClient().getClusterState().getCollection(COL).getSlices();
    assertEquals(1, slices.size()); // sanity check
    Slice slice = slices.iterator().next();
    assertEquals(1, slice.getReplicas().size()); // sanity check
    final String old_leader_core_node_name = slice.getLeader().getName();

    // NOTE: creating our own CloudSolrClient with settings for this specific test...
    try (CloudSolrClient stale_client =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(cluster.getZkServer().getZkAddress()), Optional.empty())
            .sendDirectUpdatesToAnyShardReplica()
            .withParallelUpdates(true)
            // don't let collection cache entries get expired, even on a slow machine...
            .withCollectionCacheTtl(Integer.MAX_VALUE)
            .withDefaultCollection(COL)
            .build()) {

      // do a query to populate stale_client's cache...
      assertEquals(0, stale_client.query(new SolrQuery("*:*")).getResults().getNumFound());

      // add 1 replica on a diff node...
      assertEquals(
          "Couldn't create collection",
          0,
          CollectionAdminRequest.addReplicaToShard(COL, "shard1")
              .setNode(new_leader_node.getNodeName())
              // NOTE: don't use our stale_client for this -- don't tip it off of a collection
              // change
              .process(cluster.getSolrClient())
              .getStatus());
      AbstractDistribZkTestBase.waitForRecoveriesToFinish(
          COL, cluster.getZkStateReader(), true, true, 330);
      // ...and delete our original leader.
      assertEquals(
          "Couldn't create collection",
          0,
          CollectionAdminRequest.deleteReplica(COL, "shard1", old_leader_core_node_name)
              // NOTE: don't use our stale_client for this -- don't tip it off of a collection
              // change
              .process(cluster.getSolrClient())
              .getStatus());
      AbstractDistribZkTestBase.waitForRecoveriesToFinish(
          COL, cluster.getZkStateReader(), true, true, 330);

      // stale_client's collection state cache should now only point at a leader that no longer
      // exists.

      // attempt a (direct) update that should succeed in spite of cached cluster state
      // pointing solely to a node that's no longer part of our collection...
      assertEquals(0, (new UpdateRequest().add("id", "1").commit(stale_client, COL)).getStatus());
      assertEquals(1, stale_client.query(new SolrQuery("*:*")).getResults().getNumFound());
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void checkSingleServer(NamedList<Object> response) {
    final CloudSolrClient.RouteResponse rr = (CloudSolrClient.RouteResponse) response;
    final Map<String, LBSolrClient.Req> routes = rr.getRoutes();
    final Iterator<Map.Entry<String, LBSolrClient.Req>> it = routes.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, LBSolrClient.Req> entry = it.next();
      assertEquals(
          "wrong number of servers: " + entry.getValue().getServers(),
          1,
          entry.getValue().getServers().size());
    }
  }

  /**
   * Tests if the specification of 'preferReplicaTypes` in the query-params limits the distributed
   * query to locally hosted shards only
   */
  @Test
  public void preferReplicaTypesTest() throws Exception {

    String collectionName = "replicaTypesTestColl";

    int liveNodes = cluster.getJettySolrRunners().size();

    // For these tests we need to have multiple replica types.
    // Hence, the below configuration for our collection
    int pullReplicas = Math.max(1, liveNodes - 2);
    CollectionAdminRequest.createCollection(collectionName, "conf", liveNodes, 1, 1, pullReplicas)
        .processAndWait(cluster.getSolrClient(), TIMEOUT);
    cluster.waitForActiveCollection(collectionName, liveNodes, liveNodes * (2 + pullReplicas));

    // Add some new documents
    new UpdateRequest()
        .add(id, "0", "a_t", "hello1")
        .add(id, "2", "a_t", "hello2")
        .add(id, "3", "a_t", "hello2")
        .commit(getRandomClient(), collectionName);

    // Run the actual tests for 'shards.preference=replica.type:*'
    queryWithPreferReplicaTypes(getRandomClient(), "PULL", collectionName);
    queryWithPreferReplicaTypes(getRandomClient(), "PULL|TLOG", collectionName);
    queryWithPreferReplicaTypes(getRandomClient(), "TLOG", collectionName);
    queryWithPreferReplicaTypes(getRandomClient(), "TLOG|PULL", collectionName);
    queryWithPreferReplicaTypes(getRandomClient(), "NRT", collectionName);
    queryWithPreferReplicaTypes(getRandomClient(), "NRT|PULL", collectionName);
    CollectionAdminRequest.deleteCollection(collectionName)
        .processAndWait(cluster.getSolrClient(), TIMEOUT);
  }

  private void queryWithPreferReplicaTypes(
      CloudSolrClient cloudClient, String preferReplicaTypes, String collectionName)
      throws Exception {
    SolrQuery qRequest = new SolrQuery("*:*");
    ModifiableSolrParams qParams = new ModifiableSolrParams();

    final List<String> preferredTypes = Arrays.asList(preferReplicaTypes.split("\\|"));
    StringBuilder rule = new StringBuilder();
    preferredTypes.forEach(
        type -> {
          if (rule.length() != 0) {
            rule.append(',');
          }
          rule.append(ShardParams.SHARDS_PREFERENCE_REPLICA_TYPE);
          rule.append(':');
          rule.append(type);
        });
    qParams.add(ShardParams.SHARDS_PREFERENCE, rule.toString());
    qParams.add(ShardParams.SHARDS_INFO, "true");
    qRequest.add(qParams);

    // CloudSolrClient sends the request to some node.
    // And since all the nodes are hosting cores from all shards, the
    // distributed query formed by this node will select cores from the
    // local shards only
    QueryResponse qResponse = cloudClient.query(collectionName, qRequest);

    Object shardsInfo = qResponse.getResponse().get(ShardParams.SHARDS_INFO);
    assertNotNull("Unable to obtain " + ShardParams.SHARDS_INFO, shardsInfo);

    Map<String, String> replicaTypeMap = new HashMap<>();
    DocCollection collection = getCollectionState(collectionName);
    for (Slice slice : collection.getSlices()) {
      for (Replica replica : slice.getReplicas()) {
        String coreUrl = replica.getCoreUrl();
        // It seems replica reports its core URL with a trailing slash while shard
        // info returned from the query doesn't.
        if (coreUrl.endsWith("/")) {
          coreUrl = coreUrl.substring(0, coreUrl.length() - 1);
        }
        replicaTypeMap.put(coreUrl, replica.getType().toString());
      }
    }

    // Iterate over shards-info and check that replicas of correct type responded
    SimpleOrderedMap<?> shardsInfoMap = (SimpleOrderedMap<?>) shardsInfo;
    @SuppressWarnings({"unchecked"})
    Iterator<Map.Entry<String, ?>> itr = shardsInfoMap.asMap(100).entrySet().iterator();
    List<String> shardAddresses = new ArrayList<>();
    while (itr.hasNext()) {
      Map.Entry<String, ?> e = itr.next();
      assertTrue(
          "Did not find map-type value in " + ShardParams.SHARDS_INFO, e.getValue() instanceof Map);
      String shardAddress = (String) ((Map) e.getValue()).get("shardAddress");
      assertNotNull(
          ShardParams.SHARDS_INFO + " did not return 'shardAddress' parameter", shardAddress);
      assertTrue(replicaTypeMap.containsKey(shardAddress));
      assertEquals(0, preferredTypes.indexOf(replicaTypeMap.get(shardAddress)));
      shardAddresses.add(shardAddress);
    }
    assertTrue("No responses", shardAddresses.size() > 0);
    if (log.isInfoEnabled()) {
      log.info("Shards giving the response: {}", Arrays.toString(shardAddresses.toArray()));
    }
  }

  @Test
  public void testPing() throws Exception {
    final String testCollection = "ping_test";
    CollectionAdminRequest.createCollection(testCollection, "conf", 2, 1)
        .setPerReplicaState(USE_PER_REPLICA_STATE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(testCollection, 2, 2);
    final SolrClient clientUnderTest = getRandomClient();

    final SolrPingResponse response = clientUnderTest.ping(testCollection);

    assertEquals("This should be OK", 0, response.getStatus());
  }

  public void testPerReplicaStateCollection() throws Exception {
    String collection = getSaferTestName();

    CollectionAdminRequest.createCollection(collection, "conf", 2, 1)
        .process(cluster.getSolrClient());

    String testCollection = "perReplicaState_test";
    String collectionPath = DocCollection.getCollectionPath(testCollection);

    int liveNodes = cluster.getJettySolrRunners().size();
    CollectionAdminRequest.createCollection(testCollection, "conf", 2, 2)
        .setPerReplicaState(Boolean.TRUE)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(testCollection, 2, 4);
    final SolrClient clientUnderTest = getRandomClient();
    final SolrPingResponse response = clientUnderTest.ping(testCollection);
    assertEquals("This should be OK", 0, response.getStatus());

    DocCollection c = cluster.getZkStateReader().getCollection(testCollection);
    c.forEachReplica((s, replica) -> assertNotNull(replica.getReplicaState()));
    PerReplicaStates prs = PerReplicaStatesOps.fetch(collectionPath, cluster.getZkClient(), null);
    assertEquals(4, prs.states.size());

    JettySolrRunner jsr = null;
    try {
      jsr = cluster.startJettySolrRunner();

      // Now let's do an add replica
      CollectionAdminRequest.addReplicaToShard(testCollection, "shard1")
          .process(cluster.getSolrClient());
      prs = PerReplicaStatesOps.fetch(collectionPath, cluster.getZkClient(), null);
      assertEquals(5, prs.states.size());

      // create a collection with PRS and v2 API
      testCollection = "perReplicaState_testv2";
      collectionPath = DocCollection.getCollectionPath(testCollection);

      new V2Request.Builder("/collections")
          .withMethod(POST)
          .withPayload(
              "{\"name\": \"perReplicaState_testv2\", \"config\" : \"conf\", \"numShards\" : 2, \"nrtReplicas\" : 2, \"perReplicaState\" : true}")
          .build()
          .process(cluster.getSolrClient());
      cluster.waitForActiveCollection(testCollection, 2, 4);
      c = cluster.getZkStateReader().getCollection(testCollection);
      c.forEachReplica((s, replica) -> assertNotNull(replica.getReplicaState()));
      prs = PerReplicaStatesOps.fetch(collectionPath, cluster.getZkClient(), null);
      assertEquals(4, prs.states.size());
    } finally {
      if (jsr != null) {
        cluster.stopJettySolrRunner(jsr);
      }
    }
  }
}
