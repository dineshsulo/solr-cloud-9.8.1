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
package org.apache.solr.core;

import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.CommonParams.PATH;
import static org.apache.solr.core.ConfigOverlay.ZNODEVER;
import static org.apache.solr.core.SolrConfig.PluginOpts.LAZY;
import static org.apache.solr.core.SolrConfig.PluginOpts.MULTI_OK;
import static org.apache.solr.core.SolrConfig.PluginOpts.NOOP;
import static org.apache.solr.core.SolrConfig.PluginOpts.REQUIRE_CLASS;
import static org.apache.solr.core.SolrConfig.PluginOpts.REQUIRE_NAME;
import static org.apache.solr.core.SolrConfig.PluginOpts.REQUIRE_NAME_IN_OVERLAY;
import static org.apache.solr.core.XmlConfigFile.assertWarnOrFail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.index.IndexDeletionPolicy;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Version;
import org.apache.solr.client.solrj.io.stream.expr.Expressible;
import org.apache.solr.cloud.RecoveryStrategy;
import org.apache.solr.cloud.ZkSolrResourceLoader;
import org.apache.solr.common.ConfigNode;
import org.apache.solr.common.MapSerializable;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.EnvUtils;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.pkg.PackageListeners;
import org.apache.solr.pkg.SolrPackageLoader;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.transform.TransformerFactory;
import org.apache.solr.rest.RestManager;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.IndexSchemaFactory;
import org.apache.solr.search.CacheConfig;
import org.apache.solr.search.CaffeineCache;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.ValueSourceParser;
import org.apache.solr.search.stats.StatsCache;
import org.apache.solr.servlet.SolrRequestParsers;
import org.apache.solr.spelling.QueryConverter;
import org.apache.solr.update.SolrIndexConfig;
import org.apache.solr.update.UpdateLog;
import org.apache.solr.update.processor.UpdateRequestProcessorChain;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.DOMConfigNode;
import org.apache.solr.util.DataConfigNode;
import org.apache.solr.util.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a static reference to a Config object modeling the main configuration data for a Solr
 * instance -- typically found in "solrconfig.xml".
 */
public class SolrConfig implements MapSerializable {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String DEFAULT_CONF_FILE = "solrconfig.xml";
  public static final String MIN_PREFIX_QUERY_TERM_LENGTH = "minPrefixQueryTermLength";
  public static final int DEFAULT_MIN_PREFIX_QUERY_TERM_LENGTH = -1;
  private final String resourceName;

  private int znodeVersion;
  ConfigNode root;
  int rootDataHashCode;
  private final SolrResourceLoader resourceLoader;
  private Properties substituteProperties;

  private RequestParams requestParams;

  public enum PluginOpts {
    MULTI_OK,
    REQUIRE_NAME,
    REQUIRE_NAME_IN_OVERLAY,
    REQUIRE_CLASS,
    LAZY,
    // EnumSet.of and/or EnumSet.copyOf(Collection) are annoying
    // because of type determination
    NOOP
  }

  private int multipartUploadLimitKB;

  private int formUploadLimitKB;

  private boolean handleSelect;

  private boolean addHttpRequestToContext;

  private final SolrRequestParsers solrRequestParsers;

  /**
   * TEST-ONLY: Creates a configuration instance from an instance directory and file name
   *
   * @param instanceDir the directory used to create the resource loader
   * @param name the configuration name used by the loader if the stream is null
   */
  public SolrConfig(Path instanceDir, String name) throws IOException {
    this(new SolrResourceLoader(instanceDir), name, true, null);
  }

  public static SolrConfig readFromResourceLoader(
      SolrResourceLoader loader,
      String name,
      boolean isConfigsetTrusted,
      Properties substitutableProperties) {
    try {
      return new SolrConfig(loader, name, isConfigsetTrusted, substitutableProperties);
    } catch (Exception e) {
      String resource;
      if (loader instanceof ZkSolrResourceLoader) {
        resource = name;
      } else {
        resource = loader.getConfigPath().resolve(name).toString();
      }
      throw new SolrException(
          ErrorCode.SERVER_ERROR, "Error loading solr config from " + resource, e);
    }
  }

  private class ResourceProvider implements Function<String, InputStream> {
    int zkVersion;
    int hash = -1;
    InputStream in;
    String fileName;

    ResourceProvider(InputStream in) throws IOException {
      this.in = in;
      if (in instanceof ZkSolrResourceLoader.ZkByteArrayInputStream) {
        ZkSolrResourceLoader.ZkByteArrayInputStream zkin =
            (ZkSolrResourceLoader.ZkByteArrayInputStream) in;
        zkVersion = zkin.getStat().getVersion();
        hash = Objects.hash(zkin.getStat().getCtime(), zkVersion, overlay.getVersion());
        this.fileName = zkin.fileName;
      } else if (in instanceof SolrResourceLoader.SolrFileInputStream) {
        SolrResourceLoader.SolrFileInputStream sfin = (SolrResourceLoader.SolrFileInputStream) in;
        zkVersion = (int) sfin.getLastModified();
        hash = Objects.hash(sfin.getLastModified(), overlay.getVersion());
      }
    }

    @Override
    public InputStream apply(String s) {
      return in;
    }
  }

