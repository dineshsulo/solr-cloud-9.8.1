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
package org.apache.solr.schema;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;

import java.io.IOException;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queries.payloads.PayloadDecoder;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.ResourceLoaderAware;
import org.apache.lucene.util.Version;
import org.apache.solr.analysis.TokenizerChain;
import org.apache.solr.common.ConfigNode;
import org.apache.solr.common.MapSerializable;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.SolrClassLoader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.Cache;
import org.apache.solr.common.util.DOMUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.ConfigSetService;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.response.SchemaXmlWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.similarities.SchemaSimilarityFactory;
import org.apache.solr.uninverting.UninvertingReader;
import org.apache.solr.util.ConcurrentLRUCache;
import org.apache.solr.util.PayloadUtils;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>IndexSchema</code> contains information about the valid fields in an index and the types of
 * those fields.
 */
public class IndexSchema {
  public static final String COPY_FIELD = "copyField";
  public static final String COPY_FIELDS = COPY_FIELD + "s";
  public static final String DEFAULT_SCHEMA_FILE = "schema.xml";
  public static final String DESTINATION = "dest";
  public static final String DYNAMIC_FIELD = "dynamicField";
  public static final String DYNAMIC_FIELDS = DYNAMIC_FIELD + "s";
  public static final String FIELD = "field";
  public static final String FIELDS = FIELD + "s";
  public static final String FIELD_TYPE = "fieldType";
  public static final String FIELD_TYPES = FIELD_TYPE + "s";
  public static final String INTERNAL_POLY_FIELD_PREFIX = "*" + FieldType.POLY_FIELD_SEPARATOR;
  public static final String LUCENE_MATCH_VERSION_PARAM = "luceneMatchVersion";
  public static final String MAX_CHARS = "maxChars";
  public static final String NAME = "name";
  public static final String NEST_PARENT_FIELD_NAME = "_nest_parent_";
  public static final String NEST_PATH_FIELD_NAME = "_nest_path_";
  public static final String REQUIRED = "required";
  public static final String SCHEMA = "schema";
  public static final String SIMILARITY = "similarity";
  public static final String SOURCE = "source";
  public static final String TYPE = "type";
  public static final String TYPES = "types";
  public static final String ROOT_FIELD_NAME = "_root_";
  public static final String UNIQUE_KEY = "uniqueKey";
  public static final String VERSION = "version";

  private static final String DESTINATION_DYNAMIC_BASE = "destDynamicBase";
  private static final String SOURCE_DYNAMIC_BASE = "sourceDynamicBase";
  private static final String SOURCE_EXPLICIT_FIELDS = "sourceExplicitFields";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected String resourceName;
  protected String name;
  protected final Version luceneVersion;
  protected float version;
  protected final SolrResourceLoader loader;
  protected final SolrClassLoader solrClassLoader;
  protected final Properties substitutableProperties;

  protected Map<String, SchemaField> fields = new HashMap<>();
  protected Map<String, FieldType> fieldTypes = new HashMap<>();

  protected List<SchemaField> fieldsWithDefaultValue = new ArrayList<>();
  protected Collection<SchemaField> requiredFields = new HashSet<>();
  protected DynamicField[] dynamicFields = new DynamicField[] {};

  public DynamicField[] getDynamicFields() {
    return dynamicFields;
  }

  private static final Set<String> FIELDTYPE_KEYS = Set.of("fieldtype", "fieldType");
  private static final Set<String> FIELD_KEYS = Set.of("dynamicField", "field");

  protected Cache<String, SchemaField> dynamicFieldCache =
      new ConcurrentLRUCache<>(10000, 8000, 9000, 100, false, false, null);

  private Analyzer indexAnalyzer;
  private Analyzer queryAnalyzer;

  protected List<SchemaAware> schemaAware = new ArrayList<>();

  protected Map<String, List<CopyField>> copyFieldsMap = new HashMap<>();

  public Map<String, List<CopyField>> getCopyFieldsMap() {
    return Collections.unmodifiableMap(copyFieldsMap);
  }

  protected DynamicCopy[] dynamicCopyFields = new DynamicCopy[] {};

  public DynamicCopy[] getDynamicCopyFields() {
    return dynamicCopyFields;
  }

  private Map<FieldType, PayloadDecoder> decoders =
      new HashMap<>(); // cache to avoid scanning token filters repeatedly, unnecessarily

  /** keys are all fields copied to, count is num of copyField directives that target them. */
  protected Map<SchemaField, Integer> copyFieldTargetCounts = new HashMap<>();

  private ConfigNode rootNode;

  /**
   * Constructs a schema using the specified resource name and stream. By default, this follows the
   * normal config path directory searching rules.
   *
   * @see SolrResourceLoader#openResource
   */
  public IndexSchema(
      String name,
      ConfigSetService.ConfigResource schemaResource,
      Version luceneVersion,
      SolrResourceLoader resourceLoader,
      Properties substitutableProperties) {
    this(luceneVersion, resourceLoader, substitutableProperties);

    this.resourceName = Objects.requireNonNull(name);
    if (substitutableProperties != null)
      ConfigNode.SUBSTITUTES.set(substitutableProperties::getProperty);
    try {
      readSchema(schemaResource);
      loader.inform(loader);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      ConfigNode.SUBSTITUTES.remove();
    }
  }

  protected IndexSchema(
      Version luceneVersion, SolrResourceLoader loader, Properties substitutableProperties) {
    this.luceneVersion = Objects.requireNonNull(luceneVersion);
    this.loader = loader;
    this.solrClassLoader = loader.getSchemaLoader() == null ? loader : loader.getSchemaLoader();
    this.substitutableProperties = substitutableProperties;
  }

  /**
   * The resource loader to be used to load components related to the schema when the schema is
   * loading / initialising. It should <em>not</em> be used for any other purpose or time; consider
   * {@link SolrCore#getResourceLoader()} instead.
   *
   * @since solr 1.4
   */
  public SolrResourceLoader getResourceLoader() {
    // TODO consider asserting the schema has not finished loading somehow?
    return loader;
  }

  /** Gets the name of the resource used to instantiate this schema. */
  public String getResourceName() {
    return resourceName;
  }

  public SolrClassLoader getSolrClassLoader() {
    return solrClassLoader;
  }

  /** Sets the name of the resource used to instantiate this schema. */
  public void setResourceName(String resourceName) {
    this.resourceName = resourceName;
  }

  /** Gets the name of the schema as specified in the schema resource. */
  public String getSchemaName() {
    return name;
  }

  /** The Default Lucene Match Version for this IndexSchema */
  public Version getDefaultLuceneMatchVersion() {
    return luceneVersion;
  }

  public float getVersion() {
    return version;
  }

  /**
   * Provides direct access to the Map containing all explicit (ie: non-dynamic) fields in the
   * index, keyed on field name.
   *
   * <p>Modifying this Map (or any item in it) will affect the real schema
   *
   * <p>NOTE: this function is not thread safe. However, it is safe to use within the standard
   * <code>inform( SolrCore core )</code> function for <code>SolrCoreAware</code> classes. Outside
   * <code>inform</code>, this could potentially throw a ConcurrentModificationException
   */
  public Map<String, SchemaField> getFields() {
    return fields;
  }

  /**
   * Provides direct access to the Map containing all Field Types in the index, keyed on field type
   * name.
   *
   * <p>Modifying this Map (or any item in it) will affect the real schema. However if you make any
   * modifications, be sure to call {@link IndexSchema#refreshAnalyzers()} to update the Analyzers
   * for the registered fields.
   *
   * <p>NOTE: this function is not thread safe. However, it is safe to use within the standard
   * <code>inform( SolrCore core )</code> function for <code>SolrCoreAware</code> classes. Outside
   * <code>inform</code>, this could potentially throw a ConcurrentModificationException
   */
  public Map<String, FieldType> getFieldTypes() {
    return fieldTypes;
  }

  /** Provides direct access to the List containing all fields with a default value */
  public List<SchemaField> getFieldsWithDefaultValue() {
    return fieldsWithDefaultValue;
  }

  /**
   * Provides direct access to the List containing all required fields. This list contains all
   * fields with default values.
   */
  public Collection<SchemaField> getRequiredFields() {
    return requiredFields;
  }

  protected Similarity similarity;

  /** Returns the Similarity used for this index */
  public Similarity getSimilarity() {
    if (null == similarity) {
      similarity = similarityFactory.getSimilarity();
    }
    return similarity;
  }

  protected SimilarityFactory similarityFactory;
  protected boolean isExplicitSimilarity = false;

  /** Returns the SimilarityFactory that constructed the Similarity for this index */
  public SimilarityFactory getSimilarityFactory() {
    return similarityFactory;
  }

