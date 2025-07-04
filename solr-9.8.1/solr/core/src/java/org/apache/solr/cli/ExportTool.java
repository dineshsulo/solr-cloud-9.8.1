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

package org.apache.solr.cli;

import static org.apache.solr.common.params.CommonParams.FL;
import static org.apache.solr.common.params.CommonParams.JAVABIN;
import static org.apache.solr.common.params.CommonParams.JSON;
import static org.apache.solr.common.params.CommonParams.Q;
import static org.apache.solr.common.params.CommonParams.SORT;
import static org.apache.solr.common.util.JavaBinCodec.SOLRINPUTDOC;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DeprecatedAttributes;
import org.apache.commons.cli.Option;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.ClusterStateProvider;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.StreamingBinaryResponseParser;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CursorMarkParams;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.CollectionUtil;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.StrUtils;
import org.noggit.CharArr;
import org.noggit.JSONWriter;

public class ExportTool extends ToolBase {
  @Override
  public String getName() {
    return "export";
  }

  @Override
  public List<Option> getOptions() {
    return List.of(
        Option.builder("url")
            .longOpt("solr-collection-url")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.8")
                    .setDescription("Use --solr-url and -c / --name instead")
                    .get())
            .hasArg()
            .argName("URL")
            .desc("Address of the collection, example http://localhost:8983/solr/gettingstarted.")
            .build(),
        Option.builder("c")
            .longOpt("name")
            .hasArg()
            .argName("NAME")
            .desc("Name of the collection.")
            .build(),
        Option.builder("out")
            .hasArg()
            .argName("PATH")
            .desc(
                "Path to output the exported data, and optionally the file name, defaults to 'collection-name'.")
            .build(),
        Option.builder("format")
            .hasArg()
            .argName("FORMAT")
            .desc("Output format for exported docs (json, jsonl or javabin), defaulting to json.")
            .build(),
        Option.builder("compress").desc("Compress the output.").build(),
        Option.builder("limit")
            .hasArg()
            .argName("#")
            .desc("Maximum number of docs to download. Default is 100, use -1 for all docs.")
            .build(),
        Option.builder("query")
            .hasArg()
            .argName("QUERY")
            .desc("A custom query, default is '*:*'.")
            .build(),
        Option.builder("fields")
            .hasArg()
            .argName("FIELDA,FIELDB")
            .desc("Comma separated list of fields to export. By default all fields are fetched.")
            .build(),
        SolrCLI.OPTION_SOLRURL);
  }

  public abstract static class Info {
    String baseurl;
    String format;
    boolean compress;
    String query;
    String coll;
    String out;
    String fields;
    long limit = 100;
    AtomicLong docsWritten = new AtomicLong(0);
    int bufferSize = 1024 * 1024;
    PrintStream output;
    String uniqueKey;
    CloudSolrClient solrClient;
    DocsSink sink;

    public Info(String url) {
      setUrl(url);
      setOutFormat(null, "jsonl", false);
    }

    public void setUrl(String url) {
      int idx = url.lastIndexOf('/');
      baseurl = url.substring(0, idx);
      coll = url.substring(idx + 1);
      query = "*:*";
    }

    public void setLimit(String maxDocsStr) {
      limit = Long.parseLong(maxDocsStr);
      if (limit == -1) limit = Long.MAX_VALUE;
    }

    public void setOutFormat(String out, String format, boolean compress) {
      this.compress = compress;
      this.format = format;
      this.out = out;
      if (this.format == null) {
        this.format = JSON;
      }
      if (!formats.contains(this.format)) {
        throw new IllegalArgumentException("format must be one of: " + formats);
      }
      if (this.out == null) {
        this.out = coll;
      } else if (Files.isDirectory(Path.of(this.out))) {
        this.out = this.out + "/" + coll;
      }
      this.out = this.out + '.' + this.format;
      if (compress) {
        this.out = this.out + ".gz";
      }
    }

    DocsSink getSink() {
      DocsSink docSink = null;
      switch (format) {
        case JAVABIN:
          docSink = new JavabinSink(this);
          break;
        case JSON:
          docSink = new JsonSink(this);
          break;
        case "jsonl":
          docSink = new JsonWithLinesSink(this);
          break;
      }
      return docSink;
    }

    abstract void exportDocs() throws Exception;

    void fetchUniqueKey() throws SolrServerException, IOException {
      solrClient = new CloudHttp2SolrClient.Builder(Collections.singletonList(baseurl)).build();
      NamedList<Object> response =
          solrClient.request(
              new GenericSolrRequest(
                      SolrRequest.METHOD.GET,
                      "/schema/uniquekey",
                      new MapSolrParams(Collections.singletonMap("collection", coll)))
                  .setRequiresCollection(true));
      uniqueKey = (String) response.get("uniqueKey");
    }

    public static StreamingResponseCallback getStreamer(Consumer<SolrDocument> sink) {
      return new StreamingResponseCallback() {
        @Override
        public void streamSolrDocument(SolrDocument doc) {
          try {
            sink.accept(doc);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }

        @Override
        public void streamDocListInfo(long numFound, long start, Float maxScore) {}
      };
    }
  }

  static Set<String> formats = Set.of(JAVABIN, JSON, "jsonl");

  @Override
  public void runImpl(CommandLine cli) throws Exception {
    String url = null;
    if (cli.hasOption("solr-url")) {
      if (!cli.hasOption("name")) {
        throw new IllegalArgumentException(
            "Must specify -c / --name parameter with --solr-url to post documents.");
      }
      url = SolrCLI.normalizeSolrUrl(cli) + "/solr/" + cli.getOptionValue("name");

    } else if (cli.hasOption("solr-collection-url")) {
      url = cli.getOptionValue("solr-collection-url");
    } else {
      // Swap to required Option when --solr-collection-url removed.
      throw new IllegalArgumentException("Must specify --solr-url.");
    }
    Info info = new MultiThreadedRunner(url);
    info.query = cli.getOptionValue("query", "*:*");
    info.setOutFormat(
        cli.getOptionValue("out"), cli.getOptionValue("format"), cli.hasOption("compress"));
    info.fields = cli.getOptionValue("fields");
    info.setLimit(cli.getOptionValue("limit", "100"));
    info.output = super.stdout;
    info.exportDocs();
  }

  abstract static class DocsSink {
    Info info;
    OutputStream fos;

    abstract void start() throws IOException;

    @SuppressForbidden(reason = "Command line tool prints out to console")
    void accept(SolrDocument document) throws IOException {
      long count = info.docsWritten.incrementAndGet();

      if (count % 100000 == 0) {
        System.out.println("\nDOCS: " + count);
      }
    }

    void end() throws IOException {}
  }

  static class JsonWithLinesSink extends DocsSink {
    private final CharArr charArr = new CharArr(1024 * 2);
    JSONWriter jsonWriter = new JSONWriter(charArr, -1);
    private Writer writer;

    public JsonWithLinesSink(Info info) {
      this.info = info;
    }

    @Override
    public void start() throws IOException {
      fos = new FileOutputStream(info.out);
      if (info.compress) {
        fos = new GZIPOutputStream(fos);
      }
      if (info.bufferSize > 0) {
        fos = new BufferedOutputStream(fos, info.bufferSize);
      }
      writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
    }

    @Override
    public void end() throws IOException {
      writer.flush();
      fos.flush();
      fos.close();
    }

    @Override
    public synchronized void accept(SolrDocument doc) throws IOException {
      charArr.reset();
      Map<String, Object> m = CollectionUtil.newLinkedHashMap(doc.size());
      doc.forEach(
          (s, field) -> {
            if (s.equals("_version_") || s.equals("_roor_")) return;
            if (field instanceof List) {
              if (((List<?>) field).size() == 1) {
                field = ((List<?>) field).get(0);
              }
            }
            field = constructDateStr(field);
            if (field instanceof List) {
              List<?> list = (List<?>) field;
              if (hasdate(list)) {
                ArrayList<Object> listCopy = new ArrayList<>(list.size());
                for (Object o : list) listCopy.add(constructDateStr(o));
                field = listCopy;
              }
            }
            m.put(s, field);
          });
      jsonWriter.write(m);
      writer.write(charArr.getArray(), charArr.getStart(), charArr.getEnd());
      writer.append('\n');
      super.accept(doc);
    }

    private boolean hasdate(List<?> list) {
      boolean hasDate = false;
      for (Object o : list) {
        if (o instanceof Date) {
          hasDate = true;
          break;
        }
      }
      return hasDate;
    }

    private Object constructDateStr(Object field) {
      if (field instanceof Date) {
        field =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(((Date) field).getTime()));
      }
      return field;
    }
  }

  static class JsonSink extends DocsSink {
    private final CharArr charArr = new CharArr(1024 * 2);
    JSONWriter jsonWriter = new JSONWriter(charArr, -1);
    private Writer writer;
    private boolean firstDoc = true;

    public JsonSink(Info info) {
      this.info = info;
    }

    @Override
    public void start() throws IOException {
      fos = new FileOutputStream(info.out);
      if (info.compress) {
        fos = new GZIPOutputStream(fos);
      }
      if (info.bufferSize > 0) {
        fos = new BufferedOutputStream(fos, info.bufferSize);
      }
      writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
      writer.append('[');
    }

    @Override
    public void end() throws IOException {
      writer.append(']');
      writer.flush();
      fos.flush();
      fos.close();
    }

    @Override
    public synchronized void accept(SolrDocument doc) throws IOException {
      charArr.reset();
      Map<String, Object> m = CollectionUtil.newLinkedHashMap(doc.size());
      doc.forEach(
          (s, field) -> {
            if (s.equals("_version_") || s.equals("_roor_")) return;
            if (field instanceof List) {
              if (((List<?>) field).size() == 1) {
                field = ((List<?>) field).get(0);
              }
            }
            field = constructDateStr(field);
            if (field instanceof List) {
              List<?> list = (List<?>) field;
              if (hasdate(list)) {
                ArrayList<Object> listCopy = new ArrayList<>(list.size());
                for (Object o : list) listCopy.add(constructDateStr(o));
                field = listCopy;
              }
            }
            m.put(s, field);
          });
      if (firstDoc) {
        firstDoc = false;
      } else {
        writer.append(',');
      }
      jsonWriter.write(m);
      writer.write(charArr.getArray(), charArr.getStart(), charArr.getEnd());
      writer.append('\n');
      super.accept(doc);
    }

    private boolean hasdate(List<?> list) {
      boolean hasDate = false;
      for (Object o : list) {
        if (o instanceof Date) {
          hasDate = true;
          break;
        }
      }
      return hasDate;
    }

    private Object constructDateStr(Object field) {
      if (field instanceof Date) {
        field =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(((Date) field).getTime()));
      }
      return field;
    }
  }

  static class JavabinSink extends DocsSink {
    JavaBinCodec codec;

    public JavabinSink(Info info) {
      this.info = info;
    }

    @Override
    public void start() throws IOException {
      fos = new FileOutputStream(info.out);
      if (info.compress) {
        fos = new GZIPOutputStream(fos);
      }
      if (info.bufferSize > 0) {
        fos = new BufferedOutputStream(fos, info.bufferSize);
      }
      codec = new JavaBinCodec(fos, null);
      codec.writeTag(JavaBinCodec.NAMED_LST, 2);
      codec.writeStr("params");
      codec.writeNamedList(new NamedList<>());
      codec.writeStr("docs");
      codec.writeTag(JavaBinCodec.ITERATOR);
    }

    @Override
    public void end() throws IOException {
      codec.writeTag(JavaBinCodec.END);
      codec.close();
      fos.flush();
      fos.close();
    }

    private final BiConsumer<String, Object> bic =
        new BiConsumer<>() {
          @Override
          public void accept(String s, Object o) {
            try {
              if (s.equals("_version_") || s.equals("_root_")) return;
              codec.writeExternString(s);
              codec.writeVal(o);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };

    @Override
    public synchronized void accept(SolrDocument doc) throws IOException {
      int sz = doc.size();
      if (doc.containsKey("_version_")) sz--;
      if (doc.containsKey("_root_")) sz--;
      codec.writeTag(SOLRINPUTDOC, sz);
      codec.writeFloat(1f); // document boost
      doc.forEach(bic);
      super.accept(doc);
    }
  }

  static class MultiThreadedRunner extends Info {
    ExecutorService producerThreadpool, consumerThreadpool;
    ArrayBlockingQueue<SolrDocument> queue = new ArrayBlockingQueue<>(1000);
    SolrDocument EOFDOC = new SolrDocument();
    volatile boolean failed = false;
    Map<String, CoreHandler> corehandlers = new HashMap<>();
    private final long startTime;

    @SuppressForbidden(reason = "Need to print out time")
    public MultiThreadedRunner(String url) {
      super(url);
      startTime = System.currentTimeMillis();
    }

    @Override
    @SuppressForbidden(reason = "Need to print out time")
    void exportDocs() throws Exception {
      sink = getSink();
      fetchUniqueKey();
      ClusterStateProvider stateProvider = solrClient.getClusterStateProvider();
      DocCollection coll = stateProvider.getCollection(this.coll);
      Map<String, Slice> m = coll.getSlicesMap();
      producerThreadpool =
          ExecutorUtil.newMDCAwareFixedThreadPool(
              m.size(), new SolrNamedThreadFactory("solrcli-exporter-producers"));
      consumerThreadpool =
          ExecutorUtil.newMDCAwareFixedThreadPool(
              1, new SolrNamedThreadFactory("solrcli-exporter-consumer"));
      sink.start();
      CountDownLatch consumerlatch = new CountDownLatch(1);
      try {
        addConsumer(consumerlatch);
        addProducers(m);
        if (output != null) {
          output.println("Number of shards : " + corehandlers.size());
        }
        CountDownLatch producerLatch = new CountDownLatch(corehandlers.size());
        corehandlers.forEach(
            (s, coreHandler) ->
                producerThreadpool.execute(
                    () -> {
                      try {
                        coreHandler.exportDocsFromCore();
                      } catch (Exception e) {
                        if (output != null) output.println("Error exporting docs from : " + s);
                      }
                      producerLatch.countDown();
                    }));

        producerLatch.await();
        queue.offer(EOFDOC, 10, TimeUnit.SECONDS);
        consumerlatch.await();
      } finally {
        sink.end();
        solrClient.close();
        producerThreadpool.shutdownNow();
        consumerThreadpool.shutdownNow();
        if (failed) {
          try {
            Files.delete(new File(out).toPath());
          } catch (IOException e) {
            // ignore
          }
        }
        System.out.println(
            "\nTotal Docs exported: "
                + docsWritten.get()
                + ". Time elapsed: "
                + ((System.currentTimeMillis() - startTime) / 1000)
                + "seconds");
      }
    }

    private void addProducers(Map<String, Slice> m) {
      for (Map.Entry<String, Slice> entry : m.entrySet()) {
        Slice slice = entry.getValue();
        Replica replica = slice.getLeader();
        if (replica == null)
          replica = slice.getReplicas().iterator().next(); // get a random replica
        CoreHandler coreHandler = new CoreHandler(replica);
        corehandlers.put(replica.getCoreName(), coreHandler);
      }
    }

    private void addConsumer(CountDownLatch consumerlatch) {
      consumerThreadpool.execute(
          () -> {
            while (true) {
              SolrDocument doc;
              try {
                doc = queue.poll(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                if (output != null) output.println("Consumer interrupted");
                failed = true;
                break;
              }
              if (doc == EOFDOC) break;
              try {
                if (docsWritten.get() >= limit) {
                  continue;
                }
                sink.accept(doc);
              } catch (Exception e) {
                if (output != null) output.println("Failed to write to file " + e.getMessage());
                failed = true;
              }
            }
            consumerlatch.countDown();
          });
    }

    class CoreHandler {
      final Replica replica;
      long expectedDocs;
      AtomicLong receivedDocs = new AtomicLong();

      CoreHandler(Replica replica) {
        this.replica = replica;
      }

      boolean exportDocsFromCore() throws IOException, SolrServerException {

        try (SolrClient client = new Http2SolrClient.Builder(baseurl).build()) {
          expectedDocs = getDocCount(replica.getCoreName(), client, query);
          QueryRequest request;
          ModifiableSolrParams params = new ModifiableSolrParams();
          params.add(Q, query);
          if (fields != null) params.add(FL, fields);
          params.add(SORT, uniqueKey + " asc");
          params.add(CommonParams.DISTRIB, "false");
          params.add(CommonParams.ROWS, "1000");
          String cursorMark = CursorMarkParams.CURSOR_MARK_START;
          Consumer<SolrDocument> wrapper =
              doc -> {
                try {
                  queue.offer(doc, 10, TimeUnit.SECONDS);
                  receivedDocs.incrementAndGet();
                } catch (InterruptedException e) {
                  failed = true;
                  if (output != null) output.println("Failed to write docs from" + e.getMessage());
                }
              };
          StreamingBinaryResponseParser responseParser =
              new StreamingBinaryResponseParser(getStreamer(wrapper));
          while (true) {
            if (failed) return false;
            if (docsWritten.get() > limit) return true;
            params.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
            request = new QueryRequest(params);
            request.setResponseParser(responseParser);
            try {
              NamedList<Object> rsp = client.request(request, replica.getCoreName());
              String nextCursorMark = (String) rsp.get(CursorMarkParams.CURSOR_MARK_NEXT);
              if (nextCursorMark == null || Objects.equals(cursorMark, nextCursorMark)) {
                if (output != null) {
                  output.println(
                      StrUtils.formatString(
                          "\nExport complete from shard {0}, core {1}, docs received: {2}",
                          replica.getShard(), replica.getCoreName(), receivedDocs.get()));
                }
                if (expectedDocs != receivedDocs.get()) {
                  if (output != null) {
                    output.println(
                        StrUtils.formatString(
                            "Could not download all docs from core {0}, docs expected: {1}, received: {2}",
                            replica.getCoreName(), expectedDocs, receivedDocs.get()));
                    return false;
                  }
                }
                return true;
              }
              cursorMark = nextCursorMark;
              if (output != null) output.print(".");
            } catch (SolrServerException e) {
              if (output != null)
                output.println(
                    "Error reading from server "
                        + replica.getBaseUrl()
                        + "/"
                        + replica.getCoreName());
              failed = true;
              return false;
            }
          }
        }
      }
    }
  }

  static long getDocCount(String coreName, SolrClient client, String query)
      throws SolrServerException, IOException {
    SolrQuery q = new SolrQuery(query);
    q.setRows(0);
    q.add("distrib", "false");
    final var request = new QueryRequest(q);
    NamedList<Object> res = client.request(request, coreName);
    SolrDocumentList sdl = (SolrDocumentList) res.get("response");
    return sdl.getNumFound();
  }
}