  /**
   * Creates a configuration instance from a resource loader, a configuration name and a stream. If
   * the stream is null, the resource loader will open the configuration stream. If the stream is
   * not null, no attempt to load the resource will occur (the name is not used).
   *
   * @param loader the resource loader
   * @param name the configuration name
   * @param isConfigsetTrusted false if configset was uploaded using unsecured configset upload API,
   *     true otherwise
   * @param substitutableProperties optional properties to substitute into the XML
   */
  private SolrConfig(
      SolrResourceLoader loader,
      String name,
      boolean isConfigsetTrusted,
      Properties substitutableProperties) {
    this.resourceLoader = loader;
    this.resourceName = name;
    this.substituteProperties = substitutableProperties;
    getOverlay(); // just in case it is not initialized
    // insist we have non-null substituteProperties; it might get overlaid

    IndexSchemaFactory.VersionedConfig cfg =
        IndexSchemaFactory.getFromCache(
            name,
            loader,
            () -> {
              if (loader.getCoreContainer() == null) return null;
              return loader.getCoreContainer().getObjectCache();
            },
            () -> readXml(loader, name));
    this.root = cfg.data;
    this.znodeVersion = cfg.version;

    ConfigNode.SUBSTITUTES.set(
        key -> {
          if (substitutableProperties != null && substitutableProperties.containsKey(key)) {
            return substitutableProperties.getProperty(key);
          } else {
            Object o = overlay.getUserProps().get(key);
            return o == null ? null : o.toString();
          }
        });
    try {
      // This will hash the solrconfig.xml and user properties.
      rootDataHashCode = this.root.txt().hashCode();

      getRequestParams();
      initLibs(loader, isConfigsetTrusted);
      String val =
          root.child(
                  IndexSchema.LUCENE_MATCH_VERSION_PARAM,
                  () -> new RuntimeException("Missing: " + IndexSchema.LUCENE_MATCH_VERSION_PARAM))
              .txt();

      luceneMatchVersion = SolrConfig.parseLuceneVersionString(val);
      log.info("Using Lucene MatchVersion: {}", luceneMatchVersion);

      String indexConfigPrefix;

      // Old indexDefaults and mainIndex sections are deprecated and fails fast for
      // luceneMatchVersion=>LUCENE_4_0_0.
      // For older solrconfig.xml's we allow the old sections, but never mixed with the new
      // <indexConfig>
      boolean hasDeprecatedIndexConfig = get("indexDefaults").exists() || get("mainIndex").exists();
      if (hasDeprecatedIndexConfig) {
        throw new SolrException(
            ErrorCode.FORBIDDEN,
            "<indexDefaults> and <mainIndex> configuration sections are discontinued. Use <indexConfig> instead.");
      } else {
        indexConfigPrefix = "indexConfig";
      }
      assertWarnOrFail(
          "The <nrtMode> config has been discontinued and NRT mode is always used by Solr."
              + " This config will be removed in future versions.",
          get(indexConfigPrefix).get("nrtMode").isNull(),
          true);
      assertWarnOrFail(
          "Solr no longer supports forceful unlocking via the 'unlockOnStartup' option.  "
              + "This is no longer necessary for the default lockType except in situations where "
              + "it would be dangerous and should not be done.  For other lockTypes and/or "
              + "directoryFactory options it may also be dangerous and users must resolve "
              + "problematic locks manually.",
          !get(indexConfigPrefix).get("unlockOnStartup").exists(),
          true // 'fail' in trunk
          );

      // Parse indexConfig section, using mainIndex as backup in case old config is used
      indexConfig = new SolrIndexConfig(get("indexConfig"), null);

      booleanQueryMaxClauseCount =
          get("query").get("maxBooleanClauses").intVal(BooleanQuery.getMaxClauseCount());
      if (IndexSearcher.getMaxClauseCount() < booleanQueryMaxClauseCount) {
        log.warn(
            "solrconfig.xml: <maxBooleanClauses> of {} is greater than global limit of {} and will have no effect {}",
            booleanQueryMaxClauseCount,
            BooleanQuery.getMaxClauseCount(),
            "set 'maxBooleanClauses' in solr.xml to increase global limit");
      }
      prefixQueryMinPrefixLength =
          get("query")
              .get(MIN_PREFIX_QUERY_TERM_LENGTH)
              .intVal(DEFAULT_MIN_PREFIX_QUERY_TERM_LENGTH);

      // Warn about deprecated / discontinued parameters
      // boolToFilterOptimizer has had no effect since 3.1
      if (get("query").get("boolTofilterOptimizer").exists())
        log.warn(
            "solrconfig.xml: <boolTofilterOptimizer> is currently not implemented and has no effect.");
      if (get("query").get("HashDocSet").exists())
        log.warn("solrconfig.xml: <HashDocSet> is deprecated and no longer used.");

      // TODO: Old code - in case somebody wants to re-enable. Also see SolrIndexSearcher#search()
      //    filtOptEnabled = getBool("query/boolTofilterOptimizer/@enabled", false);
      //    filtOptCacheSize = getInt("query/boolTofilterOptimizer/@cacheSize",32);
      //    filtOptThreshold = getFloat("query/boolTofilterOptimizer/@threshold",.05f);

      useFilterForSortedQuery = get("query").get("useFilterForSortedQuery").boolVal(false);
      queryResultWindowSize = Math.max(1, get("query").get("queryResultWindowSize").intVal(1));
      queryResultMaxDocsCached =
          get("query").get("queryResultMaxDocsCached").intVal(Integer.MAX_VALUE);
      enableLazyFieldLoading = get("query").get("enableLazyFieldLoading").boolVal(false);

      filterCacheConfig =
          CacheConfig.getConfig(this, get("query").get("filterCache"), "query/filterCache");
      queryResultCacheConfig =
          CacheConfig.getConfig(
              this, get("query").get("queryResultCache"), "query/queryResultCache");
      documentCacheConfig =
          CacheConfig.getConfig(this, get("query").get("documentCache"), "query/documentCache");
      CacheConfig conf =
          CacheConfig.getConfig(this, get("query").get("fieldValueCache"), "query/fieldValueCache");
      if (conf == null) {
        Map<String, String> args = new HashMap<>();
        args.put(NAME, "fieldValueCache");
        args.put("size", "10000");
        args.put("initialSize", "10");
        conf = new CacheConfig(CaffeineCache.class, args, null);
      }
      fieldValueCacheConfig = conf;
      useColdSearcher = get("query").get("useColdSearcher").boolVal(false);
      dataDir = get("dataDir").txt();
      if (dataDir != null && dataDir.length() == 0) dataDir = null;

      org.apache.solr.search.SolrIndexSearcher.initRegenerators(this);

      if (get("jmx").exists()) {
        log.warn(
            "solrconfig.xml: <jmx> is no longer supported, use solr.xml:/metrics/reporter section instead");
      }

      httpCachingConfig = new HttpCachingConfig(this);

      maxWarmingSearchers = get("query").get("maxWarmingSearchers").intVal(1);
      slowQueryThresholdMillis = get("query").get("slowQueryThresholdMillis").intVal(-1);
      for (SolrPluginInfo plugin : plugins) loadPluginInfo(plugin);

      Map<String, CacheConfig> userCacheConfigs =
          CacheConfig.getMultipleConfigs(
              getResourceLoader(), this, "query/cache", get("query").getAll("cache"));
      List<PluginInfo> caches = getPluginInfos(SolrCache.class.getName());
      if (!caches.isEmpty()) {
        for (PluginInfo c : caches) {
          userCacheConfigs.put(c.name, CacheConfig.getConfig(this, "cache", c.attributes, null));
        }
      }
      this.userCacheConfigs = Collections.unmodifiableMap(userCacheConfigs);

      updateHandlerInfo = loadUpdatehandlerInfo();

      final var requestParsersNode = get("requestDispatcher").get("requestParsers");

      multipartUploadLimitKB =
          requestParsersNode.intAttr("multipartUploadLimitInKB", Integer.MAX_VALUE);
      if (multipartUploadLimitKB == -1) multipartUploadLimitKB = Integer.MAX_VALUE;

      formUploadLimitKB = requestParsersNode.intAttr("formdataUploadLimitInKB", Integer.MAX_VALUE);
      if (formUploadLimitKB == -1) formUploadLimitKB = Integer.MAX_VALUE;

      if (requestParsersNode.attr("enableRemoteStreaming") != null) {
        log.warn("Ignored deprecated enableRemoteStreaming in config; use sys-prop");
      }

      if (requestParsersNode.attr("enableStreamBody") != null) {
        log.warn("Ignored deprecated enableStreamBody in config; use sys-prop");
      }

      handleSelect = get("requestDispatcher").boolAttr("handleSelect", false);
      addHttpRequestToContext = requestParsersNode.boolAttr("addHttpRequestToContext", false);

      List<PluginInfo> argsInfos = getPluginInfos(InitParams.class.getName());
      if (argsInfos != null) {
        Map<String, InitParams> argsMap = new HashMap<>();
        for (PluginInfo p : argsInfos) {
          InitParams args = new InitParams(p);
          argsMap.put(args.name == null ? String.valueOf(args.hashCode()) : args.name, args);
        }
        this.initParams = Collections.unmodifiableMap(argsMap);
      }

      solrRequestParsers = new SolrRequestParsers(this);
      log.debug("Loaded SolrConfig: {}", name);

      // make resource loader aware of the config early on
      this.resourceLoader.setSolrConfig(this);
    } finally {
      ConfigNode.SUBSTITUTES.remove();
    }
  }