  /**
   * Returns the Analyzer used when indexing documents for this index
   *
   * <p>This Analyzer is field (and dynamic field) name aware, and delegates to a field specific
   * Analyzer based on the field type.
   */
  public Analyzer getIndexAnalyzer() {
    return indexAnalyzer;
  }

  /**
   * Returns the Analyzer used when searching this index
   *
   * <p>This Analyzer is field (and dynamic field) name aware, and delegates to a field specific
   * Analyzer based on the field type.
   */
  public Analyzer getQueryAnalyzer() {
    return queryAnalyzer;
  }

  protected SchemaField uniqueKeyField;

  /**
   * Unique Key field specified in the schema file
   *
   * @return null if this schema has no unique key field
   */
  public SchemaField getUniqueKeyField() {
    return uniqueKeyField;
  }

  protected String uniqueKeyFieldName;
  protected FieldType uniqueKeyFieldType;

  /**
   * The raw (field type encoded) value of the Unique Key field for the specified Document
   *
   * @return null if this schema has no unique key field
   * @see #printableUniqueKey
   */
  public IndexableField getUniqueKeyField(org.apache.lucene.document.Document doc) {
    return doc.getField(uniqueKeyFieldName); // this should return null if name is null
  }

  /**
   * The printable value of the Unique Key field for the specified Document
   *
   * @return null if this schema has no unique key field
   */
  public String printableUniqueKey(org.apache.lucene.document.Document doc) {
    IndexableField f = doc.getField(uniqueKeyFieldName);
    return f == null ? null : uniqueKeyFieldType.toExternal(f);
  }

  /** Like {@link #printableUniqueKey(org.apache.lucene.document.Document)} */
  public String printableUniqueKey(SolrDocument solrDoc) {
    Object val = solrDoc.getFieldValue(uniqueKeyFieldName);
    if (val == null) {
      return null;
    } else if (val instanceof IndexableField) {
      return uniqueKeyFieldType.toExternal((IndexableField) val);
    } else {
      return val.toString();
    }
  }

  /** Like {@link #printableUniqueKey(org.apache.lucene.document.Document)} */
  public String printableUniqueKey(SolrInputDocument solrDoc) {
    Object val = solrDoc.getFieldValue(uniqueKeyFieldName);
    if (val == null) {
      return null;
    } else {
      return val.toString();
    }
  }

  /** Given an indexable uniqueKey value, return the readable/printable version */
  public String printableUniqueKey(BytesRef idBytes) {
    return uniqueKeyFieldType.indexedToReadable(idBytes.utf8ToString());
  }

  /** Given a readable/printable uniqueKey value, return an indexable version */
  public BytesRef indexableUniqueKey(String idStr) {
    return new BytesRef(uniqueKeyFieldType.toInternal(idStr));
  }

  private SchemaField getIndexedField(String fname) {
    SchemaField f = getFields().get(fname);
    if (f == null) {
      throw new RuntimeException("unknown field '" + fname + "'");
    }
    if (!f.indexed()) {
      throw new RuntimeException("'" + fname + "' is not an indexed field:" + f);
    }
    return f;
  }

  /**
   * This will re-create the Analyzers. If you make any modifications to the Field map ({@link
   * IndexSchema#getFields()}, this function is required to synch the internally cached field
   * analyzers.
   *
   * @since solr 1.3
   */
  public void refreshAnalyzers() {
    indexAnalyzer = new SolrIndexAnalyzer();
    queryAnalyzer = new SolrQueryAnalyzer();
  }

  /**
   * @see UninvertingReader
   */
  public Function<String, UninvertingReader.Type> getUninversionMapper() {
    return name -> {
      SchemaField sf = getFieldOrNull(name);
      if (sf == null) {
        return null;
      }

      if (sf.isUninvertible()) {
        return sf.getType().getUninversionType(sf);
      }
      // else...

      // It would be nice to throw a helpful error here, with a good useful message for the user,
      // but unfortunately, inspite of the UninvertingReader class jdoc claims that the uninversion
      // process is lazy, that doesn't mean it's lazy as of "When a caller attempts ot use doc
      // values"
      //
      // The *mapping* function is consulted on LeafReader init/wrap for every FieldInfos found w/o
      // docValues.
      //
      // So if we throw an error here instead of returning null, the act of just opening a
      // newSearcher will trigger that error for any field, even if no one ever attempts to uninvert
      // it
      return null;
    };
  }

  /** Writes the schema in schema.xml format to the given writer */
  void persist(Writer writer) throws IOException {
    final SolrQueryResponse response = new SolrQueryResponse();
    response.add(IndexSchema.SCHEMA, getNamedPropertyValues());
    final SolrParams args = (new ModifiableSolrParams()).set("indent", "on");
    final LocalSolrQueryRequest req = new LocalSolrQueryRequest(null, args);
    final SchemaXmlWriter schemaXmlWriter = new SchemaXmlWriter(writer, req, response);
    schemaXmlWriter.setEmitManagedSchemaDoNotEditWarning(true);
    schemaXmlWriter.writeResponse();
    schemaXmlWriter.close();
  }

  public boolean isMutable() {
    return false;
  }

  private class SolrIndexAnalyzer extends DelegatingAnalyzerWrapper {
    protected final HashMap<String, Analyzer> analyzers;

    SolrIndexAnalyzer() {
      super(PER_FIELD_REUSE_STRATEGY);
      analyzers = analyzerCache();
    }

    protected HashMap<String, Analyzer> analyzerCache() {
      HashMap<String, Analyzer> cache = new HashMap<>();
      for (SchemaField f : getFields().values()) {
        Analyzer analyzer = f.getType().getIndexAnalyzer();
        cache.put(f.getName(), analyzer);
      }
      return cache;
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
      Analyzer analyzer = analyzers.get(fieldName);
      return analyzer != null ? analyzer : getDynamicFieldType(fieldName).getIndexAnalyzer();
    }
  }

  private class SolrQueryAnalyzer extends SolrIndexAnalyzer {
    SolrQueryAnalyzer() {}

    @Override
    protected HashMap<String, Analyzer> analyzerCache() {
      HashMap<String, Analyzer> cache = new HashMap<>();
      for (SchemaField f : getFields().values()) {
        Analyzer analyzer = f.getType().getQueryAnalyzer();
        cache.put(f.getName(), analyzer);
      }
      return cache;
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
      Analyzer analyzer = analyzers.get(fieldName);
      return analyzer != null ? analyzer : getDynamicFieldType(fieldName).getQueryAnalyzer();
    }
  }

  protected void readSchema(ConfigSetService.ConfigResource is) {
    assert null != is : "schema InputSource should never be null";
    try {
      rootNode = is.get();
      name = rootNode.attributes().get("name");
      StringBuilder sb = new StringBuilder();
      // Another case where the initialization from the test harness is different than the "real
      // world"
      if (name == null) {
        sb.append("schema has no name!");
        log.warn("{}", sb);
      } else {
        sb.append("Schema ");
        sb.append(NAME);
        sb.append("=");
        sb.append(name);
        log.info("{}", sb);
      }

      version = Float.parseFloat(rootNode.attributes().getOrDefault("version", "1.0f"));

      // load the Field Types
      final FieldTypePluginLoader typeLoader =
          new FieldTypePluginLoader(this, fieldTypes, schemaAware);

      List<ConfigNode> fTypes = rootNode.getAll(null, FIELDTYPE_KEYS);
      ConfigNode types = rootNode.child(TYPES);
      if (types != null) fTypes.addAll(types.getAll(null, FIELDTYPE_KEYS));
      typeLoader.load(solrClassLoader, fTypes);

      // load the fields
      Map<String, Boolean> explicitRequiredProp = loadFields(rootNode);

      similarityFactory = readSimilarity(solrClassLoader, rootNode.child(SIMILARITY));
      if (similarityFactory == null) {
        final Class<?> simClass = SchemaSimilarityFactory.class;
        // use the loader to ensure proper SolrCoreAware handling
        similarityFactory =
            solrClassLoader.newInstance(simClass.getName(), SimilarityFactory.class);
        similarityFactory.init(new ModifiableSolrParams());
      } else {
        isExplicitSimilarity = true;
      }
      if (!(similarityFactory instanceof SolrCoreAware)) {
        // if the sim factory isn't SolrCoreAware (and hence schema aware),
        // then we are responsible for erroring if a field type is trying to specify a sim.
        for (FieldType ft : fieldTypes.values()) {
          if (null != ft.getSimilarity()) {
            String msg =
                "FieldType '"
                    + ft.getTypeName()
                    + "' is configured with a similarity, but the global similarity does not support it: "
                    + similarityFactory.getClass();
            log.error(msg);
            throw new SolrException(ErrorCode.SERVER_ERROR, msg);
          }
        }
      }

      ConfigNode node = rootNode.child("defaultSearchField");
      if (node != null) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR,
            "Setting defaultSearchField in schema not supported since Solr 7");
      }

