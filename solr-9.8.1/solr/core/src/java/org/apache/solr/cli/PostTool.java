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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.ws.rs.core.UriBuilder;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DeprecatedAttributes;
import org.apache.commons.cli.Option;
import org.apache.solr.client.api.util.SolrVersion;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.Utils;
import org.apache.solr.util.RTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class PostTool extends ToolBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String DEFAULT_FILE_TYPES =
      "xml,json,jsonl,csv,pdf,doc,docx,ppt,pptx,xls,xlsx,odt,odp,ods,ott,otp,ots,rtf,htm,html,txt,log";
  static final String DATA_MODE_FILES = "files";
  static final String DATA_MODE_ARGS = "args";
  static final String DATA_MODE_STDIN = "stdin";
  static final String DATA_MODE_WEB = "web";
  static final String FORMAT_SOLR = "solr";

  private static final int DEFAULT_WEB_DELAY = 10;
  private static final int MAX_WEB_DEPTH = 10;
  public static final String DEFAULT_CONTENT_TYPE = "application/json";

  // Input args
  int recursive = 0;
  int delay = 0;
  String fileTypes = PostTool.DEFAULT_FILE_TYPES;
  URI solrUpdateUrl;
  String credentials;
  OutputStream out = null;
  String type;
  String format;
  boolean commit;
  boolean optimize;
  boolean dryRun; // Avoids actual network traffic to Solr

  String[] args;
  String params;

  boolean auto = true;
  private int currentDepth;

  static HashMap<String, String> mimeMap;
  FileFilter fileFilter;
  // Backlog for crawling
  List<LinkedHashSet<URI>> backlog = new ArrayList<>();
  Set<URI> visited = new HashSet<>();

  static final Set<String> DATA_MODES = new HashSet<>();

  PostTool.PageFetcher pageFetcher = new PostTool.PageFetcher();

  static {
    DATA_MODES.add(DATA_MODE_FILES);
    DATA_MODES.add(DATA_MODE_ARGS);
    DATA_MODES.add(DATA_MODE_STDIN);
    DATA_MODES.add(DATA_MODE_WEB);

    mimeMap = new HashMap<>();
    mimeMap.put("xml", "application/xml");
    mimeMap.put("csv", "text/csv");
    mimeMap.put("json", "application/json");
    mimeMap.put("jsonl", "application/jsonl");
    mimeMap.put("pdf", "application/pdf");
    mimeMap.put("rtf", "text/rtf");
    mimeMap.put("html", "text/html");
    mimeMap.put("htm", "text/html");
    mimeMap.put("doc", "application/msword");
    mimeMap.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    mimeMap.put("ppt", "application/vnd.ms-powerpoint");
    mimeMap.put(
        "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    mimeMap.put("xls", "application/vnd.ms-excel");
    mimeMap.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    mimeMap.put("odt", "application/vnd.oasis.opendocument.text");
    mimeMap.put("ott", "application/vnd.oasis.opendocument.text");
    mimeMap.put("odp", "application/vnd.oasis.opendocument.presentation");
    mimeMap.put("otp", "application/vnd.oasis.opendocument.presentation");
    mimeMap.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
    mimeMap.put("ots", "application/vnd.oasis.opendocument.spreadsheet");
    mimeMap.put("txt", "text/plain");
    mimeMap.put("log", "text/plain");
  }

  public PostTool() {
    this(CLIO.getOutStream());
  }

  public PostTool(PrintStream stdout) {
    super(stdout);
  }

  @Override
  public String getName() {
    return "post";
  }

  @Override
  public List<Option> getOptions() {
    return List.of(
        Option.builder("url")
            .longOpt("solr-update-url")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.8")
                    .setDescription("Use --solr-url and -c / --name instead")
                    .get())
            .hasArg()
            .argName("UPDATEURL")
            .desc("Solr Update URL, the full url to the update handler, including the /update.")
            .build(),
        Option.builder("c")
            .longOpt("name")
            .hasArg()
            .argName("NAME")
            .desc("Name of the collection.")
            .build(),
        Option.builder()
            .longOpt("skip-commit")
            .desc("Do not 'commit', and thus changes won't be visible till a commit occurs.")
            .build(),
        Option.builder()
            .longOpt("skipcommit")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.7")
                    .setDescription("Use --skip-commit instead")
                    .get())
            .desc("Do not 'commit', and thus changes won't be visible till a commit occurs.")
            .build(),
        Option.builder("o")
            .longOpt("optimize")
            .desc("Issue an optimize at end of posting documents.")
            .build(),
        Option.builder()
            .longOpt("mode")
            .hasArg()
            .argName("mode")
            .desc(
                "Which mode the Post tool is running in, 'files' crawls local directory, 'web' crawls website, 'args' processes input args, and 'stdin' reads a command from standard in. default: files.")
            .build(),
        Option.builder("r")
            .longOpt("recursive")
            .hasArg()
            .argName("recursive")
            .required(false)
            .desc("For web crawl, how deep to go. default: 1")
            .build(),
        Option.builder("d")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.8")
                    .setDescription("Use --delay instead")
                    .get())
            .hasArg()
            .argName("delay")
            .required(false)
            .desc(
                "If recursive then delay will be the wait time between posts.  default: 10 for web, 0 for files")
            .build(),
        Option.builder()
            .longOpt("delay")
            .hasArg()
            .argName("delay")
            .required(false)
            .desc(
                "If recursive then delay will be the wait time between posts.  default: 10 for web, 0 for files")
            .build(),
        Option.builder("t")
            .longOpt("type")
            .hasArg()
            .argName("content-type")
            .required(false)
            .desc("Specify a specific mimetype to use, such as application/json.")
            .build(),
        Option.builder("ft")
            .longOpt("filetypes")
            .hasArg()
            .argName("<type>[,<type>,...]")
            .required(false)
            .desc("default: " + DEFAULT_FILE_TYPES)
            .build(),
        Option.builder()
            .longOpt("params")
            .hasArg()
            .argName("<key>=<value>[&<key>=<value>...]")
            .required(false)
            .desc("Values must be URL-encoded; these pass through to Solr update request.")
            .build(),
        Option.builder("p")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.8")
                    .setDescription("Use --params instead")
                    .get())
            .hasArg()
            .argName("<key>=<value>[&<key>=<value>...]")
            .required(false)
            .desc("Values must be URL-encoded; these pass through to Solr update request.")
            .build(),
        Option.builder()
            .longOpt("out")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.8")
                    .setDescription("Use --verbose instead")
                    .get())
            .required(false)
            .desc("sends Solr response outputs to console.")
            .build(),
        Option.builder("f")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.8")
                    .setDescription("Use --format instead")
                    .get())
            .required(false)
            .desc(
                "sends application/json content as Solr commands to /update instead of /update/json/docs.")
            .build(),
        Option.builder()
            .longOpt("format")
            .required(false)
            .desc(
                "sends application/json content as Solr commands to /update instead of /update/json/docs.")
            .build(),
        Option.builder()
            .longOpt("dry-run")
            .required(false)
            .desc(
                "Performs a dry run of the posting process without actually sending documents to Solr.  Only works with files mode.")
            .build(),
        SolrCLI.OPTION_SOLRURL);
  }

  @Override
  public void runImpl(CommandLine cli) throws Exception {
    SolrCLI.raiseLogLevelUnlessVerbose(cli);

    solrUpdateUrl = null;
    if (cli.hasOption("solr-url")) {
      if (!cli.hasOption("name")) {
        throw new IllegalArgumentException(
            "Must specify -c / --name parameter with --solr-url to post documents.");
      }

      String solrUrl = cli.getOptionValue("solr-url");

      String hostContext = System.getProperty("hostContext", "/solr");
      if (hostContext.isBlank()) {
        log.warn("Invalid hostContext {} provided, setting to /solr", hostContext);
        hostContext = "/solr";
      }

      solrUrl = SolrCLI.normalizeSolrUrl(solrUrl, true, hostContext) + hostContext;

      solrUpdateUrl =
          UriBuilder.fromUri(SolrCLI.normalizeSolrUrl(solrUrl, true, hostContext))
              .path(hostContext)
              .path(cli.getOptionValue("name"))
              .path("update")
              .build();

    } else if (cli.hasOption("solr-update-url")) {
      String url = cli.getOptionValue("solr-update-url");
      solrUpdateUrl = new URI(url);
    } else if (cli.hasOption("name")) {
      solrUpdateUrl =
          UriBuilder.fromUri(SolrCLI.getDefaultSolrUrl())
              .path("solr")
              .path(cli.getOptionValue("name"))
              .path("update")
              .build();
    } else {
      throw new IllegalArgumentException(
          "Must specify either --solr-update-url or -c parameter to post documents.");
    }

    String mode = cli.getOptionValue("mode", DATA_MODE_FILES);

    dryRun = cli.hasOption("dry-run");

    if (cli.hasOption("type")) {
      type = cli.getOptionValue("type");
      // Turn off automatically looking up the mimetype in favour of what is passed in.
      auto = false;
    }
    format =
        cli.hasOption("format") || cli.hasOption("f")
            ? FORMAT_SOLR
            : ""; // i.e not solr formatted json commands

    if (cli.hasOption("filetypes")) {
      fileTypes = cli.getOptionValue("filetypes");
    }

    delay = (mode.equals((DATA_MODE_WEB)) ? 10 : 0);
    if (cli.hasOption("delay")) {
      delay = Integer.parseInt(cli.getOptionValue("delay"));
    } else if (cli.hasOption("d")) {
      delay = Integer.parseInt(cli.getOptionValue("d"));
    }

    recursive = Integer.parseInt(cli.getOptionValue("recursive", "1"));

    out =
        cli.hasOption("out") || cli.hasOption(SolrCLI.OPTION_VERBOSE.getLongOpt())
            ? CLIO.getOutStream()
            : null;
    commit = !(cli.hasOption("skipcommit") || cli.hasOption("skip-commit"));
    optimize = cli.hasOption("optimize");

    args = cli.getArgs();

    params = SolrCLI.getOptionWithDeprecatedAndDefault(cli, "params", "p", "");

    execute(mode);
  }

  /**
   * After initialization, call execute to start the post job. This method delegates to the correct
   * mode method.
   */
  public void execute(String mode) throws SolrServerException, IOException {
    final RTimer timer = new RTimer();
    if (PostTool.DATA_MODE_FILES.equals(mode)) {
      doFilesMode();
    } else if (DATA_MODE_ARGS.equals(mode)) {
      doArgsMode(args);
    } else if (PostTool.DATA_MODE_WEB.equals(mode)) {
      doWebMode();
    } else if (DATA_MODE_STDIN.equals(mode)) {
      doStdinMode();
    } else {
      return;
    }

    if (optimize) {
      // optimize does a commit under the covers.
      optimize();
    } else if (commit) {
      commit();
    }
    displayTiming((long) timer.getTime());
  }

  private void doFilesMode() {
    currentDepth = 0;

    info(
        "Posting files to [base] url "
            + solrUpdateUrl
            + (!auto ? " using content-type " + (type == null ? DEFAULT_CONTENT_TYPE : type) : "")
            + "...");
    if (auto) {
      info("Entering auto mode. File endings considered are " + fileTypes);
    }
    if (recursive > 0) {
      if (recursionPossible(args)) {
        info("Entering recursive mode, max depth=" + recursive + ", delay=" + delay + "s");
      }
    }
    fileFilter = getFileFilterFromFileTypes(fileTypes);
    int numFilesPosted = postFiles(args, 0, out, type);
    if (dryRun) {
      info("Dry run complete. " + numFilesPosted + " would have been indexed.");
    } else {
      info(numFilesPosted + " files indexed.");
    }
  }

  private void doArgsMode(String[] args) {
    info("POSTing args to " + solrUpdateUrl + "...");
    for (String a : args) {
      postData(stringToStream(a), null, out, type, solrUpdateUrl);
    }
  }

  private void doWebMode() {
    reset();
    int numPagesPosted;
    if (type != null) {
      throw new IllegalArgumentException(
          "Specifying content-type with \"--mode=web\" is not supported");
    }

    // Set Extracting handler as default
    solrUpdateUrl = UriBuilder.fromUri(solrUpdateUrl).path("extract").build();

    info("Posting web pages to Solr url " + solrUpdateUrl);
    auto = true;
    info(
        "Entering auto mode. Indexing pages with content-types corresponding to file endings "
            + fileTypes);
    if (recursive > 0) {
      if (recursive > MAX_WEB_DEPTH) {
        recursive = MAX_WEB_DEPTH;
        warn("Too large recursion depth for web mode, limiting to " + MAX_WEB_DEPTH + "...");
      }
      if (delay < DEFAULT_WEB_DELAY) {
        warn(
            "Never crawl an external web site faster than every 10 seconds, your IP will probably be blocked");
      }
      info("Entering recursive mode, depth=" + recursive + ", delay=" + delay + "s");
    }
    numPagesPosted = postWebPages(args, 0, out);
    info(numPagesPosted + " web pages indexed.");
  }

  private void doStdinMode() {
    info("POSTing stdin to " + solrUpdateUrl + "...");
    postData(System.in, null, out, type, solrUpdateUrl);
  }

  private void reset() {
    backlog = new ArrayList<>();
    visited = new HashSet<>();
  }

  /**
   * Pretty prints the number of milliseconds taken to post the content to Solr
   *
   * @param millis the time in milliseconds
   */
  private void displayTiming(long millis) {
    SimpleDateFormat df = new SimpleDateFormat("H:mm:ss.SSS", Locale.getDefault());
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    CLIO.out("Time spent: " + df.format(new Date(millis)));
  }

  private boolean checkIsValidPath(File srcFile) {
    return Files.exists(srcFile.toPath());
  }

  /**
   * Check all the arguments looking to see if any are directories, and if so then we can recurse
   * into them.
   *
   * @param args array of file names
   * @return if we have a directory to recurse into
   */
  boolean recursionPossible(String[] args) {
    boolean recursionPossible = false;
    for (String arg : args) {
      File f = new File(arg);
      if (f.isDirectory()) {
        recursionPossible = true;
      }
    }
    return recursionPossible;
  }

  /**
   * Post all filenames provided in args
   *
   * @param args array of file names
   * @param startIndexInArgs offset to start
   * @param out output stream to post data to
   * @param type default content-type to use when posting (this may be overridden in auto mode)
   * @return number of files posted
   */
  public int postFiles(String[] args, int startIndexInArgs, OutputStream out, String type) {
    reset();
    int filesPosted = 0;
    for (int j = startIndexInArgs; j < args.length; j++) {
      File srcFile = new File(args[j]);
      filesPosted = getFilesPosted(out, type, srcFile);
    }
    return filesPosted;
  }

  private int getFilesPosted(final OutputStream out, final String type, final File srcFile) {
    int filesPosted = 0;
    boolean isValidPath = checkIsValidPath(srcFile);
    if (isValidPath && srcFile.isDirectory() && srcFile.canRead()) {
      filesPosted += postDirectory(srcFile, out, type);
    } else if (isValidPath && srcFile.isFile() && srcFile.canRead()) {
      filesPosted += postFiles(new File[] {srcFile}, out, type);
    } else {
      filesPosted += handleGlob(srcFile, out, type);
    }
    return filesPosted;
  }

  /**
   * Posts a whole directory
   *
   * @return number of files posted total
   */
  private int postDirectory(File dir, OutputStream out, String type) {
    if (dir.isHidden() && !dir.getName().equals(".")) {
      return (0);
    }
    info(
        "Indexing directory "
            + dir.getPath()
            + " ("
            + dir.listFiles(fileFilter).length
            + " files, depth="
            + currentDepth
            + ")");
    int posted = 0;
    posted += postFiles(dir.listFiles(fileFilter), out, type);
    if (recursive > currentDepth) {
      for (File d : dir.listFiles()) {
        if (d.isDirectory()) {
          currentDepth++;
          posted += postDirectory(d, out, type);
          currentDepth--;
        }
      }
    }
    return posted;
  }

  /**
   * Posts a list of file names
   *
   * @return number of files posted
   */
  int postFiles(File[] files, OutputStream out, String type) {
    int filesPosted = 0;
    for (File srcFile : files) {
      try {
        if (!srcFile.isFile() || srcFile.isHidden()) {
          continue;
        }
        postFile(srcFile, out, type);
        Thread.sleep(delay * 1000L);
        filesPosted++;
      } catch (InterruptedException | MalformedURLException | URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }
    return filesPosted;
  }

  /**
   * This only handles file globs not full path globbing.
   *
   * @param globFile file holding glob path
   * @param out outputStream to write results to
   * @param type default content-type to use when posting (this may be overridden in auto mode)
   * @return number of files posted
   */
  int handleGlob(File globFile, OutputStream out, String type) {
    int filesPosted = 0;
    File parent = globFile.getParentFile();
    if (parent == null) {
      parent = new File(".");
    }
    String fileGlob = globFile.getName();
    PostTool.GlobFileFilter ff = new PostTool.GlobFileFilter(fileGlob, false);
    File[] fileList = parent.listFiles(ff);
    if (fileList == null || fileList.length == 0) {
      warn("No files or directories matching " + globFile);
    } else {
      filesPosted = postFiles(fileList, out, type);
    }
    return filesPosted;
  }

  /**
   * This method takes as input a list of start URL strings for crawling, converts the URL strings
   * to URI strings and adds each one to a backlog and then starts crawling
   *
   * @param args the raw input args from main()
   * @param startIndexInArgs offset for where to start
   * @param out outputStream to write results to
   * @return the number of web pages posted
   */
  public int postWebPages(String[] args, int startIndexInArgs, OutputStream out) {
    reset();
    LinkedHashSet<URI> s = new LinkedHashSet<>();
    for (int j = startIndexInArgs; j < args.length; j++) {
      try {
        URI uri = new URI(normalizeUrlEnding(args[j]));
        s.add(uri);
      } catch (URISyntaxException e) {
        warn("Skipping malformed input URL: " + args[j]);
      }
    }
    // Add URIs to level 0 of the backlog and start recursive crawling
    backlog.add(s);
    return webCrawl(0, out);
  }

  /**
   * Normalizes a URL string by removing anchor part and trailing slash
   *
   * @return the normalized URL string
   */
  protected static String normalizeUrlEnding(String link) {
    if (link.contains("#")) {
      link = link.substring(0, link.indexOf('#'));
    }
    if (link.endsWith("?")) {
      link = link.substring(0, link.length() - 1);
    }
    if (link.endsWith("/")) {
      link = link.substring(0, link.length() - 1);
    }
    return link;
  }

  /**
   * A very simple crawler, pulling URLs to fetch from a backlog and then recurses N levels deep if
   * recursive&gt;0. Links are parsed from HTML through first getting an XHTML version using
   * SolrCell with extractOnly, and followed if they are local. The crawler pauses for a default
   * delay of 10 seconds between each fetch, this can be configured in the delay variable. This is
   * only meant for test purposes, as it does not respect robots or anything else fancy :)
   *
   * @param level which level to crawl
   * @param out output stream to write to
   * @return number of pages crawled on this level and below
   */
  protected int webCrawl(int level, OutputStream out) {
    int numPages = 0;
    LinkedHashSet<URI> stack = backlog.get(level);
    int rawStackSize = stack.size();
    stack.removeAll(visited);
    int stackSize = stack.size();
    LinkedHashSet<URI> subStack = new LinkedHashSet<>();
    info(
        "Entering crawl at level "
            + level
            + " ("
            + rawStackSize
            + " links total, "
            + stackSize
            + " new)");
    for (URI uri : stack) {
      try {
        visited.add(uri);
        URL url = uri.toURL();
        PostTool.PageFetcherResult result = pageFetcher.readPageFromUrl(url);
        if (result.httpStatus == 200) {
          url = (result.redirectUrl != null) ? result.redirectUrl : url;
          URI postUri =
              new URI(
                  appendParam(
                      solrUpdateUrl.toString(),
                      "literal.id="
                          + URLEncoder.encode(url.toString(), UTF_8)
                          + "&literal.url="
                          + URLEncoder.encode(url.toString(), UTF_8)));
          ByteBuffer content = result.content;
          boolean success =
              postData(
                  new ByteArrayInputStream(content.array(), content.arrayOffset(), content.limit()),
                  null,
                  out,
                  result.contentType,
                  postUri);
          if (success) {
            info("POSTed web resource " + url + " (depth: " + level + ")");
            Thread.sleep(delay * 1000L);
            numPages++;
            // Pull links from HTML pages only
            if (recursive > level && result.contentType.equals("text/html")) {
              Set<URI> children =
                  pageFetcher.getLinksFromWebPage(
                      url,
                      new ByteArrayInputStream(
                          content.array(), content.arrayOffset(), content.limit()),
                      result.contentType,
                      postUri);
              subStack.addAll(children);
            }
          } else {
            warn("An error occurred while posting " + uri);
          }
        } else {
          warn("The URL " + uri + " returned a HTTP result status of " + result.httpStatus);
        }
      } catch (IOException | URISyntaxException e) {
        warn("Caught exception when trying to open connection to " + uri + ": " + e.getMessage());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!subStack.isEmpty()) {
      backlog.add(subStack);
      numPages += webCrawl(level + 1, out);
    }
    return numPages;
  }

  /**
   * Computes the full URL based on a base url and a possibly relative link found in the href param
   * of an HTML anchor.
   *
   * @param baseUrl the base url from where the link was found
   * @param link the absolute or relative link
   * @return the string version of the full URL
   */
  protected static String computeFullUrl(URL baseUrl, String link)
      throws MalformedURLException, URISyntaxException {
    if (link == null || link.length() == 0) {
      return null;
    }
    if (!link.startsWith("http")) {
      if (link.startsWith("/")) {
        link = baseUrl.toURI().resolve(link).toString();
      } else {
        if (link.contains(":")) {
          return null; // Skip non-relative URLs
        }
        String path = baseUrl.getPath();
        if (!path.endsWith("/")) {
          int sep = path.lastIndexOf('/');
          String file = path.substring(sep + 1);
          if (file.contains(".") || file.contains("?")) {
            path = path.substring(0, sep + 1);
          } else {
            path = path + "/";
          }
        }
        link = baseUrl.toURI().resolve(path + link).toString();
      }
    }
    link = normalizeUrlEnding(link);
    String l = link.toLowerCase(Locale.ROOT);
    // Simple brute force skip images
    if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".gif")) {
      return null; // Skip images
    }
    return link;
  }

  /**
   * Uses the mime-type map to reverse lookup whether the file ending for our type is supported by
   * the fileTypes option
   *
   * @param type what content-type to lookup
   * @return true if this is a supported content type
   */
  protected boolean typeSupported(String type) {
    for (Map.Entry<String, String> entry : mimeMap.entrySet()) {
      if (entry.getValue().equals(type)) {
        if (fileTypes.contains(entry.getKey())) {
          return true;
        }
      }
    }
    return false;
  }

  static void warn(String msg) {
    CLIO.err("PostTool: WARNING: " + msg);
  }

  static void info(String msg) {
    CLIO.out(msg);
  }

  /** Does a simple commit operation */
  public void commit() throws IOException, SolrServerException {
    info("COMMITting Solr index changes to " + solrUpdateUrl + "...");
    String updateUrl = solrUpdateUrl.toString();
    String collectionName = getCollection(updateUrl);
    String solrBaseUrl = getSolrBaseUrl(updateUrl);
    try (final SolrClient client = SolrCLI.getSolrClient(solrBaseUrl)) {
      client.commit(collectionName);
    }
  }

  /** Does a simple optimize operation */
  public void optimize() throws IOException, SolrServerException {
    info("Performing an OPTIMIZE to " + solrUpdateUrl + "...");
    String updateUrl = solrUpdateUrl.toString();
    String collectionName = getCollection(updateUrl);
    String solrBaseUrl = getSolrBaseUrl(updateUrl);
    try (final SolrClient client = SolrCLI.getSolrClient(solrBaseUrl)) {
      client.optimize(collectionName);
    }
  }

  private String getSolrBaseUrl(String solrUpdateUrl) {
    return solrUpdateUrl.substring(0, solrUpdateUrl.lastIndexOf("/solr/") + 5);
  }

  // Given a url ending in /update
  private String getCollection(String solrUpdateUrl) {
    return solrUpdateUrl.substring(
        solrUpdateUrl.lastIndexOf("/solr/") + 6, solrUpdateUrl.lastIndexOf("/update"));
  }

  /**
   * Appends a URL query parameter to a URL
   *
   * @param url the original URL
   * @param param the parameter(s) to append, separated by "&amp;"
   * @return the string version of the resulting URL
   */
  public static String appendParam(String url, String param) {
    String[] pa = param.split("&");
    StringBuilder urlBuilder = new StringBuilder(url);
    for (String p : pa) {
      if (p.trim().length() == 0) {
        continue;
      }
      String[] kv = p.split("=");
      if (kv.length == 2) {
        urlBuilder
            .append(urlBuilder.toString().contains("?") ? "&" : "?")
            .append(kv[0])
            .append("=")
            .append(kv[1]);
      } else {
        warn("Skipping param " + p + " which is not on form key=value");
      }
    }
    url = urlBuilder.toString();
    return url;
  }

  /** Opens the file and posts its contents to the solrUrl, writes to response to output. */
  public void postFile(File file, OutputStream output, String type)
      throws MalformedURLException, URISyntaxException {
    InputStream is = null;

    URI uri = solrUpdateUrl;
    String suffix = "";
    if (auto) {
      if (type == null) {
        type = guessType(file);
      }
      // TODO: Add a flag that disables /update and sends all to /update/extract, to avoid CSV,
      // JSON, and XML files
      // TODO: from being interpreted as Solr documents internally
      if (type.equals("application/json") && !PostTool.FORMAT_SOLR.equals(format)) {
        suffix = "/json/docs";
        String urlStr = UriBuilder.fromUri(solrUpdateUrl).path(suffix).build().toString();
        uri = new URI(urlStr);
      } else if (type.equals("application/xml")
          || type.equals("text/csv")
          || type.equals("application/json")) {
        // Default handler
      } else {
        // SolrCell
        suffix = "/extract";
        String urlStr = UriBuilder.fromUri(solrUpdateUrl).path(suffix).build().toString();
        ;
        if (!urlStr.contains("resource.name")) {
          urlStr =
              appendParam(
                  urlStr, "resource.name=" + URLEncoder.encode(file.getAbsolutePath(), UTF_8));
        }
        if (!urlStr.contains("literal.id")) {
          urlStr =
              appendParam(urlStr, "literal.id=" + URLEncoder.encode(file.getAbsolutePath(), UTF_8));
        }
        uri = new URI(urlStr);
      }
    } else {
      if (type == null) {
        type = DEFAULT_CONTENT_TYPE;
      }
    }
    if (dryRun) {
      info(
          "DRY RUN of POSTing file "
              + file.getName()
              + (auto ? " (" + type + ")" : "")
              + " to [base]"
              + suffix);
    } else {
      try {
        info(
            "POSTing file "
                + file.getName()
                + (auto ? " (" + type + ")" : "")
                + " to [base]"
                + suffix);
        is = new FileInputStream(file);
        postData(is, file.length(), output, type, uri);
      } catch (IOException e) {
        warn("Can't open/read file: " + file);
      } finally {
        try {
          if (is != null) {
            is.close();
          }
        } catch (IOException e) {
          warn("IOException while closing file: " + e);
        }
      }
    }
  }

  /**
   * Guesses the type of file, based on file name suffix Returns "application/octet-stream" if no
   * corresponding mimeMap type.
   *
   * @param file the file
   * @return the content-type guessed
   */
  protected static String guessType(File file) {
    String name = file.getName();
    String suffix = name.substring(name.lastIndexOf('.') + 1);
    String type = mimeMap.get(suffix.toLowerCase(Locale.ROOT));
    return (type != null) ? type : "application/octet-stream";
  }

  /**
   * Reads data from the data stream and posts it to solr, writes to the response to output
   *
   * @return true if success
   */
  public boolean postData(
      InputStream data, Long length, OutputStream output, String type, URI uri) {
    if (dryRun) {
      return true;
    }

    if (params.length() > 0) {
      try {
        uri = new URI(appendParam(uri.toString(), params));
      } catch (URISyntaxException e) {
        warn("Malformed params");
      }
    }

    boolean success = true;
    if (type == null) {
      type = DEFAULT_CONTENT_TYPE;
    }
    HttpURLConnection urlConnection = null;
    try {
      try {
        urlConnection = (HttpURLConnection) (uri.toURL()).openConnection();
        try {
          urlConnection.setRequestMethod("POST");
        } catch (ProtocolException e) {
          warn("Shouldn't happen: HttpURLConnection doesn't support POST??" + e);
        }
        urlConnection.setDoOutput(true);
        urlConnection.setDoInput(true);
        urlConnection.setUseCaches(false);
        urlConnection.setAllowUserInteraction(false);
        urlConnection.setRequestProperty("Content-type", type);
        basicAuth(urlConnection);
        if (null != length) {
          urlConnection.setFixedLengthStreamingMode(length);
        } else {
          urlConnection.setChunkedStreamingMode(-1); // use JDK default chunkLen, 4k in Java 8.
        }
        urlConnection.connect();
      } catch (IOException e) {
        warn("Connection error (is Solr running at " + solrUpdateUrl + " ?): " + e);
        success = false;
      } catch (Exception e) {
        warn("POST failed with error " + e.getMessage());
      }

      try (final OutputStream out = urlConnection.getOutputStream()) {
        pipe(data, out);
      } catch (IOException e) {
        warn("IOException while posting data: " + e);
      }

      try {
        success &= checkResponseCode(urlConnection);
        try (final InputStream in = urlConnection.getInputStream()) {
          pipe(in, output);
        }
      } catch (IOException e) {
        warn("IOException while reading response: " + e);
        success = false;
      } catch (GeneralSecurityException e) {
        warn("Looks like Solr is secured and would not let us in.");
      }
    } finally {
      if (urlConnection != null) {
        urlConnection.disconnect();
      }
    }
    return success;
  }

  private void basicAuth(HttpURLConnection urlc) throws Exception {
    if (urlc.getURL().getUserInfo() != null) {
      String encoding =
          Base64.getEncoder().encodeToString(urlc.getURL().getUserInfo().getBytes(US_ASCII));
      urlc.setRequestProperty("Authorization", "Basic " + encoding);
    } else if (credentials != null) {
      if (!credentials.contains(":")) {
        throw new Exception("credentials '" + credentials + "' must be of format user:pass");
      }
      urlc.setRequestProperty(
          "Authorization",
          "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)));
    }
  }

  private static boolean checkResponseCode(HttpURLConnection urlc)
      throws IOException, GeneralSecurityException {
    if (urlc.getResponseCode() >= 400) {
      warn(
          "Solr returned an error #"
              + urlc.getResponseCode()
              + " ("
              + urlc.getResponseMessage()
              + ") for url: "
              + urlc.getURL());
      Charset charset = StandardCharsets.ISO_8859_1;
      final String contentType = urlc.getContentType();
      // code cloned from ContentStreamBase, but post.jar should be standalone!
      if (contentType != null) {
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx > 0) {
          charset = Charset.forName(contentType.substring(idx + "charset=".length()).trim());
        }
      }
      // Print the response returned by Solr
      try (InputStream errStream = urlc.getErrorStream()) {
        if (errStream != null) {
          BufferedReader br = new BufferedReader(new InputStreamReader(errStream, charset));
          final StringBuilder response = new StringBuilder("Response: ");
          int ch;
          while ((ch = br.read()) != -1) {
            response.append((char) ch);
          }
          warn(response.toString().trim());
        }
      }
      if (urlc.getResponseCode() == 401) {
        throw new GeneralSecurityException(
            "Solr requires authentication (response 401). Please try again with '-u' option");
      }
      if (urlc.getResponseCode() == 403) {
        throw new GeneralSecurityException(
            "You are not authorized to perform this action against Solr. (response 403)");
      }
      return false;
    }
    return true;
  }

  /**
   * Converts a string to an input stream
   *
   * @param s the string
   * @return the input stream
   */
  public static InputStream stringToStream(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Pipes everything from the source to the dest. If dest is null, then everything is read from
   * source and thrown away.
   */
  private static void pipe(InputStream source, OutputStream dest) throws IOException {
    byte[] buf = new byte[1024];
    int read = 0;
    while ((read = source.read(buf)) >= 0) {
      if (null != dest) {
        dest.write(buf, 0, read);
      }
    }
    if (null != dest) {
      dest.flush();
    }
  }

  public FileFilter getFileFilterFromFileTypes(String fileTypes) {
    String glob;
    if (fileTypes.equals("*")) {
      glob = ".*";
    } else {
      glob = "^.*\\.(" + fileTypes.replace(",", "|") + ")$";
    }
    return new PostTool.GlobFileFilter(glob, true);
  }

  //
  // Utility methods for XPath handing
  //

  /** Gets all nodes matching an XPath */
  public static NodeList getNodesFromXP(Node n, String xpath) throws XPathExpressionException {
    XPathFactory factory = XPathFactory.newInstance();
    XPath xp = factory.newXPath();
    XPathExpression expr = xp.compile(xpath);
    return (NodeList) expr.evaluate(n, XPathConstants.NODESET);
  }

  /**
   * Gets the string content of the matching an XPath
   *
   * @param n the node (or doc)
   * @param xpath the xpath string
   * @param concatAll if true, text from all matching nodes will be concatenated, else only the
   *     first returned
   */
  public static String getXP(Node n, String xpath, boolean concatAll)
      throws XPathExpressionException {
    NodeList nodes = getNodesFromXP(n, xpath);
    StringBuilder sb = new StringBuilder();
    if (nodes.getLength() > 0) {
      for (int i = 0; i < nodes.getLength(); i++) {
        sb.append(nodes.item(i).getNodeValue()).append(' ');
        if (!concatAll) {
          break;
        }
      }
      return sb.toString().trim();
    } else return "";
  }

  /** Takes a string as input and returns a DOM */
  public static Document makeDom(byte[] in)
      throws SAXException, IOException, ParserConfigurationException {
    InputStream is = new ByteArrayInputStream(in);
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
  }

  /** Inner class to filter files based on glob wildcards */
  static class GlobFileFilter implements FileFilter {
    private final Pattern p;

    public GlobFileFilter(String pattern, boolean isRegex) {
      String _pattern = pattern;
      if (!isRegex) {
        _pattern =
            _pattern
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace(".", "\\.")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("+", "\\+")
                .replace("*", ".*")
                .replace("?", ".");
        _pattern = "^" + _pattern + "$";
      }

      try {
        p = Pattern.compile(_pattern, Pattern.CASE_INSENSITIVE);
      } catch (PatternSyntaxException e) {
        throw new IllegalArgumentException(
            "Invalid type list " + pattern + ". " + e.getDescription());
      }
    }

    @Override
    public boolean accept(File file) {
      return p.matcher(file.getName()).find();
    }
  }

  //
  // Simple crawler class which can fetch a page and check for robots.txt
  //
  class PageFetcher {
    Map<String, List<String>> robotsCache;
    static final String DISALLOW = "Disallow:";

    public PageFetcher() {
      robotsCache = new HashMap<>();
    }

    public PageFetcherResult readPageFromUrl(URL u) throws URISyntaxException {
      PostTool.PageFetcherResult res = new PostTool.PageFetcherResult();
      try {
        if (isDisallowedByRobots(u)) {
          warn("The URL " + u + " is disallowed by robots.txt and will not be crawled.");
          res.httpStatus = 403;
          URI uri = u.toURI();
          visited.add(uri);
          return res;
        }
        res.httpStatus = 404;
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestProperty(
            "User-Agent",
            "PostTool-crawler/" + SolrVersion.LATEST_STRING + " (https://solr.apache.org/)");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        conn.connect();
        res.httpStatus = conn.getResponseCode();
        if (!normalizeUrlEnding(conn.getURL().toString())
            .equals(normalizeUrlEnding(u.toString()))) {
          info("The URL " + u + " caused a redirect to " + conn.getURL());
          u = conn.getURL();
          res.redirectUrl = u;
          URI uri = u.toURI();
          visited.add(uri);
        }
        if (res.httpStatus == 200) {
          // Raw content type of form "text/html; encoding=utf-8"
          String rawContentType = conn.getContentType();
          String type = rawContentType.split(";")[0];
          if (typeSupported(type) || "*".equals(fileTypes)) {
            String encoding = conn.getContentEncoding();
            InputStream is;
            if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
              is = new GZIPInputStream(conn.getInputStream());
            } else if (encoding != null && encoding.equalsIgnoreCase("deflate")) {
              is = new InflaterInputStream(conn.getInputStream(), new Inflater(true));
            } else {
              is = conn.getInputStream();
            }

            // Read into memory, so that we later can pull links from the page without re-fetching
            res.content = Utils.toByteArray(is);
            is.close();
          } else {
            warn("Skipping URL with unsupported type " + type);
            res.httpStatus = 415;
          }
        }
      } catch (IOException e) {
        warn("IOException when reading page from url " + u + ": " + e.getMessage());
      }
      return res;
    }

    public boolean isDisallowedByRobots(URL url) {
      String host = url.getHost();
      String strRobot = url.getProtocol() + "://" + host + "/robots.txt";
      List<String> disallows = robotsCache.get(host);
      if (disallows == null) {
        disallows = new ArrayList<>();
        URL urlRobot;
        try {
          urlRobot = new URI(strRobot).toURL();
          disallows = parseRobotsTxt(urlRobot.openStream());
        } catch (URISyntaxException | MalformedURLException e) {
          return true; // We cannot trust this robots URL, should not happen
        } catch (IOException e) {
          // There is no robots.txt, will cache an empty disallow list
        }
      }

      robotsCache.put(host, disallows);

      String strURL = url.getFile();
      for (String path : disallows) {
        if (path.equals("/") || strURL.indexOf(path) == 0) return true;
      }
      return false;
    }

    /**
     * Very simple robots.txt parser which obeys all Disallow lines regardless of user agent or
     * whether there are valid Allow: lines.
     *
     * @param is Input stream of the robots.txt file
     * @return a list of disallow paths
     * @throws IOException if problems reading the stream
     */
    protected List<String> parseRobotsTxt(InputStream is) throws IOException {
      List<String> disallows = new ArrayList<>();
      BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      String l;
      while ((l = r.readLine()) != null) {
        String[] arr = l.split("#");
        if (arr.length == 0) continue;
        l = arr[0].trim();
        if (l.startsWith(DISALLOW)) {
          l = l.substring(DISALLOW.length()).trim();
          if (l.length() == 0) continue;
          disallows.add(l);
        }
      }
      is.close();
      return disallows;
    }

    /**
     * Finds links on a web page, using /extract?extractOnly=true
     *
     * @param url the URL of the web page
     * @param is the input stream of the page
     * @param type the content-type
     * @param postUri the URI (typically /solr/extract) in order to pull out links
     * @return a set of URIs parsed from the page
     */
    protected Set<URI> getLinksFromWebPage(URL url, InputStream is, String type, URI postUri) {
      Set<URI> linksFromPage = new HashSet<>();

      try {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        URI extractUri = new URI(appendParam(postUri.toString(), "extractOnly=true"));
        boolean success = postData(is, null, os, type, extractUri);
        if (success) {
          Document d = makeDom(os.toByteArray());
          String innerXml = getXP(d, "/response/str/text()[1]", false);
          d = makeDom(innerXml.getBytes(StandardCharsets.UTF_8));
          NodeList links = getNodesFromXP(d, "/html/body//a/@href");
          for (int i = 0; i < links.getLength(); i++) {
            String link = links.item(i).getTextContent();
            link = computeFullUrl(url, link);
            if (link == null) {
              continue;
            }
            URI newUri = new URI(link);
            if (newUri.getAuthority() == null
                || !newUri.getAuthority().equals(url.getAuthority())) {
              linksFromPage.add(newUri);
            }
          }
        }
      } catch (URISyntaxException e) {
        warn("Malformed URL " + url);
      } catch (IOException e) {
        warn("IOException opening URL " + url + ": " + e.getMessage());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      return linksFromPage;
    }
  }

  /** Utility class to hold the result form a page fetch */
  public static class PageFetcherResult {
    int httpStatus = 200;
    String contentType = "text/html";
    URL redirectUrl = null;
    ByteBuffer content;
  }
}