  private IndexSchemaFactory.VersionedConfig readXml(SolrResourceLoader loader, String name) {
    try (InputStream in = loader.openResource(name)) {
      ResourceProvider rp = new ResourceProvider(in);
      XmlConfigFile xml = new XmlConfigFile(loader, rp, name, null, "/config/", null);
      return new IndexSchemaFactory.VersionedConfig(
          rp.zkVersion,
          new DataConfigNode(new DOMConfigNode(xml.getDocument().getDocumentElement())));
    } catch (IOException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }
  }

  private static final AtomicBoolean versionWarningAlreadyLogged = new AtomicBoolean(false);

  @SuppressWarnings("ReferenceEquality") // Use of == is intentional here
  public static final Version parseLuceneVersionString(final String matchVersion) {
    final Version version;
    try {
      version = Version.parseLeniently(matchVersion);
    } catch (ParseException pe) {
      throw new SolrException(
          ErrorCode.SERVER_ERROR,
          "Invalid luceneMatchVersion.  Should be of the form 'V.V.V' (e.g. 4.8.0)",
          pe);
    }

    // The use of == is intentional here because the latest 'V.V.V' version will be equal() to
    // Version.LATEST, but will not be == to Version.LATEST unless 'LATEST' was supplied.
    if (version == Version.LATEST && !versionWarningAlreadyLogged.getAndSet(true)) {
      log.warn(
          "You should not use LATEST as luceneMatchVersion property: "
              + "if you use this setting, and then Solr upgrades to a newer release of Lucene, "
              + "sizable changes may happen. If precise back compatibility is important "
              + "then you should instead explicitly specify an actual Lucene version.");
    }

    return version;
  }

