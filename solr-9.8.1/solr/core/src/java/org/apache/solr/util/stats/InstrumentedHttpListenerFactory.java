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

package org.apache.solr.util.stats;

import static org.apache.solr.metrics.SolrMetricManager.mkName;

import com.codahale.metrics.Timer;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.Locale;
import java.util.Map;
import org.apache.solr.client.solrj.impl.HttpListenerFactory;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.CollectionUtil;
import org.apache.solr.metrics.SolrMetricProducer;
import org.apache.solr.metrics.SolrMetricsContext;
import org.apache.solr.util.tracing.TraceUtils;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;

/**
 * A HttpListenerFactory tracking metrics and distributed tracing. The Metrics are inspired and
 * partially copied from dropwizard httpclient library.
 */
public class InstrumentedHttpListenerFactory implements SolrMetricProducer, HttpListenerFactory {

  public interface NameStrategy {
    String getNameFor(String scope, Request request);
  }

  private static final NameStrategy QUERYLESS_URL_AND_METHOD =
      (scope, request) -> {
        String schemeHostPort =
            request.getScheme()
                + "://"
                + request.getHost()
                + ":"
                + request.getPort()
                + request.getPath();
        return mkName(schemeHostPort + "." + methodNameString(request), scope);
      };

  private static final NameStrategy METHOD_ONLY =
      (scope, request) -> mkName(methodNameString(request), scope);

  private static final NameStrategy HOST_AND_METHOD =
      (scope, request) -> {
        String schemeHostPort =
            request.getScheme() + "://" + request.getHost() + ":" + request.getPort();
        return mkName(schemeHostPort + "." + methodNameString(request), scope);
      };

  public static final Map<String, NameStrategy> KNOWN_METRIC_NAME_STRATEGIES =
      CollectionUtil.newHashMap(3);

  static {
    KNOWN_METRIC_NAME_STRATEGIES.put("queryLessURLAndMethod", QUERYLESS_URL_AND_METHOD);
    KNOWN_METRIC_NAME_STRATEGIES.put("hostAndMethod", HOST_AND_METHOD);
    KNOWN_METRIC_NAME_STRATEGIES.put("methodOnly", METHOD_ONLY);
  }

  protected SolrMetricsContext solrMetricsContext;
  protected String scope;
  protected NameStrategy nameStrategy;

  public InstrumentedHttpListenerFactory(NameStrategy nameStrategy) {
    this.nameStrategy = nameStrategy;
  }

  private static String methodNameString(Request request) {
    return request.getMethod().toLowerCase(Locale.ROOT) + ".requests";
  }

  @Override
  public RequestResponseListener get() {
    return new RequestResponseListener() {
      Timer.Context timerContext;
      Tracer tracer = GlobalTracer.get();
      Span span;

      @Override
      public void onQueued(Request request) {
        // do tracing onQueued because it's called from Solr's thread
        span = tracer.activeSpan();
        TraceUtils.injectTraceContext(request, span);
      }

      @Override
      public void onBegin(Request request) {
        if (solrMetricsContext != null) {
          timerContext = timer(request).time();
        }
        if (span != null) {
          span.log("Client Send"); // perhaps delayed a bit after the span started in enqueue
        }
      }

      @Override
      public void onComplete(Result result) {
        if (timerContext != null) {
          timerContext.stop();
        }
        if (result.isFailed() && span != null) {
          span.log(result.toString()); // logs failure info and interesting stuff
        }
      }
    };
  }

  private Timer timer(Request request) {
    return solrMetricsContext.timer(nameStrategy.getNameFor(scope, request));
  }

  @Override
  public void initializeMetrics(SolrMetricsContext parentContext, String scope) {
    this.solrMetricsContext = parentContext;
    this.scope = scope;
  }

  @Override
  public SolrMetricsContext getSolrMetricsContext() {
    return solrMetricsContext;
  }

  public static NameStrategy getNameStrategy(String name) {
    var nameStrategy = KNOWN_METRIC_NAME_STRATEGIES.get(name);
    if (nameStrategy == null) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Unknown metricNameStrategy: "
              + name
              + " found. Must be one of: "
              + KNOWN_METRIC_NAME_STRATEGIES.keySet());
    }
    return nameStrategy;
  }
}
