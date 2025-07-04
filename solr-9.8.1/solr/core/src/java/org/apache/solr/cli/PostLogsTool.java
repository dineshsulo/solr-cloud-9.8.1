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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DeprecatedAttributes;
import org.apache.commons.cli.Option;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.handler.component.ShardRequest;

/** A command line tool for indexing Solr logs in the out-of-the-box log format. */
public class PostLogsTool extends ToolBase {

  public PostLogsTool() {
    this(CLIO.getOutStream());
  }

  public PostLogsTool(PrintStream stdout) {
    super(stdout);
  }

  @Override
  public String getName() {
    return "postlogs";
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
            .argName("ADDRESS")
            .desc("Address of the collection, example http://localhost:8983/solr/collection1/.")
            .build(),
        Option.builder("c")
            .longOpt("name")
            .hasArg()
            .argName("NAME")
            .desc("Name of the collection.")
            .build(),
        Option.builder("rootdir")
            .longOpt("rootdir")
            .hasArg()
            .argName("DIRECTORY")
            .required(true)
            .desc("All files found at or below the root directory will be indexed.")
            .build(),
        SolrCLI.OPTION_SOLRURL);
  }

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
    String rootDir = cli.getOptionValue("rootdir");
    runCommand(url, rootDir);
  }

  public void runCommand(String baseUrl, String root) throws IOException {
    Http2SolrClient.Builder builder =
        new Http2SolrClient.Builder(baseUrl).withKeyStoreReloadInterval(-1, TimeUnit.SECONDS);
    try (SolrClient client = builder.build()) {
      int rec = 0;
      UpdateRequest request = new UpdateRequest();

      List<Path> files;
      try (Stream<Path> stream = Files.walk(Path.of(root), Integer.MAX_VALUE)) {
        files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
      }

      for (Path file : files) {
        try (LineNumberReader reader =
            new LineNumberReader(Files.newBufferedReader(file, Charset.defaultCharset()))) {
          LogRecordReader recordReader = new LogRecordReader(reader);
          SolrInputDocument doc;
          String fileName = file.getFileName().toString();
          while (true) {
            try {
              doc = recordReader.readRecord();
            } catch (Throwable t) {
              CLIO.err(
                  "Error reading log record:" + reader.getLineNumber() + " from file:" + fileName);
              CLIO.err(t.getMessage());
              continue;
            }

            if (doc == null) {
              break;
            }

            rec++;
            UUID id = UUID.randomUUID();
            doc.setField("id", id.toString());
            doc.setField("file_s", fileName);
            request.add(doc);
            if (rec == 300) {
              sendBatch(client, request, false /* normal batch */);
              request = new UpdateRequest();
              rec = 0;
            }
          }
        }
      }

      if (rec > 0) {
        sendBatch(client, request, true /* last batch */);
      }
    }
  }

  private void sendBatch(SolrClient client, UpdateRequest request, boolean lastRequest) {
    final String beginMessage =
        lastRequest ? "Sending last batch ..." : "Sending batch of 300 log records...";
    CLIO.out(beginMessage);
    try {
      request.process(client);
      CLIO.out("Batch sent");
    } catch (Exception e) {
      CLIO.err("Batch sending failed: " + e.getMessage());
      e.printStackTrace(CLIO.getErrStream());
    }

    if (lastRequest) {
      try {
        client.commit();
        CLIO.out("Committed");
      } catch (Exception e) {
        CLIO.err("Unable to commit documents: " + e.getMessage());
        e.printStackTrace(CLIO.getErrStream());
      }
    }
  }

  public static class LogRecordReader {

    private BufferedReader bufferedReader;
    private String pushedBack = null;
    private boolean finished = false;
    private String cause;
    private Pattern p =
        Pattern.compile("^(\\d\\d\\d\\d\\-\\d\\d\\-\\d\\d[\\s|T]\\d\\d:\\d\\d\\:\\d\\d.\\d\\d\\d)");
    private Pattern minute =
        Pattern.compile("^(\\d\\d\\d\\d\\-\\d\\d\\-\\d\\d[\\s|T]\\d\\d:\\d\\d)");
    private Pattern tenSecond =
        Pattern.compile("^(\\d\\d\\d\\d\\-\\d\\d\\-\\d\\d[\\s|T]\\d\\d:\\d\\d:\\d)");

    public LogRecordReader(BufferedReader bufferedReader) throws IOException {
      this.bufferedReader = bufferedReader;
    }

    public SolrInputDocument readRecord() throws IOException {
      while (true) {
        String line = null;

        if (finished) {
          return null;
        }

        if (pushedBack != null) {
          line = pushedBack;
          pushedBack = null;
        } else {
          line = bufferedReader.readLine();
        }

        if (line != null) {
          SolrInputDocument lineDoc = new SolrInputDocument();
          String date = parseDate(line);
          String minute = parseMinute(line);
          String tenSecond = parseTenSecond(line);
          lineDoc.setField("date_dt", date);
          lineDoc.setField("time_minute_s", minute);
          lineDoc.setField("time_ten_second_s", tenSecond);
          lineDoc.setField("line_t", line);
          lineDoc.setField("type_s", "other"); // Overridden by known types below

          if (line.contains("Registered new searcher")) {
            parseNewSearch(lineDoc, line);
          } else if (line.contains("path=/update")) {
            parseUpdate(lineDoc, line);
          } else if (line.contains(" ERROR ")) {
            this.cause = null;
            parseError(lineDoc, line, readTrace());
          } else if (line.contains("QTime=")) {
            parseQueryRecord(lineDoc, line);
          }

          return lineDoc;
        } else {
          return null;
        }
      }
    }

    private String readTrace() throws IOException {
      StringBuilder buf = new StringBuilder();
      buf.append("%html ");

      while (true) {
        String line = bufferedReader.readLine();
        if (line == null) {
          finished = true;
          return buf.toString();
        } else {
          // look for a date at the beginning of the line
          // If it's not there then read into the stack trace buffer
          Matcher m = p.matcher(line);

          if (!m.find() && buf.length() < 10000) {
            // Line does not start with a timestamp so append to the stack trace
            buf.append(line.replace("\t", "    ")).append("<br/>");
            if (line.startsWith("Caused by:")) {
              this.cause = line;
            }
          } else {
            pushedBack = line;
            break;
          }
        }
      }

      return buf.toString();
    }

    private String parseDate(String line) {
      Matcher m = p.matcher(line);
      if (m.find()) {
        String date = m.group(1);
        return date.replace(" ", "T") + "Z";
      }

      return null;
    }

    private String parseMinute(String line) {
      Matcher m = minute.matcher(line);
      if (m.find()) {
        String date = m.group(1);
        return date.replace(" ", "T") + ":00Z";
      }

      return null;
    }

    private String parseTenSecond(String line) {
      Matcher m = tenSecond.matcher(line);
      if (m.find()) {
        String date = m.group(1);
        return date.replace(" ", "T") + "0Z";
      }

      return null;
    }

    private void setFieldIfUnset(SolrInputDocument doc, String fieldName, String fieldValue) {
      if (doc.containsKey(fieldName)) return;

      doc.setField(fieldName, fieldValue);
    }

    private void parseError(SolrInputDocument lineRecord, String line, String trace) {
      lineRecord.setField("type_s", "error");

      // Don't include traces that have only the %html header.
      if (trace != null && trace.length() > 6) {
        lineRecord.setField("stack_t", trace);
      }

      if (this.cause != null) {
        lineRecord.setField("root_cause_t", cause.replace("Caused by:", "").trim());
      }

      lineRecord.setField("collection_s", parseCollection(line));
      lineRecord.setField("core_s", parseCore(line));
      lineRecord.setField("shard_s", parseShard(line));
      lineRecord.setField("replica_s", parseReplica(line));
    }

    private void parseQueryRecord(SolrInputDocument lineRecord, String line) {
      lineRecord.setField("qtime_i", parseQTime(line));
      lineRecord.setField("status_s", parseStatus(line));

      String path = parsePath(line);
      lineRecord.setField("path_s", path);

      if (line.contains("hits=")) {
        lineRecord.setField("hits_l", parseHits(line));
      }

      String params = parseParams(line);
      lineRecord.setField("params_t", params);
      addParams(lineRecord, params);

      lineRecord.setField("collection_s", parseCollection(line));
      lineRecord.setField("core_s", parseCore(line));
      lineRecord.setField("node_s", parseNode(line));
      lineRecord.setField("shard_s", parseShard(line));
      lineRecord.setField("replica_s", parseReplica(line));

      if (path != null && path.contains("/admin")) {
        lineRecord.setField("type_s", "admin");
      } else if (path != null && params.contains("/replication")) {
        lineRecord.setField("type_s", "replication");
      } else if (path != null && path.contains("/get")) {
        lineRecord.setField("type_s", "get");
      } else {
        lineRecord.setField("type_s", "query");
      }
    }

    private void parseNewSearch(SolrInputDocument lineRecord, String line) {
      lineRecord.setField("core_s", parseCore(line));
      lineRecord.setField("type_s", "newSearcher");
      lineRecord.setField("collection_s", parseCollection(line));
      lineRecord.setField("shard_s", parseShard(line));
      lineRecord.setField("replica_s", parseReplica(line));
    }

    private String parseCollection(String line) {
      char[] ca = {' ', ']', ','};
      String[] parts = line.split("c:");
      if (parts.length >= 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private void parseUpdate(SolrInputDocument lineRecord, String line) {
      if (line.contains("deleteByQuery=")) {
        lineRecord.setField("type_s", "deleteByQuery");
      } else if (line.contains("delete=")) {
        lineRecord.setField("type_s", "delete");
      } else if (line.contains("commit=true")) {
        lineRecord.setField("type_s", "commit");
      } else {
        lineRecord.setField("type_s", "update");
      }

      lineRecord.setField("collection_s", parseCollection(line));
      lineRecord.setField("core_s", parseCore(line));
      lineRecord.setField("shard_s", parseShard(line));
      lineRecord.setField("replica_s", parseReplica(line));
    }

    private String parseCore(String line) {
      char[] ca = {' ', ']', '}', ','};
      String[] parts = line.split("x:");
      if (parts.length >= 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseShard(String line) {
      char[] ca = {' ', ']', '}', ','};
      String[] parts = line.split("s:");
      if (parts.length >= 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseReplica(String line) {
      char[] ca = {' ', ']', '}', ','};
      String[] parts = line.split("r:");
      if (parts.length >= 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parsePath(String line) {
      char[] ca = {' '};
      String[] parts = line.split(" path=");
      if (parts.length == 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseQTime(String line) {
      char[] ca = {'\n', '\r'};
      String[] parts = line.split(" QTime=");
      if (parts.length == 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseNode(String line) {
      char[] ca = {' ', ']', '}', ','};
      String[] parts = line.split("node_name=n:");
      if (parts.length >= 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseStatus(String line) {
      char[] ca = {' ', '\n', '\r'};
      String[] parts = line.split(" status=");
      if (parts.length == 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseHits(String line) {
      char[] ca = {' '};
      String[] parts = line.split(" hits=");
      if (parts.length == 2) {
        return readUntil(parts[1], ca);
      } else {
        return null;
      }
    }

    private String parseParams(String line) {
      char[] ca = {' '};
      String[] parts = line.split(" params=");
      if (parts.length == 2) {
        String p = readUntil(parts[1].substring(1), ca);
        return p.substring(0, p.length() - 1);
      } else {
        return null;
      }
    }

    private String readUntil(String s, char[] chars) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        char a = s.charAt(i);
        for (char c : chars) {
          if (a == c) {
            return builder.toString();
          }
        }
        builder.append(a);
      }

      return builder.toString();
    }

    private void addParams(SolrInputDocument doc, String params) {
      String[] pairs = params.split("&");
      for (String pair : pairs) {
        String[] parts = pair.split("=");
        if (parts.length == 2 && parts[0].equals("q")) {
          String dq = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "q_s", dq);
          setFieldIfUnset(doc, "q_t", dq);
        }

        if (parts[0].equals("rows")) {
          String dr = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "rows_i", dr);
        }

        if (parts[0].equals("start")) {
          String dr = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "start_i", dr);
        }

        if (parts[0].equals("distrib")) {
          String dr = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "distrib_s", dr);
        }

        if (parts[0].equals("shards")) {
          setFieldIfUnset(doc, "shards_s", "true");
        }

        if (parts[0].equals("ids") && !isRTGRequest(doc)) {
          setFieldIfUnset(doc, "ids_s", "true");
        }

        if (parts[0].equals("isShard")) {
          String dr = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "isShard_s", dr);
        }

        if (parts[0].equals("wt")) {
          String dr = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "wt_s", dr);
        }

        if (parts[0].equals("facet")) {
          String dr = URLDecoder.decode(parts[1], Charset.defaultCharset());
          setFieldIfUnset(doc, "facet_s", dr);
        }

        if (parts[0].equals("shards.purpose")) {
          try {
            int purpose = Integer.parseInt(parts[1]);
            String[] purposes = getRequestPurposeNames(purpose);
            for (String p : purposes) {
              doc.addField("purpose_ss", p);
            }
          } catch (Throwable e) {
            // We'll just sit on this for now and not interrupt the load for this one field.
          }
        }
      }

      // Special params used to determine what stage a query is.
      // So we populate with defaults.
      // The absence of the distrib params means it's a distributed query.
      setFieldIfUnset(doc, "distrib_s", "true");
      setFieldIfUnset(doc, "shards_s", "false");
      setFieldIfUnset(doc, "ids_s", "false");
    }

    private boolean isRTGRequest(SolrInputDocument doc) {
      final SolrInputField path = doc.getField("path_s");
      if (path == null) return false;

      return "/get".equals(path.getValue());
    }
  }

  private static final Map<Integer, String> purposes;
  protected static final String UNKNOWN_VALUE = "Unknown";
  private static final String[] purposeUnknown = new String[] {UNKNOWN_VALUE};

  public static String[] getRequestPurposeNames(Integer reqPurpose) {
    if (reqPurpose != null) {
      int valid = 0;
      for (Map.Entry<Integer, String> entry : purposes.entrySet()) {
        if ((reqPurpose & entry.getKey()) != 0) {
          valid++;
        }
      }
      if (valid == 0) {
        return purposeUnknown;
      } else {
        String[] result = new String[valid];
        int i = 0;
        for (Map.Entry<Integer, String> entry : purposes.entrySet()) {
          if ((reqPurpose & entry.getKey()) != 0) {
            result[i] = entry.getValue();
            i++;
          }
        }
        return result;
      }
    }
    return purposeUnknown;
  }

  static {
    Map<Integer, String> map = new TreeMap<>();
    map.put(ShardRequest.PURPOSE_PRIVATE, "PRIVATE");
    map.put(ShardRequest.PURPOSE_GET_TOP_IDS, "GET_TOP_IDS");
    map.put(ShardRequest.PURPOSE_REFINE_TOP_IDS, "REFINE_TOP_IDS");
    map.put(ShardRequest.PURPOSE_GET_FACETS, "GET_FACETS");
    map.put(ShardRequest.PURPOSE_REFINE_FACETS, "REFINE_FACETS");
    map.put(ShardRequest.PURPOSE_GET_FIELDS, "GET_FIELDS");
    map.put(ShardRequest.PURPOSE_GET_HIGHLIGHTS, "GET_HIGHLIGHTS");
    map.put(ShardRequest.PURPOSE_GET_DEBUG, "GET_DEBUG");
    map.put(ShardRequest.PURPOSE_GET_STATS, "GET_STATS");
    map.put(ShardRequest.PURPOSE_GET_TERMS, "GET_TERMS");
    map.put(ShardRequest.PURPOSE_GET_TOP_GROUPS, "GET_TOP_GROUPS");
    map.put(ShardRequest.PURPOSE_GET_MLT_RESULTS, "GET_MLT_RESULTS");
    map.put(ShardRequest.PURPOSE_REFINE_PIVOT_FACETS, "REFINE_PIVOT_FACETS");
    map.put(ShardRequest.PURPOSE_SET_TERM_STATS, "SET_TERM_STATS");
    map.put(ShardRequest.PURPOSE_GET_TERM_STATS, "GET_TERM_STATS");
    purposes = Collections.unmodifiableMap(map);
  }
}