  public static final List<SolrPluginInfo> plugins =
      List.of(
          new SolrPluginInfo(
              SolrRequestHandler.class,
              SolrRequestHandler.TYPE,
              REQUIRE_NAME,
              REQUIRE_CLASS,
              MULTI_OK,
              LAZY),
          new SolrPluginInfo(
              QParserPlugin.class, "queryParser", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK),
          new SolrPluginInfo(
              Expressible.class, "expressible", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK),
          new SolrPluginInfo(
              QueryResponseWriter.class,
              "queryResponseWriter",
              REQUIRE_NAME,
              REQUIRE_CLASS,
              MULTI_OK,
              LAZY),
          new SolrPluginInfo(
              ValueSourceParser.class, "valueSourceParser", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK),
          new SolrPluginInfo(
              TransformerFactory.class, "transformer", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK),
          new SolrPluginInfo(
              SearchComponent.class, "searchComponent", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK),
          new SolrPluginInfo(
              UpdateRequestProcessorFactory.class,
              "updateProcessor",
              REQUIRE_NAME,
              REQUIRE_CLASS,
              MULTI_OK),
          new SolrPluginInfo(SolrCache.class, "cache", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK),
          // TODO: WTF is up with queryConverter???
          // it apparently *only* works as a singleton? - SOLR-4304
          // and even then -- only if there is a single SpellCheckComponent
          // because of queryConverter.setIndexAnalyzer
          new SolrPluginInfo(QueryConverter.class, "queryConverter", REQUIRE_NAME, REQUIRE_CLASS),
          // this is hackish, since it picks up all SolrEventListeners,
          // regardless of when/how/why they are used (or even if they are
          // declared outside of the appropriate context) but there's no nice
          // way around that in the PluginInfo framework
          new SolrPluginInfo(InitParams.class, InitParams.TYPE, MULTI_OK, REQUIRE_NAME_IN_OVERLAY),
          new SolrPluginInfo(
              it -> {
                List<ConfigNode> result = new ArrayList<>();
                result.addAll(it.get("query").getAll("listener"));
                result.addAll(it.get("updateHandler").getAll("listener"));
                return result;
              },
              SolrEventListener.class,
              "//listener",
              REQUIRE_CLASS,
              MULTI_OK,
              REQUIRE_NAME_IN_OVERLAY),
          new SolrPluginInfo(DirectoryFactory.class, "directoryFactory", REQUIRE_CLASS),
          new SolrPluginInfo(RecoveryStrategy.Builder.class, "recoveryStrategy"),
          new SolrPluginInfo(
              it -> it.get("indexConfig").getAll("deletionPolicy"),
              IndexDeletionPolicy.class,
              "indexConfig/deletionPolicy",
              REQUIRE_CLASS),
          new SolrPluginInfo(CodecFactory.class, "codecFactory", REQUIRE_CLASS),
          new SolrPluginInfo(IndexReaderFactory.class, "indexReaderFactory", REQUIRE_CLASS),
          new SolrPluginInfo(
              UpdateRequestProcessorChain.class, "updateRequestProcessorChain", MULTI_OK),
          new SolrPluginInfo(
              it -> it.get("updateHandler").getAll("updateLog"),
              UpdateLog.class,
              "updateHandler/updateLog"),
          new SolrPluginInfo(IndexSchemaFactory.class, "schemaFactory", REQUIRE_CLASS),
          new SolrPluginInfo(RestManager.class, "restManager"),
          new SolrPluginInfo(StatsCache.class, "statsCache", REQUIRE_CLASS),
          new SolrPluginInfo(CircuitBreaker.class, "circuitBreaker", REQUIRE_CLASS, MULTI_OK));
  public static final Map<String, SolrPluginInfo> classVsSolrPluginInfo;

  static {
    Map<String, SolrPluginInfo> map = new HashMap<>();
    for (SolrPluginInfo plugin : plugins) map.put(plugin.clazz.getName(), plugin);
    classVsSolrPluginInfo = Collections.unmodifiableMap(map);
  }

  public static class SolrPluginInfo {

    public final Class<?> clazz;
    public final String tag;
    public final Set<PluginOpts> options;
    final Function<SolrConfig, List<ConfigNode>> configReader;

    private SolrPluginInfo(Class<?> clz, String tag, PluginOpts... opts) {
      this(solrConfig -> solrConfig.root.getAll(null, tag), clz, tag, opts);
    }

    private SolrPluginInfo(
        Function<SolrConfig, List<ConfigNode>> configReader,
        Class<?> clz,
        String tag,
        PluginOpts... opts) {
      this.configReader = configReader;

      this.clazz = clz;
      this.tag = tag;
      this.options = opts == null ? Collections.emptySet() : EnumSet.of(NOOP, opts);
    }

    public String getCleanTag() {
      return tag.replace("/", "");
    }

    public String getTagCleanLower() {
      return getCleanTag().toLowerCase(Locale.ROOT);
    }
  }

  public static ConfigOverlay getConfigOverlay(SolrResourceLoader loader) {
    InputStream in = null;
    InputStreamReader isr = null;
    try {
      try {
        in = loader.openResource(ConfigOverlay.RESOURCE_NAME);
      } catch (IOException e) {
        // TODO: we should be explicitly looking for file not found exceptions
        // and logging if it's not the expected IOException
        // hopefully no problem, assume no overlay.json file
        return new ConfigOverlay(Collections.emptyMap(), -1);
      }

      int version = 0;
      if (in instanceof ZkSolrResourceLoader.ZkByteArrayInputStream) {
        version = ((ZkSolrResourceLoader.ZkByteArrayInputStream) in).getStat().getVersion();
        log.debug("Config overlay loaded. version : {} ", version);
      }
      if (in instanceof SolrResourceLoader.SolrFileInputStream) {
        // We should be ok, it is unlikely that a configOverlay is loaded decades apart and has the
        // same version after casting to an int
        version = (int) ((SolrResourceLoader.SolrFileInputStream) in).getLastModified();
        log.debug("Config overlay loaded. version : {} ", version);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) Utils.fromJSON(in);
      return new ConfigOverlay(m, version);
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error reading config overlay", e);
    } finally {
      IOUtils.closeQuietly(isr);
      IOUtils.closeQuietly(in);
    }
  }