      node =
          rootNode.child(it -> it.attributes().get("defaultOperator") != null, "solrQueryParser");
      if (node != null) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR,
            "Setting default operator in schema (solrQueryParser/@defaultOperator) not supported");
      }

      node = rootNode.child(UNIQUE_KEY);

      if (node == null) {
        log.warn("no {} specified in schema.", UNIQUE_KEY);
      } else {
        uniqueKeyField = getIndexedField(node.txt().trim());
        uniqueKeyFieldName = uniqueKeyField.getName();
        uniqueKeyFieldType = uniqueKeyField.getType();

        // we fail on init if the ROOT field is *explicitly* defined as incompatible with uniqueKey
        // we don't want ot fail if there happens to be a dynamicField matching ROOT, (ie: "*")
        // because the user may not care about child docs at all.  The run time code
        // related to child docs can catch that if it happens
        if (fields.containsKey(ROOT_FIELD_NAME) && !isUsableForChildDocs()) {
          String msg =
              ROOT_FIELD_NAME
                  + " field must be defined using the exact same fieldType as the "
                  + UNIQUE_KEY
                  + " field ("
                  + uniqueKeyFieldName
                  + ") uses: "
                  + uniqueKeyFieldType.getTypeName();
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }

        if (null != uniqueKeyField.getDefaultValue()) {
          String msg =
              UNIQUE_KEY
                  + " field ("
                  + uniqueKeyFieldName
                  + ") can not be configured with a default value ("
                  + uniqueKeyField.getDefaultValue()
                  + ")";
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }

        if (!uniqueKeyField.stored()) {
          log.warn(
              "{} is not stored - distributed search and MoreLikeThis will not work", UNIQUE_KEY);
        }
        if (uniqueKeyField.multiValued()) {
          String msg =
              UNIQUE_KEY
                  + " field ("
                  + uniqueKeyFieldName
                  + ") can not be configured to be multivalued";
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }

        if (uniqueKeyField.getType().isPointField()) {
          String msg =
              UNIQUE_KEY
                  + " field ("
                  + uniqueKeyFieldName
                  + ") can not be configured to use a Points based FieldType: "
                  + uniqueKeyField.getType().getTypeName();
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }

        // Unless the uniqueKeyField is marked 'required=false' then make sure it exists
        if (!Boolean.FALSE.equals(explicitRequiredProp.get(uniqueKeyFieldName))) {
          uniqueKeyField.required = true;
          requiredFields.add(uniqueKeyField);
        }
      }

      /////////////// parse out copyField commands ///////////////

      dynamicCopyFields = new DynamicCopy[] {};
      loadCopyFields(rootNode);

      postReadInform();
    } catch (SolrException e) {
      throw new SolrException(
          ErrorCode.getErrorCode(e.code()),
          "Can't load schema " + loader.resourceLocation(resourceName) + ": " + e.getMessage(),
          e);
    } catch (Exception e) {
      // unexpected exception...
      throw new SolrException(
          ErrorCode.SERVER_ERROR,
          "Can't load schema " + loader.resourceLocation(resourceName) + ": " + e.getMessage(),
          e);
    }

    // create the field analyzers
    refreshAnalyzers();

    log.info("Loaded schema {}/{} with uniqueid field {}", name, version, uniqueKeyFieldName);
  }

  protected void postReadInform() {
    // Run the callbacks on SchemaAware now that everything else is done
    for (SchemaAware aware : schemaAware) {
      aware.inform(this);
    }
    // Make sure all analyzers have resource loaders, even SPI loaded ones
    fieldTypes.values().forEach(this::informResourceLoaderAwareObjectsForFieldType);
  }

  /**
   * Loads fields and dynamic fields.
   *
   * @return a map from field name to explicit required value
   */
  protected synchronized Map<String, Boolean> loadFields(ConfigNode n) {
    // Hang on to the fields that say if they are required -- this lets us set a reasonable default
    // for the unique key
    Map<String, Boolean> explicitRequiredProp = new HashMap<>();

    ArrayList<DynamicField> dFields = new ArrayList<>();

    List<ConfigNode> nodes = n.getAll(null, FIELD_KEYS);
    ConfigNode child = n.child(FIELDS);
    if (child != null) {
      nodes = new ArrayList<>(nodes);
      nodes.addAll(child.getAll(null, FIELD_KEYS));
    }

    for (ConfigNode node : nodes) {
      String name = DOMUtil.getAttr(node, NAME, "field definition");
      log.trace("reading field def {}", name);
      String type = DOMUtil.getAttr(node, TYPE, "field " + name);

      FieldType ft = fieldTypes.get(type);
      if (ft == null) {
        throw new SolrException(
            ErrorCode.BAD_REQUEST,
            "Unknown " + FIELD_TYPE + " '" + type + "' specified on field " + name);
      }

      Map<String, String> args = DOMUtil.toMapExcept(node, NAME, TYPE);
      if (null != args.get(REQUIRED)) {
        explicitRequiredProp.put(name, Boolean.valueOf(args.get(REQUIRED)));
      }

      SchemaField f = SchemaField.create(name, ft, args);

      if (node.name().equals(FIELD)) {
        SchemaField old = fields.put(f.getName(), f);
        if (old != null) {
          String msg =
              "[schema.xml] Duplicate field definition for '"
                  + f.getName()
                  + "' [[["
                  + old.toString()
                  + "]]] and [[["
                  + f.toString()
                  + "]]]";
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }
        log.debug("field defined: {}", f);
        if (f.getDefaultValue() != null) {
          if (log.isDebugEnabled()) {
            log.debug("{} contains default value {}", name, f.getDefaultValue());
          }
          fieldsWithDefaultValue.add(f);
        }
        if (f.isRequired()) {
          log.debug("{} is required in this schema", name);
          requiredFields.add(f);
        }
      } else if (node.name().equals(DYNAMIC_FIELD)) {
        if (isValidDynamicField(dFields, f)) {
          addDynamicFieldNoDupCheck(dFields, f);
        }
      } else {
        // we should never get here
        throw new RuntimeException("Unknown field type");
      }
    }

    // fields with default values are by definition required
    // add them to required fields, and we only have to loop once
    // in DocumentBuilder.getDoc()
    requiredFields.addAll(fieldsWithDefaultValue);

    dynamicFields = dynamicFieldListToSortedArray(dFields);

    return explicitRequiredProp;
  }

  /** Sort the dynamic fields and stuff them in a normal array for faster access. */
  protected static DynamicField[] dynamicFieldListToSortedArray(
      List<DynamicField> dynamicFieldList) {
    // Avoid creating the array twice by converting to an array first and using Arrays.sort(),
    // rather than Collections.sort() then converting to an array, since Collections.sort()
    // copies to an array first, then sets each collection member from the array.
    DynamicField[] dFields = dynamicFieldList.toArray(new DynamicField[0]);
    Arrays.sort(dFields);

    if (log.isTraceEnabled()) {
      log.trace("Dynamic Field Ordering: {}", Arrays.toString(dFields));
    }

    return dFields;
  }

  /** Loads the copy fields */
  protected synchronized void loadCopyFields(ConfigNode n) {
    List<ConfigNode> nodes = n.getAll(COPY_FIELD);
    ConfigNode f = n.child(FIELDS);
    if (f != null) {
      nodes = new ArrayList<>(nodes);
      nodes.addAll(f.getAll(COPY_FIELD));
    }
    for (ConfigNode node : nodes) {

      String source = DOMUtil.getAttr(node, SOURCE, COPY_FIELD + " definition");
      String dest = DOMUtil.getAttr(node, DESTINATION, COPY_FIELD + " definition");
      String maxChars = DOMUtil.getAttr(node, MAX_CHARS, null);

      int maxCharsInt = CopyField.UNLIMITED;
      if (maxChars != null) {
        try {
          maxCharsInt = Integer.parseInt(maxChars);
        } catch (NumberFormatException e) {
          log.warn(
              "Couldn't parse {} attribute for '{}' from '{}' to '{}' as integer. The whole field will be copied.",
              MAX_CHARS,
              COPY_FIELD,
              source,
              dest);
        }
      }

      if (dest.equals(uniqueKeyFieldName)) {
        String msg =
            UNIQUE_KEY
                + " field ("
                + uniqueKeyFieldName
                + ") can not be the "
                + DESTINATION
                + " of a "
                + COPY_FIELD
                + "("
                + SOURCE
                + "="
                + source
                + ")";
        log.error(msg);
        throw new SolrException(ErrorCode.SERVER_ERROR, msg);
      }

      registerCopyField(source, dest, maxCharsInt);
    }

    for (Map.Entry<SchemaField, Integer> entry : copyFieldTargetCounts.entrySet()) {
      if (entry.getValue() > 1 && !entry.getKey().multiValued()) {
        log.warn(
            "Field {} is not multivalued and destination for multiple {} ({})",
            entry.getKey().name,
            COPY_FIELDS,
            entry.getValue());
      }
    }
  }

  /**
   * Returns true if the given name has exactly one asterisk either at the start or end of the name
   */
  protected static boolean isValidFieldGlob(String name) {
    if (name.startsWith("*") || name.endsWith("*")) {
      int count = 0;
      for (int pos = 0; pos < name.length() && -1 != (pos = name.indexOf('*', pos)); ++pos) ++count;
      if (1 == count) return true;
    }
    return false;
  }

  protected boolean isValidDynamicField(List<DynamicField> dFields, SchemaField f) {
    String glob = f.getName();
    if (f.getDefaultValue() != null) {
      throw new SolrException(
          ErrorCode.SERVER_ERROR, DYNAMIC_FIELD + " can not have a default value: " + glob);
    }
    if (f.isRequired()) {
      throw new SolrException(
          ErrorCode.SERVER_ERROR, DYNAMIC_FIELD + " can not be required: " + glob);
    }
    if (!isValidFieldGlob(glob)) {
      String msg =
          "Dynamic field name '"
              + glob
              + "' should have either a leading or a trailing asterisk, and no others.";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    if (isDuplicateDynField(dFields, f)) {
      String msg = "[schema.xml] Duplicate DynamicField definition for '" + glob + "'";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    return true;
  }

  /**
   * Register one or more new Dynamic Fields with the Schema.
   *
   * @param fields The sequence of {@link org.apache.solr.schema.SchemaField}
   */
  public void registerDynamicFields(SchemaField... fields) {
    List<DynamicField> dynFields = new ArrayList<>(asList(dynamicFields));
    for (SchemaField field : fields) {
      if (isDuplicateDynField(dynFields, field)) {
        if (log.isDebugEnabled()) {
          log.debug("dynamic field already exists: dynamic field: [{}]", field.getName());
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("dynamic field creation for schema field: {}", field.getName());
        }
        addDynamicFieldNoDupCheck(dynFields, field);
      }
    }
    dynamicFields = dynamicFieldListToSortedArray(dynFields);
  }

  private void addDynamicFieldNoDupCheck(List<DynamicField> dFields, SchemaField f) {
    dFields.add(new DynamicField(f));
    log.debug("dynamic field defined: {}", f);
  }

  protected boolean isDuplicateDynField(List<DynamicField> dFields, SchemaField f) {
    for (DynamicField df : dFields) {
      if (df.getRegex().equals(f.name)) return true;
    }
    return false;
  }

  public void registerCopyField(String source, String dest) {
    registerCopyField(source, dest, CopyField.UNLIMITED);
  }

  /**
   * NOTE: this function is not thread safe. However, it is safe to use within the standard <code>
   * inform( SolrCore core )</code> function for <code>SolrCoreAware</code> classes. Outside <code>
   * inform</code>, this could potentially throw a ConcurrentModificationException
   *
   * @see SolrCoreAware
   */
  public void registerCopyField(String source, String dest, int maxChars) {
    log.debug(
        "{} {}='{}' {}='{}' {}='{}'",
        COPY_FIELD,
        SOURCE,
        source,
        DESTINATION,
        dest,
        MAX_CHARS,
        maxChars);

    DynamicField destDynamicField = null;
    SchemaField destSchemaField = fields.get(dest);
    SchemaField sourceSchemaField = fields.get(source);

    DynamicField sourceDynamicBase = null;
    DynamicField destDynamicBase = null;

    boolean sourceIsDynamicFieldReference = false;
    boolean sourceIsExplicitFieldGlob = false;

    final String invalidGlobMessage =
        "is an invalid glob: either it contains more than one asterisk,"
            + " or the asterisk occurs neither at the start nor at the end.";
    final boolean sourceIsGlob = isValidFieldGlob(source);
    if (source.contains("*") && !sourceIsGlob) {
      String msg = "copyField source :'" + source + "' " + invalidGlobMessage;
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    if (dest.contains("*") && !isValidFieldGlob(dest)) {
      String msg = "copyField dest :'" + dest + "' " + invalidGlobMessage;
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }

    if (null == sourceSchemaField && sourceIsGlob) {
      Pattern pattern = Pattern.compile(source.replace("*", ".*")); // glob->regex
      for (String field : fields.keySet()) {
        if (pattern.matcher(field).matches()) {
          sourceIsExplicitFieldGlob = true;
          break;
        }
      }
    }

    if (null == destSchemaField || (null == sourceSchemaField && !sourceIsExplicitFieldGlob)) {
      // Go through dynamicFields array only once, collecting info for both source and dest fields,
      // if needed
      for (DynamicField dynamicField : dynamicFields) {
        if (null == sourceSchemaField
            && !sourceIsDynamicFieldReference
            && !sourceIsExplicitFieldGlob) {
          if (dynamicField.matches(source)) {
            sourceIsDynamicFieldReference = true;
            if (!source.equals(dynamicField.getRegex())) {
              sourceDynamicBase = dynamicField;
            }
          }
        }
        if (null == destSchemaField) {
          if (dest.equals(dynamicField.getRegex())) {
            destDynamicField = dynamicField;
            destSchemaField = dynamicField.prototype;
          } else if (dynamicField.matches(dest)) {
            destSchemaField = dynamicField.makeSchemaField(dest);
            destDynamicField = new DynamicField(destSchemaField);
            destDynamicBase = dynamicField;
          }
        }
        if (null != destSchemaField
            && (null != sourceSchemaField
                || sourceIsDynamicFieldReference
                || sourceIsExplicitFieldGlob)) {
          break;
        }
      }
    }
    if (null == sourceSchemaField && !sourceIsGlob && !sourceIsDynamicFieldReference) {
      String msg =
          "copyField source :'"
              + source
              + "' is not a glob and doesn't match any explicit field or dynamicField.";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    if (null == destSchemaField) {
      String msg =
          "copyField dest :'"
              + dest
              + "' is not an explicit field and doesn't match a dynamicField.";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    if (sourceIsGlob) {
      if (null != destDynamicField) { // source: glob ; dest: dynamic field ref
        registerDynamicCopyField(
            new DynamicCopy(
                source, destDynamicField, maxChars, sourceDynamicBase, destDynamicBase));
        incrementCopyFieldTargetCount(destSchemaField);
      } else { // source: glob ; dest: explicit field
        destDynamicField = new DynamicField(destSchemaField);
        registerDynamicCopyField(
            new DynamicCopy(source, destDynamicField, maxChars, sourceDynamicBase, null));
        incrementCopyFieldTargetCount(destSchemaField);
      }
    } else if (sourceIsDynamicFieldReference) {
      if (null
          != destDynamicField) { // source: no-asterisk dynamic field ref ; dest: dynamic field ref
        registerDynamicCopyField(
            new DynamicCopy(
                source, destDynamicField, maxChars, sourceDynamicBase, destDynamicBase));
        incrementCopyFieldTargetCount(destSchemaField);
      } else { // source: no-asterisk dynamic field ref ; dest: explicit field
        sourceSchemaField = getField(source);
        registerExplicitSrcAndDestFields(source, maxChars, destSchemaField, sourceSchemaField);
      }
    } else {
      if (null != destDynamicField) { // source: explicit field ; dest: dynamic field reference
        if (destDynamicField.pattern instanceof DynamicReplacement.DynamicPattern.NameEquals) {
          // Dynamic dest with no asterisk is acceptable
          registerDynamicCopyField(
              new DynamicCopy(
                  source, destDynamicField, maxChars, sourceDynamicBase, destDynamicBase));
          incrementCopyFieldTargetCount(destSchemaField);
        } else { // source: explicit field ; dest: dynamic field with an asterisk
          String msg =
              "copyField only supports a dynamic destination with an asterisk "
                  + "if the source also has an asterisk";
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }
      } else { // source & dest: explicit fields
        registerExplicitSrcAndDestFields(source, maxChars, destSchemaField, sourceSchemaField);
      }
    }
  }

  protected void registerExplicitSrcAndDestFields(
      String source, int maxChars, SchemaField destSchemaField, SchemaField sourceSchemaField) {
    List<CopyField> copyFieldList = copyFieldsMap.get(source);
    if (copyFieldList == null) {
      copyFieldList = new ArrayList<>();
      copyFieldsMap.put(source, copyFieldList);
    }
    copyFieldList.add(new CopyField(sourceSchemaField, destSchemaField, maxChars));
    incrementCopyFieldTargetCount(destSchemaField);
  }

  private void incrementCopyFieldTargetCount(SchemaField dest) {
    copyFieldTargetCounts.put(
        dest, copyFieldTargetCounts.containsKey(dest) ? copyFieldTargetCounts.get(dest) + 1 : 1);
  }

  private void registerDynamicCopyField(DynamicCopy dcopy) {
    DynamicCopy[] temp = new DynamicCopy[dynamicCopyFields.length + 1];
    System.arraycopy(dynamicCopyFields, 0, temp, 0, dynamicCopyFields.length);
    temp[temp.length - 1] = dcopy;
    dynamicCopyFields = temp;
  }

  static SimilarityFactory readSimilarity(SolrClassLoader loader, ConfigNode node) {
    if (node == null) {
      return null;
    } else {
      SimilarityFactory similarityFactory;
      final String classArg = node.attributes().get(SimilarityFactory.CLASS_NAME);
      final Object obj = loader.newInstance(classArg, Object.class, "search.similarities.");
      if (obj instanceof SimilarityFactory) {
        // configure a factory, get a similarity back
        final NamedList<Object> namedList = DOMUtil.childNodesToNamedList(node);
        namedList.add(SimilarityFactory.CLASS_NAME, classArg);
        SolrParams params = namedList.toSolrParams();
        similarityFactory = (SimilarityFactory) obj;
        similarityFactory.init(params);
      } else {
        // just like always, assume it's a Similarity and get a ClassCastException - reasonable
        // error handling
        similarityFactory =
            new SimilarityFactory() {
              @Override
              public Similarity getSimilarity() {
                return (Similarity) obj;
              }
            };
      }
      return similarityFactory;
    }
  }

  public abstract static class DynamicReplacement implements Comparable<DynamicReplacement> {
    protected abstract static class DynamicPattern {
      protected final String regex;
      protected final String fixedStr;

      protected DynamicPattern(String regex, String fixedStr) {
        this.regex = regex;
        this.fixedStr = fixedStr;
      }

      static DynamicPattern createPattern(String regex) {
        if (regex.startsWith("*")) {
          return new NameEndsWith(regex);
        } else if (regex.endsWith("*")) {
          return new NameStartsWith(regex);
        } else {
          return new NameEquals(regex);
        }
      }

      /** Returns true if the given name matches this pattern */
      abstract boolean matches(String name);

      /**
       * Returns the remainder of the given name after removing this pattern's fixed string
       * component
       */
      abstract String remainder(String name);

      /**
       * Returns the result of combining this pattern's fixed string component with the given
       * replacement
       */
      abstract String subst(String replacement);

      /** Returns the length of the original regex, including the asterisk, if any. */
      public int length() {
        return regex.length();
      }

      private static class NameStartsWith extends DynamicPattern {
        NameStartsWith(String regex) {
          super(regex, regex.substring(0, regex.length() - 1));
        }

        @Override
        boolean matches(String name) {
          return name.startsWith(fixedStr);
        }

        @Override
        String remainder(String name) {
          return name.substring(fixedStr.length());
        }

        @Override
        String subst(String replacement) {
          return fixedStr + replacement;
        }
      }

      private static class NameEndsWith extends DynamicPattern {
        NameEndsWith(String regex) {
          super(regex, regex.substring(1));
        }

        @Override
        boolean matches(String name) {
          return name.endsWith(fixedStr);
        }

        @Override
        String remainder(String name) {
          return name.substring(0, name.length() - fixedStr.length());
        }

        @Override
        String subst(String replacement) {
          return replacement + fixedStr;
        }
      }

      private static class NameEquals extends DynamicPattern {
        NameEquals(String regex) {
          super(regex, regex);
        }

        @Override
        boolean matches(String name) {
          return regex.equals(name);
        }

        @Override
        String remainder(String name) {
          return "";
        }

        @Override
        String subst(String replacement) {
          return fixedStr;
        }
      }
    }

    protected DynamicPattern pattern;

    public boolean matches(String name) {
      return pattern.matches(name);
    }

    protected DynamicReplacement(String regex) {
      pattern = DynamicPattern.createPattern(regex);
    }

    /**
     * Sort order is based on length of regex. Longest comes first.
     *
     * @param other The object to compare to.
     * @return a negative integer, zero, or a positive integer as this object is less than, equal
     *     to, or greater than the specified object.
     */
    @Override
    public int compareTo(DynamicReplacement other) {
      return other.pattern.length() - pattern.length();
    }

    /** Returns the regex used to create this instance's pattern */
    public String getRegex() {
      return pattern.regex;
    }
  }

  public static final class DynamicField extends DynamicReplacement {
    private final SchemaField prototype;

    public SchemaField getPrototype() {
      return prototype;
    }

    DynamicField(SchemaField prototype) {
      super(prototype.name);
      this.prototype = prototype;
    }

    SchemaField makeSchemaField(String name) {
      // could have a cache instead of returning a new one each time, but it might
      // not be worth it.
      // Actually, a higher level cache could be worth it to avoid too many
      // .startsWith() and .endsWith() comparisons.  it depends on how many
      // dynamic fields there are.
      return new SchemaField(prototype, name);
    }

    @Override
    public String toString() {
      return prototype.toString();
    }
  }

  public static class DynamicCopy extends DynamicReplacement {
    private final DynamicField destination;

    private final int maxChars;

    public int getMaxChars() {
      return maxChars;
    }

    final DynamicField sourceDynamicBase;

    public DynamicField getSourceDynamicBase() {
      return sourceDynamicBase;
    }

    final DynamicField destDynamicBase;

    public DynamicField getDestDynamicBase() {
      return destDynamicBase;
    }

    DynamicCopy(
        String sourceRegex,
        DynamicField destination,
        int maxChars,
        DynamicField sourceDynamicBase,
        DynamicField destDynamicBase) {
      super(sourceRegex);
      this.destination = destination;
      this.maxChars = maxChars;
      this.sourceDynamicBase = sourceDynamicBase;
      this.destDynamicBase = destDynamicBase;
    }

    public DynamicField getDestination() {
      return destination;
    }

    public String getDestFieldName() {
      return destination.getRegex();
    }

    /**
     * Generates a destination field name based on this source pattern, by substituting the
     * remainder of this source pattern into the the given destination pattern.
     */
    public SchemaField getTargetField(String sourceField) {
      String remainder = pattern.remainder(sourceField);
      String targetFieldName = destination.pattern.subst(remainder);
      return destination.makeSchemaField(targetFieldName);
    }

    @Override
    public String toString() {
      return destination.prototype.toString();
    }
  }

  public SchemaField[] getDynamicFieldPrototypes() {
    SchemaField[] df = new SchemaField[dynamicFields.length];
    for (int i = 0; i < dynamicFields.length; i++) {
      df[i] = dynamicFields[i].prototype;
    }
    return df;
  }

  public String getDynamicPattern(String fieldName) {
    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return df.getRegex();
    }
    return null;
  }

  /**
   * Does the schema explicitly define the specified field, i.e. not as a result of a copyField
   * declaration? We consider it explicitly defined if it matches a field name or a dynamicField
   * name.
   *
   * @return true if explicitly declared in the schema.
   */
  public boolean hasExplicitField(String fieldName) {
    if (fields.containsKey(fieldName)) {
      return true;
    }

    for (DynamicField df : dynamicFields) {
      if (fieldName.equals(df.getRegex())) return true;
    }

    return false;
  }

  /**
   * Is the specified field dynamic or not.
   *
   * @return true if the specified field is dynamic
   */
  public boolean isDynamicField(String fieldName) {
    if (fields.containsKey(fieldName)) {
      return false;
    }

    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return true;
    }

    return false;
  }

  /**
   * Returns the SchemaField that should be used for the specified field name, or null if none
   * exists.
   *
   * @param fieldName may be an explicitly defined field or a name that matches a dynamic field.
   * @see #getFieldType
   * @see #getField(String)
   * @return The {@link org.apache.solr.schema.SchemaField}
   */
  public SchemaField getFieldOrNull(String fieldName) {
    SchemaField f = fields.get(fieldName);
    if (f != null) return f;
    f = dynamicFieldCache.get(fieldName);
    if (f != null) return f;

    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) {
        dynamicFieldCache.put(fieldName, f = df.makeSchemaField(fieldName));
        break;
      }
    }

    return f;
  }

  /**
   * Returns the SchemaField that should be used for the specified field name
   *
   * @param fieldName may be an explicitly defined field or a name that matches a dynamic field.
   * @throws SolrException if no such field exists
   * @see #getFieldType
   * @see #getFieldOrNull(String)
   * @return The {@link SchemaField}
   */
  public SchemaField getField(String fieldName) {
    SchemaField f = getFieldOrNull(fieldName);
    if (f != null) return f;

    // Hmmm, default field could also be implemented with a dynamic field of "*".
    // It would have to be special-cased and only used if nothing else matched.
    /*  REMOVED -YCS
    if (defaultFieldType != null) return new SchemaField(fieldName,defaultFieldType);
    */
    throw new SolrException(ErrorCode.BAD_REQUEST, "undefined field: \"" + fieldName + "\"");
  }

  /**
   * Returns the FieldType for the specified field name.
   *
   * <p>This method exists because it can be more efficient then {@link #getField} for dynamic
   * fields if a full SchemaField isn't needed.
   *
   * @param fieldName may be an explicitly created field, or a name that excercises a dynamic field.
   * @throws SolrException if no such field exists
   * @see #getField(String)
   * @see #getFieldTypeNoEx
   */
  public FieldType getFieldType(String fieldName) {
    SchemaField f = fields.get(fieldName);
    if (f != null) return f.getType();

    return getDynamicFieldType(fieldName);
  }

  /**
   * Given the name of a {@link org.apache.solr.schema.FieldType} (not to be confused with {@link
   * #getFieldType(String)} which takes in the name of a field), return the {@link
   * org.apache.solr.schema.FieldType}.
   *
   * @param fieldTypeName The name of the {@link org.apache.solr.schema.FieldType}
   * @return The {@link org.apache.solr.schema.FieldType} or null.
   */
  public FieldType getFieldTypeByName(String fieldTypeName) {
    return fieldTypes.get(fieldTypeName);
  }

  /**
   * Returns the FieldType for the specified field name.
   *
   * <p>This method exists because it can be more efficient then {@link #getField} for dynamic
   * fields if a full SchemaField isn't needed.
   *
   * @param fieldName may be an explicitly created field, or a name that exercises a dynamic field.
   * @return null if field is not defined.
   * @see #getField(String)
   * @see #getFieldTypeNoEx
   */
  public FieldType getFieldTypeNoEx(String fieldName) {
    SchemaField f = fields.get(fieldName);
    if (f != null) return f.getType();
    return dynFieldType(fieldName);
  }

  /**
   * Returns the FieldType of the best matching dynamic field for the specified field name
   *
   * @param fieldName may be an explicitly created field, or a name that exercises a dynamic field.
   * @throws SolrException if no such field exists
   * @see #getField(String)
   * @see #getFieldTypeNoEx
   */
  public FieldType getDynamicFieldType(String fieldName) {
    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return df.prototype.getType();
    }
    throw new SolrException(ErrorCode.BAD_REQUEST, "undefined field " + fieldName);
  }

  private FieldType dynFieldType(String fieldName) {
    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return df.prototype.getType();
    }
    return null;
  }

  /**
   * Get all copy fields, both the static and the dynamic ones.
   *
   * @return Array of fields copied into this field
   */
  public List<String> getCopySources(String destField) {
    SchemaField f = getField(destField);
    if (!isCopyFieldTarget(f)) {
      return Collections.emptyList();
    }
    List<String> fieldNames = new ArrayList<>();
    for (Map.Entry<String, List<CopyField>> cfs : copyFieldsMap.entrySet()) {
      for (CopyField copyField : cfs.getValue()) {
        if (copyField.getDestination().getName().equals(destField)) {
          fieldNames.add(copyField.getSource().getName());
        }
      }
    }
    for (DynamicCopy dynamicCopy : dynamicCopyFields) {
      if (dynamicCopy.getDestFieldName().equals(destField)) {
        fieldNames.add(dynamicCopy.getRegex());
      }
    }
    return fieldNames;
  }

  /**
   * Get all copy fields for a specified source field, both static and dynamic ones.
   *
   * @return List of CopyFields to copy to.
   * @since solr 1.4
   */
  // This is useful when we need the maxSize param of each CopyField
  public List<CopyField> getCopyFieldsList(final String sourceField) {
    final List<CopyField> result = new ArrayList<>();
    for (DynamicCopy dynamicCopy : dynamicCopyFields) {
      if (dynamicCopy.matches(sourceField)) {
        result.add(
            new CopyField(
                getField(sourceField),
                dynamicCopy.getTargetField(sourceField),
                dynamicCopy.maxChars));
      }
    }
    List<CopyField> fixedCopyFields = copyFieldsMap.get(sourceField);
    if (null != fixedCopyFields) {
      result.addAll(fixedCopyFields);
    }

    return result;
  }

  /**
   * Check if a field is used as the destination of a copyField operation
   *
   * @since solr 1.3
   */
  public boolean isCopyFieldTarget(SchemaField f) {
    return copyFieldTargetCounts.containsKey(f);
  }

  /** Get a map of property name -&gt; value for the whole schema. */
  public Map<String, Object> getNamedPropertyValues() {
    return getNamedPropertyValues(null, new MapSolrParams(Collections.emptyMap()));
  }

  public static class SchemaProps implements MapSerializable {
    private static final String SOURCE_FIELD_LIST = IndexSchema.SOURCE + "." + CommonParams.FL;
    private static final String DESTINATION_FIELD_LIST =
        IndexSchema.DESTINATION + "." + CommonParams.FL;
    public final String name;
    private final SolrParams params;
    private final IndexSchema schema;
    boolean showDefaults, includeDynamic;
    Set<String> requestedFields;
    private Set<String> requestedSourceFields;
    private Set<String> requestedDestinationFields;

    @SuppressWarnings("ImmutableEnumChecker")
    public enum Handler {
      NAME(IndexSchema.NAME, sp -> sp.schema.getSchemaName()),
      VERSION(IndexSchema.VERSION, sp -> sp.schema.getVersion()),
      UNIQUE_KEY(IndexSchema.UNIQUE_KEY, sp -> sp.schema.uniqueKeyFieldName),
      SIMILARITY(
          IndexSchema.SIMILARITY,
          sp ->
              sp.schema.isExplicitSimilarity
                  ? sp.schema.similarityFactory.getNamedPropertyValues()
                  : null),
      FIELD_TYPES(
          IndexSchema.FIELD_TYPES,
          sp ->
              new TreeMap<>(sp.schema.fieldTypes)
                  .values().stream()
                      .map(it -> it.getNamedPropertyValues(sp.showDefaults))
                      .collect(Collectors.toList())),

      FIELDS(
          IndexSchema.FIELDS,
          sp -> {
            List<SimpleOrderedMap<Object>> result =
                (sp.requestedFields != null
                        ? sp.requestedFields
                        : new TreeSet<>(sp.schema.fields.keySet()))
                    .stream()
                        .map(sp.schema::getFieldOrNull)
                        .filter(it -> it != null)
                        .filter(it -> sp.includeDynamic || !sp.schema.isDynamicField(it.name))
                        .map(sp::getProperties)
                        .collect(Collectors.toList());
            if (sp.includeDynamic && sp.requestedFields == null) {
              result.addAll(sp.applyDynamic());
            }
            return result;
          }),
      DYNAMIC_FIELDS(
          IndexSchema.DYNAMIC_FIELDS,
          sp ->
              Stream.of(sp.schema.dynamicFields)
                  .filter(it -> !it.getRegex().startsWith(INTERNAL_POLY_FIELD_PREFIX))
                  .filter(
                      it ->
                          sp.requestedFields == null
                              || sp.requestedFields.contains(it.getPrototype().getName()))
                  .map(it -> sp.getProperties(it.getPrototype()))
                  .collect(Collectors.toList())),

      COPY_FIELDS(
          IndexSchema.COPY_FIELDS,
          sp ->
              sp.schema.getCopyFieldProperties(
                  false, sp.requestedSourceFields, sp.requestedDestinationFields));

      final Function<SchemaProps, Object> fun;
      public final String realName, nameLower;

      Handler(String name, Function<SchemaProps, Object> fun) {
        this.fun = fun;
        this.realName = name;
        nameLower = name.toLowerCase(Locale.ROOT);
      }

      public String getRealName() {
        return realName;
      }

      public String getNameLower() {
        return nameLower;
      }
    }

    SchemaProps(String name, SolrParams params, IndexSchema schema) {
      this.name = name;
      this.params = params;
      this.schema = schema;
      showDefaults = params.getBool("showDefaults", false);
      includeDynamic = params.getBool("includeDynamic", false);
      requestedSourceFields = readMultiVals(SOURCE_FIELD_LIST);
      requestedDestinationFields = readMultiVals(DESTINATION_FIELD_LIST);
      requestedFields = readMultiVals(CommonParams.FL);
    }

    @SuppressWarnings("unchecked")
    public Collection<SimpleOrderedMap<Object>> applyDynamic() {
      return (List<SimpleOrderedMap<Object>>) Handler.DYNAMIC_FIELDS.fun.apply(this);
    }

    private Set<String> readMultiVals(String name) {
      String flParam = params.get(name);
      if (null != flParam) {
        String[] fields = flParam.trim().split("[,\\s]+");
        if (fields.length > 0)
          return new LinkedHashSet<>(
              Stream.of(fields).filter(it -> !it.trim().isEmpty()).collect(Collectors.toList()));
      }

      return null;
    }

    SimpleOrderedMap<Object> getProperties(SchemaField sf) {
      SimpleOrderedMap<Object> result = sf.getNamedPropertyValues(showDefaults);
      if (schema.isDynamicField(sf.name)) {
        String dynamicBase = schema.getDynamicPattern(sf.getName());
        // Add dynamicBase property if it's different from the field name.
        if (!sf.getName().equals(dynamicBase)) {
          result.add("dynamicBase", dynamicBase);
        }
      }
      return result;
    }

    @Override
    public Map<String, Object> toMap(Map<String, Object> map) {
      return Stream.of(Handler.values())
          .filter(it -> name == null || it.nameLower.equals(name))
          .map(it -> new Pair<>(it.realName, it.fun.apply(this)))
          .filter(it -> it.second() != null)
          .collect(Collectors.toMap(Pair::first, Pair::second, (v1, v2) -> v2, LinkedHashMap::new));
    }
  }

  public static Map<String, String> nameMapping =
      Stream.of(SchemaProps.Handler.values())
          .collect(
              Collectors.toUnmodifiableMap(
                  SchemaProps.Handler::getNameLower, SchemaProps.Handler::getRealName));

  public Map<String, Object> getNamedPropertyValues(String name, SolrParams params) {
    return new SchemaProps(name, params, this).toMap(new LinkedHashMap<>());
  }

  /**
   * Returns a list of copyField directives, with optional details and optionally restricting to
   * those directives that contain the requested source and/or destination field names.
   *
   * @param showDetails If true, source and destination dynamic bases, and explicit fields matched
   *     by source globs, will be added to dynamic copyField directives where appropriate
   * @param requestedSourceFields If not null, output is restricted to those copyField directives
   *     with the requested source field names
   * @param requestedDestinationFields If not null, output is restricted to those copyField
   *     directives with the requested destination field names
   * @return a list of copyField directives
   */
  public List<SimpleOrderedMap<Object>> getCopyFieldProperties(
      boolean showDetails,
      Set<String> requestedSourceFields,
      Set<String> requestedDestinationFields) {
    List<SimpleOrderedMap<Object>> copyFieldProperties = new ArrayList<>();
    SortedMap<String, List<CopyField>> sortedCopyFields = new TreeMap<>(copyFieldsMap);
    for (List<CopyField> copyFields : sortedCopyFields.values()) {
      copyFields = new ArrayList<>(copyFields);
      copyFields.sort(
          (cf1, cf2) -> {
            // sources are all the same, just sorting by destination here
            return cf1.getDestination().getName().compareTo(cf2.getDestination().getName());
          });
      for (CopyField copyField : copyFields) {
        final String source = copyField.getSource().getName();
        final String destination = copyField.getDestination().getName();
        if ((null == requestedSourceFields || requestedSourceFields.contains(source))
            && (null == requestedDestinationFields
                || requestedDestinationFields.contains(destination))) {
          SimpleOrderedMap<Object> props = new SimpleOrderedMap<>();
          props.add(SOURCE, source);
          props.add(DESTINATION, destination);
          if (0 != copyField.getMaxChars()) {
            props.add(MAX_CHARS, copyField.getMaxChars());
          }
          copyFieldProperties.add(props);
        }
      }
    }
    for (IndexSchema.DynamicCopy dynamicCopy : dynamicCopyFields) {
      final String source = dynamicCopy.getRegex();
      final String destination = dynamicCopy.getDestFieldName();
      if ((null == requestedSourceFields || requestedSourceFields.contains(source))
          && (null == requestedDestinationFields
              || requestedDestinationFields.contains(destination))) {
        SimpleOrderedMap<Object> dynamicCopyProps = new SimpleOrderedMap<>();

        dynamicCopyProps.add(SOURCE, dynamicCopy.getRegex());
        if (showDetails) {
          IndexSchema.DynamicField sourceDynamicBase = dynamicCopy.getSourceDynamicBase();
          if (null != sourceDynamicBase) {
            dynamicCopyProps.add(SOURCE_DYNAMIC_BASE, sourceDynamicBase.getRegex());
          } else if (source.contains("*")) {
            List<String> sourceExplicitFields = new ArrayList<>();
            Pattern pattern = Pattern.compile(source.replace("*", ".*")); // glob->regex
            for (String field : fields.keySet()) {
              if (pattern.matcher(field).matches()) {
                sourceExplicitFields.add(field);
              }
            }
            if (sourceExplicitFields.size() > 0) {
              Collections.sort(sourceExplicitFields);
              dynamicCopyProps.add(SOURCE_EXPLICIT_FIELDS, sourceExplicitFields);
            }
          }
        }

        dynamicCopyProps.add(DESTINATION, dynamicCopy.getDestFieldName());
        if (showDetails) {
          IndexSchema.DynamicField destDynamicBase = dynamicCopy.getDestDynamicBase();
          if (null != destDynamicBase) {
            dynamicCopyProps.add(DESTINATION_DYNAMIC_BASE, destDynamicBase.getRegex());
          }
        }

        if (0 != dynamicCopy.getMaxChars()) {
          dynamicCopyProps.add(MAX_CHARS, dynamicCopy.getMaxChars());
        }

        copyFieldProperties.add(dynamicCopyProps);
      }
    }
    return copyFieldProperties;
  }

  /**
   * Copies this schema, adds the given field to the copy Requires synchronizing on the object
   * returned by {@link #getSchemaUpdateLock()}.
   *
   * @param newField the SchemaField to add
   * @param persist to persist the schema or not
   * @return a new IndexSchema based on this schema with newField added
   * @see #newField(String, String, Map)
   */
  public IndexSchema addField(SchemaField newField, boolean persist) {
    return addFields(Collections.singletonList(newField), Collections.emptyMap(), persist);
  }

  public IndexSchema addField(SchemaField newField) {
    return addField(newField, true);
  }

  /**
   * Copies this schema, adds the given field to the copy Requires synchronizing on the object
   * returned by {@link #getSchemaUpdateLock()}.
   *
   * @param newField the SchemaField to add
   * @param copyFieldNames 0 or more names of targets to copy this field to. The targets must
   *     already exist.
   * @return a new IndexSchema based on this schema with newField added
   * @see #newField(String, String, Map)
   */
  public IndexSchema addField(SchemaField newField, Collection<String> copyFieldNames) {
    return addFields(
        singletonList(newField), singletonMap(newField.getName(), copyFieldNames), true);
  }

  /**
   * Copies this schema, adds the given fields to the copy. Requires synchronizing on the object
   * returned by {@link #getSchemaUpdateLock()}.
   *
   * @param newFields the SchemaFields to add
   * @return a new IndexSchema based on this schema with newFields added
   * @see #newField(String, String, Map)
   */
  public IndexSchema addFields(Collection<SchemaField> newFields) {
    return addFields(newFields, Collections.<String, Collection<String>>emptyMap(), true);
  }

  /**
   * Copies this schema, adds the given fields to the copy Requires synchronizing on the object
   * returned by {@link #getSchemaUpdateLock()}.
   *
   * @param newFields the SchemaFields to add
   * @param copyFieldNames 0 or more names of targets to copy this field to. The target fields must
   *     already exist.
   * @param persist Persist the schema or not
   * @return a new IndexSchema based on this schema with newFields added
   * @see #newField(String, String, Map)
   */
  public IndexSchema addFields(
      Collection<SchemaField> newFields,
      Map<String, Collection<String>> copyFieldNames,
      boolean persist) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, deletes the named fields from the copy.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param names the names of the fields to delete
   * @return a new IndexSchema based on this schema with the named fields deleted
   */
  public IndexSchema deleteFields(Collection<String> names) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, deletes the named field from the copy, creates a new field with the same
   * name using the given args, then rebinds any referring copy fields to the replacement field.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param fieldName The name of the field to be replaced
   * @param replacementFieldType The field type of the replacement field
   * @param replacementArgs Initialization params for the replacement field
   * @return a new IndexSchema based on this schema with the named field replaced
   */
  public IndexSchema replaceField(
      String fieldName, FieldType replacementFieldType, Map<String, ?> replacementArgs) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, adds the given dynamic fields to the copy, Requires synchronizing on the
   * object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param newDynamicFields the SchemaFields to add
   * @param copyFieldNames 0 or more names of targets to copy this field to. The target fields must
   *     already exist.
   * @param persist to persist the schema or not
   * @return a new IndexSchema based on this schema with newDynamicFields added
   * @see #newDynamicField(String, String, Map)
   */
  public IndexSchema addDynamicFields(
      Collection<SchemaField> newDynamicFields,
      Map<String, Collection<String>> copyFieldNames,
      boolean persist) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, deletes the named dynamic fields from the copy.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param fieldNamePatterns the names of the dynamic fields to delete
   * @return a new IndexSchema based on this schema with the named dynamic fields deleted
   */
  public IndexSchema deleteDynamicFields(Collection<String> fieldNamePatterns) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, deletes the named dynamic field from the copy, creates a new dynamic field
   * with the same field name pattern using the given args, then rebinds any referring dynamic copy
   * fields to the replacement dynamic field.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param fieldNamePattern The glob for the dynamic field to be replaced
   * @param replacementFieldType The field type of the replacement dynamic field
   * @param replacementArgs Initialization params for the replacement dynamic field
   * @return a new IndexSchema based on this schema with the named dynamic field replaced
   */
  public ManagedIndexSchema replaceDynamicField(
      String fieldNamePattern, FieldType replacementFieldType, Map<String, ?> replacementArgs) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema and adds the new copy fields to the copy Requires synchronizing on the
   * object returned by {@link #getSchemaUpdateLock()}.
   *
   * @see #addCopyFields(String,Collection,int) to limit the number of copied characters.
   * @param copyFields Key is the name of the source field name, value is a collection of target
   *     field names. Fields must exist.
   * @param persist to persist the schema or not
   * @return The new Schema with the copy fields added
   */
  public IndexSchema addCopyFields(Map<String, Collection<String>> copyFields, boolean persist) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema and adds the new copy fields to the copy.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}
   *
   * @param source source field name
   * @param destinations collection of target field names
   * @param maxChars max number of characters to copy from the source to each of the destinations.
   *     Use {@link CopyField#UNLIMITED} if you don't want to limit the number of copied chars.
   * @return The new Schema with the copy fields added
   */
  public IndexSchema addCopyFields(String source, Collection<String> destinations, int maxChars) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema and deletes the given copy fields from the copy.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param copyFields Key is the name of the source field name, value is a collection of target
   *     field names. Each corresponding copy field directives must exist.
   * @return The new Schema with the copy fields deleted
   */
  public IndexSchema deleteCopyFields(Map<String, Collection<String>> copyFields) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /** Informs analyzers used by a fieldType. */
  private void informResourceLoaderAwareObjectsForFieldType(FieldType fieldType) {
    // must inform any sub-components used in the
    // tokenizer chain if they are ResourceLoaderAware
    if (!fieldType.supportsAnalyzers()) return;

    Analyzer indexAnalyzer = fieldType.getIndexAnalyzer();
    if (indexAnalyzer != null && indexAnalyzer instanceof TokenizerChain)
      informResourceLoaderAwareObjectsInChain((TokenizerChain) indexAnalyzer);

    Analyzer queryAnalyzer = fieldType.getQueryAnalyzer();
    // ref comparison is correct here (vs. equals) as they may be the same
    // object in which case, we don't need to inform twice ... however, it's
    // actually safe to call inform multiple times on an object anyway
    if (queryAnalyzer != null
        && queryAnalyzer != indexAnalyzer
        && queryAnalyzer instanceof TokenizerChain)
      informResourceLoaderAwareObjectsInChain((TokenizerChain) queryAnalyzer);

    // if fieldType is a TextField, it might have a multi-term analyzer
    if (fieldType instanceof TextField) {
      TextField textFieldType = (TextField) fieldType;
      Analyzer multiTermAnalyzer = textFieldType.getMultiTermAnalyzer();
      if (multiTermAnalyzer != null
          && multiTermAnalyzer != indexAnalyzer
          && multiTermAnalyzer != queryAnalyzer
          && multiTermAnalyzer instanceof TokenizerChain)
        informResourceLoaderAwareObjectsInChain((TokenizerChain) multiTermAnalyzer);
    }
  }

  /**
   * Returns a SchemaField if the given fieldName does not already exist in this schema, and does
   * not match any dynamic fields in this schema. The resulting SchemaField can be used in a call to
   * {@link #addField(SchemaField)}.
   *
   * @param fieldName the name of the field to add
   * @param fieldType the field type for the new field
   * @param options the options to use when creating the SchemaField
   * @return The created SchemaField
   * @see #addField(SchemaField)
   */
  public SchemaField newField(String fieldName, String fieldType, Map<String, ?> options) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Returns a SchemaField if the given dynamic field glob does not already exist in this schema,
   * and does not match any dynamic fields in this schema. The resulting SchemaField can be used in
   * a call to {@link #addField(SchemaField)}.
   *
   * @param fieldNamePattern the pattern for the dynamic field to add
   * @param fieldType the field type for the new field
   * @param options the options to use when creating the SchemaField
   * @return The created SchemaField
   * @see #addField(SchemaField)
   */
  public SchemaField newDynamicField(
      String fieldNamePattern, String fieldType, Map<String, ?> options) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * After creating a new FieldType, it may contain components that implement the
   * ResourceLoaderAware interface, which need to be informed after they are loaded (as they depend
   * on this callback to complete initialization work)
   */
  private void informResourceLoaderAwareObjectsInChain(TokenizerChain chain) {
    CharFilterFactory[] charFilters = chain.getCharFilterFactories();
    for (CharFilterFactory next : charFilters) {
      if (next instanceof ResourceLoaderAware) {
        try {
          SolrResourceLoader.informAware(loader, (ResourceLoaderAware) next);
        } catch (IOException e) {
          throw new SolrException(ErrorCode.SERVER_ERROR, e);
        }
      }
    }

    TokenizerFactory tokenizerFactory = chain.getTokenizerFactory();
    if (tokenizerFactory instanceof ResourceLoaderAware) {
      try {
        SolrResourceLoader.informAware(loader, (ResourceLoaderAware) tokenizerFactory);
      } catch (IOException e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, e);
      }
    }

    TokenFilterFactory[] filters = chain.getTokenFilterFactories();
    for (TokenFilterFactory next : filters) {
      if (next instanceof ResourceLoaderAware) {
        try {
          SolrResourceLoader.informAware(loader, (ResourceLoaderAware) next);
        } catch (IOException e) {
          throw new SolrException(ErrorCode.SERVER_ERROR, e);
        }
      }
    }
  }

  /**
   * Returns the schema update lock that should be synchronized on to update the schema. Only
   * applicable to mutable schemas.
   *
   * @return the schema update lock object to synchronize on
   */
  public Object getSchemaUpdateLock() {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, adds the given field type to the copy, Requires synchronizing on the object
   * returned by {@link #getSchemaUpdateLock()}.
   *
   * @param fieldTypeList a list of FieldTypes to add
   * @param persist to persist the schema or not
   * @return a new IndexSchema based on this schema with the new types added
   * @see #newFieldType(String, String, Map)
   */
  public IndexSchema addFieldTypes(List<FieldType> fieldTypeList, boolean persist) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, deletes the named field types from the copy.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param names the names of the field types to delete
   * @return a new IndexSchema based on this schema with the named field types deleted
   */
  public IndexSchema deleteFieldTypes(Collection<String> names) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Copies this schema, deletes the named field type from the copy, creates a new field type with
   * the same name using the given args, rebuilds fields and dynamic fields of the given type, then
   * rebinds any referring copy fields to the rebuilt fields.
   *
   * <p>The schema will not be persisted.
   *
   * <p>Requires synchronizing on the object returned by {@link #getSchemaUpdateLock()}.
   *
   * @param typeName The name of the field type to be replaced
   * @param replacementClassName The class name of the replacement field type
   * @param replacementArgs Initialization params for the replacement field type
   * @return a new IndexSchema based on this schema with the named field type replaced
   */
  public IndexSchema replaceFieldType(
      String typeName, String replacementClassName, Map<String, Object> replacementArgs) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Returns a FieldType if the given typeName does not already exist in this schema. The resulting
   * FieldType can be used in a call to {@link #addFieldTypes(java.util.List, boolean)}.
   *
   * @param typeName the name of the type to add
   * @param className the name of the FieldType class
   * @param options the options to use when creating the FieldType
   * @return The created FieldType
   * @see #addFieldTypes(java.util.List, boolean)
   */
  public FieldType newFieldType(String typeName, String className, Map<String, ?> options) {
    String msg = "This IndexSchema is not mutable.";
    log.error(msg);
    throw new SolrException(ErrorCode.SERVER_ERROR, msg);
  }

  /**
   * Helper method that returns <code>true</code> if the {@link #ROOT_FIELD_NAME} uses the exact
   * same 'type' as the {@link #getUniqueKeyField()}
   *
   * @lucene.internal
   */
  public boolean isUsableForChildDocs() {
    // TODO make this boolean a field so it needn't be looked up each time?
    FieldType rootType = getFieldTypeNoEx(ROOT_FIELD_NAME);
    return (null != uniqueKeyFieldType
        && null != rootType
        && rootType.getTypeName().equals(uniqueKeyFieldType.getTypeName()));
  }

  public PayloadDecoder getPayloadDecoder(String field) {
    FieldType ft = getFieldType(field);
    if (ft == null) return null;
    return decoders.computeIfAbsent(ft, f -> PayloadUtils.getPayloadDecoder(ft));
  }
}
