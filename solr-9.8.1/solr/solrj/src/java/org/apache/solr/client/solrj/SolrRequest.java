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
package org.apache.solr.client.solrj;

import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;

/**
 * @since solr 1.3
 */
public abstract class SolrRequest<T extends SolrResponse> implements Serializable {
  // This user principal is typically used by Auth plugins during distributed/sharded search
  private Principal userPrincipal;

  public void setUserPrincipal(Principal userPrincipal) {
    this.userPrincipal = userPrincipal;
  }

  public Principal getUserPrincipal() {
    return userPrincipal;
  }

  public enum METHOD {
    GET,
    POST,
    PUT,
    DELETE
  };

  public enum ApiVersion {
    V1("/solr"),
    V2("/api");

    private final String apiPrefix;

    ApiVersion(String apiPrefix) {
      this.apiPrefix = apiPrefix;
    }

    public String getApiPrefix() {
      return apiPrefix;
    }
  }

  public enum SolrRequestType {
    QUERY,
    UPDATE,
    SECURITY,
    ADMIN,
    STREAMING,
    UNSPECIFIED
  };

  public enum SolrClientContext {
    CLIENT,
    SERVER
  };

  public static final Set<String> SUPPORTED_METHODS =
      Set.of(
          METHOD.GET.toString(),
          METHOD.POST.toString(),
          METHOD.PUT.toString(),
          METHOD.DELETE.toString());

  private METHOD method = METHOD.GET;
  private String path = null;
  private Map<String, String> headers;
  private List<String> preferredNodes;

  private ResponseParser responseParser;
  private StreamingResponseCallback callback;
  private Set<String> queryParams;

  protected boolean usev2;
  protected boolean useBinaryV2;

  /**
   * If set to true, every request that implements {@link V2RequestSupport} will be converted to a
   * V2 API call
   *
   * @deprecated use v2-native {@link SolrRequest} implementations directly instead
   */
  @Deprecated
  public SolrRequest<T> setUseV2(boolean flag) {
    this.usev2 = flag;
    return this;
  }

  /**
   * If set to true use javabin instead of json (default)
   *
   * @deprecated use v2-native {@link SolrRequest} implementations directly instead
   */
  @Deprecated
  public SolrRequest<T> setUseBinaryV2(boolean flag) {
    this.useBinaryV2 = flag;
    return this;
  }

  public SolrRequest<T> setPreferredNodes(List<String> nodes) {
    this.preferredNodes = nodes;
    return this;
  }

  public List<String> getPreferredNodes() {
    return this.preferredNodes;
  }

  private String basicAuthUser, basicAuthPwd;

  private String basePath;

  public SolrRequest<T> setBasicAuthCredentials(String user, String password) {
    this.basicAuthUser = user;
    this.basicAuthPwd = password;
    return this;
  }

  public String getBasicAuthUser() {
    return basicAuthUser;
  }

  public String getBasicAuthPassword() {
    return basicAuthPwd;
  }

  // ---------------------------------------------------------
  // ---------------------------------------------------------

  public SolrRequest(METHOD m, String path) {
    this.method = m;
    this.path = path;
  }

  // ---------------------------------------------------------
  // ---------------------------------------------------------

  public METHOD getMethod() {
    return method;
  }

  public void setMethod(METHOD method) {
    this.method = method;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  /**
   * @return The {@link org.apache.solr.client.solrj.ResponseParser}
   */
  public ResponseParser getResponseParser() {
    return responseParser;
  }

  /**
   * Optionally specify how the Response should be parsed. Not all server implementations require a
   * ResponseParser to be specified.
   *
   * @param responseParser The {@link org.apache.solr.client.solrj.ResponseParser}
   */
  public void setResponseParser(ResponseParser responseParser) {
    this.responseParser = responseParser;
  }

  public StreamingResponseCallback getStreamingResponseCallback() {
    return callback;
  }

  public void setStreamingResponseCallback(StreamingResponseCallback callback) {
    this.callback = callback;
  }

  /** Parameter keys that are sent via the query string */
  public Set<String> getQueryParams() {
    return this.queryParams;
  }

  public void setQueryParams(Set<String> queryParams) {
    this.queryParams = queryParams;
  }

  /** This method defines the type of this Solr request. */
  public abstract String getRequestType();

  public abstract SolrParams getParams();

  /**
   * Determines whether this request should use or ignore any specified collections (esp. {@link
   * SolrClient#defaultCollection})
   *
   * <p>Many Solr requests target a particular core or collection. But not all of them - many Solr
   * APIs (e.g. security or other admin APIs) are agnostic of collections entirely. This method
   * gives these requests a way to opt out of using {@link SolrClient#defaultCollection} or other
   * specified collections.
   */
  public boolean requiresCollection() {
    return false;
  }

  /**
   * Indicates which API version this request will make
   *
   * <p>Defaults implementation returns 'V1'.
   */
  public ApiVersion getApiVersion() {
    return ApiVersion.V1;
  }

  /**
   * @deprecated Please use {@link SolrRequest#getContentWriter(String)} instead.
   */
  @Deprecated
  public Collection<ContentStream> getContentStreams() throws IOException {
    return null;
  }

  /**
   * If a request object wants to do a push write, implement this method.
   *
   * @param expectedType This is the type that the RequestWriter would like to get. But, it is OK to
   *     send any format
   */
  public RequestWriter.ContentWriter getContentWriter(String expectedType) {
    return null;
  }

  /**
   * Create a new SolrResponse to hold the response from the server
   *
   * @param client the {@link SolrClient} the request will be sent to
   */
  protected abstract T createResponse(SolrClient client);

  /**
   * Send this request to a {@link SolrClient} and return the response
   *
   * @param client the SolrClient to communicate with
   * @param collection the collection to execute the request against
   * @return the response
   * @throws SolrServerException if there is an error on the Solr server
   * @throws IOException if there is a communication error
   */
  public final T process(SolrClient client, String collection)
      throws SolrServerException, IOException {
    long startNanos = System.nanoTime();
    T res = createResponse(client);
    var namedList = client.request(this, collection);
    res.setResponse(namedList);
    long endNanos = System.nanoTime();
    res.setElapsedTime(TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos));
    return res;
  }

  /**
   * Send this request to a {@link SolrClient} and return the response
   *
   * @param client the SolrClient to communicate with
   * @return the response
   * @throws SolrServerException if there is an error on the Solr server
   * @throws IOException if there is a communication error
   */
  public final T process(SolrClient client) throws SolrServerException, IOException {
    return process(client, null);
  }

  public String getCollection() {
    return getParams() == null ? null : getParams().get("collection");
  }

  @Deprecated // SOLR-17256 Slated for removal in Solr 10; only used internally
  public void setBasePath(String path) {
    if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

    this.basePath = path;
  }

  @Deprecated // SOLR-17256 Slated for removal in Solr 10; only used internally
  public String getBasePath() {
    return basePath;
  }

  public void addHeader(String key, String value) {
    if (headers == null) {
      headers = new HashMap<>();
    }
    headers.put(key, value);
  }

  public void addHeaders(Map<String, String> headers) {
    if (headers == null) {
      return;
    }
    if (this.headers == null) {
      this.headers = new HashMap<>();
    }
    this.headers.putAll(headers);
  }

  public Map<String, String> getHeaders() {
    if (headers == null) return null;
    return Collections.unmodifiableMap(headers);
  }
}