  private Map<String, InitParams> initParams = Collections.emptyMap();

  public Map<String, InitParams> getInitParams() {
    return initParams;
  }

  protected UpdateHandlerInfo loadUpdatehandlerInfo() {
    return new UpdateHandlerInfo(get("updateHandler"));
  }

  /**
   * Converts a Java heap option-like config string to bytes. Valid suffixes are: 'k', 'm', 'g'
   * (case insensitive). If there is no suffix, the default unit is bytes. For example, 50k = 50KB,
   * 20m = 20MB, 4g = 4GB, 300 = 300 bytes
   *
   * @param configStr the config setting to parse
   * @return the size, in bytes. -1 if the given config string is empty
   */
  protected static long convertHeapOptionStyleConfigStringToBytes(String configStr) {
    if (configStr == null || configStr.isEmpty()) {
      return -1;
    }
    long multiplier = 1;
    String numericValueStr = configStr;
    char suffix = Character.toLowerCase(configStr.charAt(configStr.length() - 1));
    if (Character.isLetter(suffix)) {
      if (suffix == 'k') {
        multiplier = FileUtils.ONE_KB;
      } else if (suffix == 'm') {
        multiplier = FileUtils.ONE_MB;
      } else if (suffix == 'g') {
        multiplier = FileUtils.ONE_GB;
      } else {
        throw new RuntimeException(
            "Invalid suffix. Valid suffixes are 'k' (KB), 'm' (MB), 'g' (G). "
                + "No suffix means the amount is in bytes. ");
      }
      numericValueStr = configStr.substring(0, configStr.length() - 1);
    }
    try {
      return Long.parseLong(numericValueStr) * multiplier;
    } catch (NumberFormatException e) {
      throw new RuntimeException(
          "Invalid format. The config setting should be a long with an "
              + "optional letter suffix. Valid suffixes are 'k' (KB), 'm' (MB), 'g' (G). "
              + "No suffix means the amount is in bytes.");
    }
  }

  private void loadPluginInfo(SolrPluginInfo pluginInfo) {
    boolean requireName = pluginInfo.options.contains(REQUIRE_NAME);
    boolean requireClass = pluginInfo.options.contains(REQUIRE_CLASS);

    List<PluginInfo> result = readPluginInfos(pluginInfo, requireName, requireClass);

    if (1 < result.size() && !pluginInfo.options.contains(MULTI_OK)) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Found "
              + result.size()
              + " configuration sections when at most "
              + "1 is allowed matching expression: "
              + pluginInfo.getCleanTag());
    }
    if (!result.isEmpty()) pluginStore.put(pluginInfo.clazz.getName(), result);
  }

  public List<PluginInfo> readPluginInfos(
      SolrPluginInfo info, boolean requireName, boolean requireClass) {
    ArrayList<PluginInfo> result = new ArrayList<>();
    for (ConfigNode node : info.configReader.apply(this)) {
      PluginInfo pluginInfo =
          new PluginInfo(node, "[solrconfig.xml] " + info.tag, requireName, requireClass);
      if (pluginInfo.isEnabled()) result.add(pluginInfo);
    }
    return result;
  }

  public SolrRequestParsers getRequestParsers() {
    return solrRequestParsers;
  }

  /* The set of materialized parameters: */
  public final int booleanQueryMaxClauseCount;
  public final int prefixQueryMinPrefixLength;
  // SolrIndexSearcher - nutch optimizer -- Disabled since 3.1
  //  public final boolean filtOptEnabled;
  //  public final int filtOptCacheSize;
  //  public final float filtOptThreshold;
  // SolrIndexSearcher - caches configurations
  public final CacheConfig filterCacheConfig;
  public final CacheConfig queryResultCacheConfig;
  public final CacheConfig documentCacheConfig;
  public final CacheConfig fieldValueCacheConfig;
  public final Map<String, CacheConfig> userCacheConfigs;
  // SolrIndexSearcher - more...
  public final boolean useFilterForSortedQuery;
  public final int queryResultWindowSize;
  public final int queryResultMaxDocsCached;
  public final boolean enableLazyFieldLoading;

  // IndexConfig settings
  public final SolrIndexConfig indexConfig;

  protected UpdateHandlerInfo updateHandlerInfo;

  private Map<String, List<PluginInfo>> pluginStore = new LinkedHashMap<>();

  public final int maxWarmingSearchers;
  public final boolean useColdSearcher;
  public final Version luceneMatchVersion;
  protected String dataDir;
  public final int slowQueryThresholdMillis; // threshold above which a query is considered slow

  private final HttpCachingConfig httpCachingConfig;

  public HttpCachingConfig getHttpCachingConfig() {
    return httpCachingConfig;
  }

  public static class HttpCachingConfig implements MapSerializable {

    /** For extracting Expires "ttl" from <cacheControl> config */
    private static final Pattern MAX_AGE = Pattern.compile("\\bmax-age=(\\d+)");

    @Override
    public Map<String, Object> toMap(Map<String, Object> map) {
      // Could have nulls
      return Utils.makeMap(
          "never304",
          never304,
          "etagSeed",
          etagSeed,
          "lastModFrom",
          lastModFrom.name().toLowerCase(Locale.ROOT),
          "cacheControl",
          cacheControlHeader);
    }

    public enum LastModFrom {
      OPENTIME,
      DIRLASTMOD,
      BOGUS;

      /** Input must not be null */
      public static LastModFrom parse(final String s) {
        try {
          return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
          log.warn("Unrecognized value for lastModFrom: {}", s, e);
          return BOGUS;
        }
      }
    }

    private final boolean never304;
    private final String etagSeed;
    private final String cacheControlHeader;
    private final Long maxAge;
    private final LastModFrom lastModFrom;
    private ConfigNode configNode;

    private HttpCachingConfig(SolrConfig conf) {
      configNode = conf.root;

      // "requestDispatcher/httpCaching/";
      ConfigNode httpCaching = get("requestDispatcher").get("httpCaching");
      never304 = httpCaching.boolAttr("never304", false);

      etagSeed = httpCaching.attr("etagSeed", "Solr");

      lastModFrom = LastModFrom.parse(httpCaching.attr("lastModFrom", "openTime"));

      cacheControlHeader = httpCaching.get("cacheControl").txt();

      Long tmp = null; // maxAge
      if (null != cacheControlHeader) {
        try {
          final Matcher ttlMatcher = MAX_AGE.matcher(cacheControlHeader);
          final String ttlStr = ttlMatcher.find() ? ttlMatcher.group(1) : null;
          tmp = (null != ttlStr && !ttlStr.isEmpty()) ? Long.valueOf(ttlStr) : null;
        } catch (Exception e) {
          log.warn(
              "Ignoring exception while attempting to extract max-age from cacheControl config: {}",
              cacheControlHeader,
              e);
        }
      }
      maxAge = tmp;
    }

    private ConfigNode get(String name) {
      return configNode.get(name);
    }

    public boolean isNever304() {
      return never304;
    }

    public String getEtagSeed() {
      return etagSeed;
    }

    /** null if no Cache-Control header */
    public String getCacheControlHeader() {
      return cacheControlHeader;
    }

    /** null if no max age limitation */
    public Long getMaxAge() {
      return maxAge;
    }

    public LastModFrom getLastModFrom() {
      return lastModFrom;
    }
  }

  public static class UpdateHandlerInfo implements MapSerializable {
    public final String className;
    public final int autoCommmitMaxDocs,
        autoCommmitMaxTime,
        autoSoftCommmitMaxDocs,
        autoSoftCommmitMaxTime;
    public final long autoCommitMaxSizeBytes;
    public final boolean openSearcher; // is opening a new searcher part of hard autocommit?
    public final boolean commitWithinSoftCommit;
    public final boolean aggregateNodeLevelMetricsEnabled;

    /**
     * @param autoCommmitMaxDocs set -1 as default
     * @param autoCommmitMaxTime set -1 as default
     * @param autoCommitMaxSize set -1 as default
     */
    public UpdateHandlerInfo(
        String className,
        int autoCommmitMaxDocs,
        int autoCommmitMaxTime,
        long autoCommitMaxSize,
        boolean openSearcher,
        int autoSoftCommmitMaxDocs,
        int autoSoftCommmitMaxTime,
        boolean commitWithinSoftCommit) {
      this.className = className;
      this.autoCommmitMaxDocs = autoCommmitMaxDocs;
      this.autoCommmitMaxTime = autoCommmitMaxTime;
      this.autoCommitMaxSizeBytes = autoCommitMaxSize;
      this.openSearcher = openSearcher;

      this.autoSoftCommmitMaxDocs = autoSoftCommmitMaxDocs;
      this.autoSoftCommmitMaxTime = autoSoftCommmitMaxTime;

      this.commitWithinSoftCommit = commitWithinSoftCommit;
      this.aggregateNodeLevelMetricsEnabled = false;
    }

    public UpdateHandlerInfo(ConfigNode updateHandler) {
      ConfigNode autoCommit = updateHandler.get("autoCommit");
      this.className = updateHandler.attr("class");
      this.autoCommmitMaxDocs = autoCommit.get("maxDocs").intVal(-1);
      this.autoCommmitMaxTime = autoCommit.get("maxTime").intVal(-1);
      this.autoCommitMaxSizeBytes =
          convertHeapOptionStyleConfigStringToBytes(autoCommit.get("maxSize").txt());
      this.openSearcher = autoCommit.get("openSearcher").boolVal(true);
      this.autoSoftCommmitMaxDocs = updateHandler.get("autoSoftCommit").get("maxDocs").intVal(-1);
      this.autoSoftCommmitMaxTime = updateHandler.get("autoSoftCommit").get("maxTime").intVal(-1);
      this.commitWithinSoftCommit =
          updateHandler.get("commitWithin").get("softCommit").boolVal(true);
      this.aggregateNodeLevelMetricsEnabled =
          updateHandler.boolAttr("aggregateNodeLevelMetricsEnabled", false);
    }

    @Override
    public Map<String, Object> toMap(Map<String, Object> map) {
      map.put("commitWithin", Map.of("softCommit", commitWithinSoftCommit));
      map.put(
          "autoCommit",
          Map.of(
              "maxDocs", autoCommmitMaxDocs,
              "maxTime", autoCommmitMaxTime,
              "openSearcher", openSearcher));
      map.put(
          "autoSoftCommit",
          Map.of("maxDocs", autoSoftCommmitMaxDocs, "maxTime", autoSoftCommmitMaxTime));
      return map;
    }
  }

  //  public Map<String, List<PluginInfo>> getUpdateProcessorChainInfo() { return
  // updateProcessorChainInfo; }

  public UpdateHandlerInfo getUpdateHandlerInfo() {
    return updateHandlerInfo;
  }

  public String getDataDir() {
    return dataDir;
  }

  /**
   * SolrConfig keeps a repository of plugins by the type. The known interfaces are the types.
   *
   * @param type The key is FQN of the plugin class there are a few known types : SolrFormatter,
   *     SolrFragmenter SolrRequestHandler,QParserPlugin, QueryResponseWriter,ValueSourceParser,
   *     SearchComponent, QueryConverter, SolrEventListener, DirectoryFactory, IndexDeletionPolicy,
   *     IndexReaderFactory, {@link TransformerFactory}
   */
  public List<PluginInfo> getPluginInfos(String type) {
    List<PluginInfo> result = pluginStore.get(type);
    SolrPluginInfo info = classVsSolrPluginInfo.get(type);
    if (info != null
        && (info.options.contains(REQUIRE_NAME)
            || info.options.contains(REQUIRE_NAME_IN_OVERLAY))) {
      Map<String, Map<String, Object>> infos = overlay.getNamedPlugins(info.getCleanTag());
      if (!infos.isEmpty()) {
        LinkedHashMap<String, PluginInfo> map = new LinkedHashMap<>();
        if (result != null)
          for (PluginInfo pluginInfo : result) {
            // just create a UUID for the time being so that map key is not null
            String name =
                pluginInfo.name == null
                    ? UUID.randomUUID().toString().toLowerCase(Locale.ROOT)
                    : pluginInfo.name;
            map.put(name, pluginInfo);
          }
        for (Map.Entry<String, Map<String, Object>> e : infos.entrySet()) {
          map.put(e.getKey(), new PluginInfo(info.getCleanTag(), e.getValue()));
        }
        result = new ArrayList<>(map.values());
      }
    }
    return result == null ? Collections.emptyList() : result;
  }

  public PluginInfo getPluginInfo(String type) {
    List<PluginInfo> result = pluginStore.get(type);
    if (result == null || result.isEmpty()) {
      return null;
    }
    if (1 == result.size()) {
      return result.get(0);
    }

    throw new SolrException(
        SolrException.ErrorCode.SERVER_ERROR, "Multiple plugins configured for type: " + type);
  }

  public static final String LIB_ENABLED_SYSPROP = "solr.config.lib.enabled";

  private void initLibs(SolrResourceLoader loader, boolean isConfigsetTrusted) {
    // TODO Want to remove SolrResourceLoader.getInstancePath; it can be on a Standalone subclass.
    // For Zk subclass, it's needed for the time being as well.  We could remove that one if we
    // remove two things in SolrCloud: (1) instancePath/lib  and (2) solrconfig lib directives with
    // relative paths. Can wait till 9.0.
    Path instancePath = loader.getInstancePath();
    List<URL> urls = new ArrayList<>();

    Path libPath = instancePath.resolve("lib");
    if (Files.exists(libPath)) {
      try {
        urls.addAll(SolrResourceLoader.getURLs(libPath));
      } catch (IOException e) {
        log.warn("Couldn't add files from {} to classpath: {}", libPath, e);
      }
    }

    boolean libDirectiveAllowed = EnvUtils.getPropertyAsBool(LIB_ENABLED_SYSPROP, false);
    List<ConfigNode> nodes = root.getAll("lib");
    if (nodes != null && nodes.size() > 0) {
      if (!isConfigsetTrusted) {
        throw new SolrException(
            ErrorCode.UNAUTHORIZED,
            "The configset for this collection was uploaded without any authentication in place,"
                + " and use of <lib> is not available for collections with untrusted configsets. To use this component, re-upload the configset"
                + " after enabling authentication and authorization.");
      }

      if (!libDirectiveAllowed) {
        log.warn(
            "Configset references one or more <lib/> directives, but <lib/> usage is disabled on this Solr node.  Either remove all <lib/> tags from the relevant configset, or enable use of this feature by setting '{}=true'",
            LIB_ENABLED_SYSPROP);
      } else {
        urls.addAll(processLibDirectives(nodes, instancePath));
      }
    }

    if (!urls.isEmpty()) {
      loader.addToClassLoader(urls);
      loader.reloadLuceneSPI();
    }
  }

  private List<URL> processLibDirectives(List<ConfigNode> nodes, Path instancePath) {
    final var urls = new ArrayList<URL>();

    for (int i = 0; i < nodes.size(); i++) {
      ConfigNode node = nodes.get(i);
      String baseDir = node.attr("dir");
      String path = node.attr(PATH);
      if (null != baseDir) {
        // :TODO: add support for a simpler 'glob' mutually exclusive of regex
        Path dir = instancePath.resolve(baseDir);
        String regex = node.attr("regex");
        try {
          if (regex == null) urls.addAll(SolrResourceLoader.getURLs(dir));
          else urls.addAll(SolrResourceLoader.getFilteredURLs(dir, regex));
        } catch (IOException e) {
          log.warn("Couldn't add files from {} filtered by {} to classpath: {}", dir, regex, e);
        }
      } else if (null != path) {
        final Path dir = instancePath.resolve(path);
        try {
          urls.add(dir.toUri().toURL());
        } catch (MalformedURLException e) {
          log.warn("Couldn't add file {} to classpath: {}", dir, e);
        }
      } else {
        throw new RuntimeException("lib: missing mandatory attributes: 'dir' or 'path'");
      }
    }

    return urls;
  }

  public int getMultipartUploadLimitKB() {
    return multipartUploadLimitKB;
  }

  public int getFormUploadLimitKB() {
    return formUploadLimitKB;
  }

  public boolean isHandleSelect() {
    return handleSelect;
  }

  public boolean isAddHttpRequestToContext() {
    return addHttpRequestToContext;
  }

  @Override
  public Map<String, Object> toMap(Map<String, Object> result) {
    if (znodeVersion > -1) result.put(ZNODEVER, znodeVersion);
    if (luceneMatchVersion != null)
      result.put(IndexSchema.LUCENE_MATCH_VERSION_PARAM, luceneMatchVersion.toString());
    result.put("updateHandler", getUpdateHandlerInfo());
    Map<String, Object> m = new LinkedHashMap<>();
    result.put("query", m);
    m.put("useFilterForSortedQuery", useFilterForSortedQuery);
    m.put("queryResultWindowSize", queryResultWindowSize);
    m.put("queryResultMaxDocsCached", queryResultMaxDocsCached);
    m.put("enableLazyFieldLoading", enableLazyFieldLoading);
    m.put("maxBooleanClauses", booleanQueryMaxClauseCount);
    m.put(MIN_PREFIX_QUERY_TERM_LENGTH, prefixQueryMinPrefixLength);

    for (SolrPluginInfo plugin : plugins) {
      List<PluginInfo> infos = getPluginInfos(plugin.clazz.getName());
      if (infos == null || infos.isEmpty()) continue;
      String tag = plugin.getCleanTag();
      tag = tag.replace("/", "");
      if (plugin.options.contains(PluginOpts.REQUIRE_NAME)) {
        LinkedHashMap<String, Object> items = new LinkedHashMap<>();
        for (PluginInfo info : infos) {
          // TODO remove after fixing https://issues.apache.org/jira/browse/SOLR-13706
          if (info.type.equals("searchComponent") && info.name.equals("highlight")) continue;
          items.put(info.name, info);
        }
        for (Map.Entry<String, Map<String, Object>> e :
            overlay.getNamedPlugins(plugin.tag).entrySet()) {
          items.put(e.getKey(), e.getValue());
        }
        result.put(tag, items);
      } else {
        if (plugin.options.contains(MULTI_OK)) {
          ArrayList<MapSerializable> l = new ArrayList<>();
          for (PluginInfo info : infos) l.add(info);
          result.put(tag, l);
        } else {
          result.put(tag, infos.get(0));
        }
      }
    }

    addCacheConfig(
        m, filterCacheConfig, queryResultCacheConfig, documentCacheConfig, fieldValueCacheConfig);
    m = new LinkedHashMap<>();
    result.put("requestDispatcher", m);
    m.put("handleSelect", handleSelect);
    if (httpCachingConfig != null) m.put("httpCaching", httpCachingConfig);
    m.put(
        "requestParsers",
        Map.of(
            "multipartUploadLimitKB",
            multipartUploadLimitKB,
            "formUploadLimitKB",
            formUploadLimitKB,
            "addHttpRequestToContext",
            addHttpRequestToContext));
    if (indexConfig != null) result.put("indexConfig", indexConfig);

    // TODO there is more to add

    return result;
  }

  private void addCacheConfig(Map<String, Object> queryMap, CacheConfig... cache) {
    if (cache == null) return;
    for (CacheConfig config : cache) if (config != null) queryMap.put(config.getNodeName(), config);
  }

  public Properties getSubstituteProperties() {
    Map<String, Object> p = getOverlay().getUserProps();
    if (p == null || p.isEmpty()) return substituteProperties;
    Properties result = new Properties(substituteProperties);
    result.putAll(p);
    return result;
  }

  private ConfigOverlay overlay;

  public ConfigOverlay getOverlay() {
    if (overlay == null) {
      overlay = getConfigOverlay(resourceLoader);
    }
    return overlay;
  }

  public RequestParams getRequestParams() {
    if (requestParams == null) {
      return refreshRequestParams();
    }
    return requestParams;
  }

  /**
   * The version of package that should be loaded for a given package name This information is
   * stored in the params.json in the same configset If params.json is absent or there is no
   * corresponding version specified for a given package, this returns a null and the latest is used
   * by the caller
   */
  public String maxPackageVersion(String pkg) {
    RequestParams.ParamSet p = getRequestParams().getParams(PackageListeners.PACKAGE_VERSIONS);
    if (p == null) {
      return null;
    }
    Object o = p.get().get(pkg);
    if (o == null || SolrPackageLoader.LATEST.equals(o)) return null;
    return o.toString();
  }

  public RequestParams refreshRequestParams() {
    requestParams = RequestParams.getFreshRequestParams(resourceLoader, requestParams);
    if (log.isDebugEnabled()) {
      log.debug("current version of requestparams : {}", requestParams.getZnodeVersion());
    }
    return requestParams;
  }

  public SolrResourceLoader getResourceLoader() {
    return resourceLoader;
  }

  public int getZnodeVersion() {
    return znodeVersion;
  }

  public String getName() {
    return resourceName;
  }

  public String getResourceName() {
    return resourceName;
  }

  /**
   * fetches a child node by name. An "empty node" is returned if the child does not exist This
   * never returns a null
   */
  public ConfigNode get(String name) {
    if (!overlay.hasKey(name)) {
      // there is no overlay
      return root.get(name);
    }
    return new OverlaidConfigNode(overlay, name, null, root.get(name));
  }

  public ConfigNode get(String name, Predicate<ConfigNode> test) {
    return root.get(name, test);
  }

  /**
   * Generates a String ID to represent the {@link SolrConfig}
   *
   * <p>Relies on the name of the SolrConfig, {@link String#hashCode()} to generate a "unique" id
   * for the solr.xml data (including substitutions), and the version of the overlay. These 3 pieces
   * of data should combine to make a "unique" identifier for SolrConfigs, since those are
   * ultimately all inputs to modifying the solr.xml result.
   */
  public String effectiveId() {
    return getName() + "-" + znodeVersion + "-" + rootDataHashCode;
  }
}
