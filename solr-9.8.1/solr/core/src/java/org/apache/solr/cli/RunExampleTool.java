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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DeprecatedAttributes;
import org.apache.commons.cli.Option;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.OS;
import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.EnvUtils;
import org.noggit.CharArr;
import org.noggit.JSONWriter;

/** Supports an interactive session with the user to launch (or relaunch the -e cloud example) */
public class RunExampleTool extends ToolBase {

  private static final String PROMPT_FOR_NUMBER = "Please enter %s [%d]: ";
  private static final String PROMPT_FOR_NUMBER_IN_RANGE =
      "Please enter %s between %d and %d [%d]: ";
  private static final String PROMPT_NUMBER_TOO_SMALL =
      "%d is too small! " + PROMPT_FOR_NUMBER_IN_RANGE;
  private static final String PROMPT_NUMBER_TOO_LARGE =
      "%d is too large! " + PROMPT_FOR_NUMBER_IN_RANGE;

  protected InputStream userInput;
  protected Executor executor;
  protected String script;
  protected File serverDir;
  protected File exampleDir;
  protected File solrHomeDir;
  protected String urlScheme;

  /** Default constructor used by the framework when running as a command-line application. */
  public RunExampleTool() {
    this(null, System.in, CLIO.getOutStream());
  }

  public RunExampleTool(Executor executor, InputStream userInput, PrintStream stdout) {
    super(stdout);
    this.executor = (executor != null) ? executor : new DefaultExecutor();
    this.userInput = userInput;
  }

  @Override
  public String getName() {
    return "run_example";
  }

  @Override
  public List<Option> getOptions() {
    return List.of(
        Option.builder("y")
            .longOpt("no-prompt")
            .required(false)
            .desc(
                "Don't prompt for input; accept all defaults when running examples that accept user input.")
            .build(),
        Option.builder()
            .longOpt("noprompt")
            .deprecated(
                DeprecatedAttributes.builder()
                    .setForRemoval(true)
                    .setSince("9.7")
                    .setDescription("Use --no-prompt instead")
                    .get())
            .required(false)
            .desc(
                "Don't prompt for input; accept all defaults when running examples that accept user input.")
            .build(),
        Option.builder("e")
            .longOpt("example")
            .hasArg()
            .argName("NAME")
            .required(true)
            .desc("Name of the example to launch, one of: cloud, techproducts, schemaless, films.")
            .build(),
        Option.builder()
            .longOpt("script")
            .hasArg()
            .argName("PATH")
            .required(false)
            .desc("Path to the bin/solr script.")
            .build(),
        Option.builder()
            .longOpt("server-dir")
            .hasArg()
            .argName("SERVER_DIR")
            .required(true)
            .desc("Path to the Solr server directory.")
            .build(),
        Option.builder("f")
            .longOpt("force")
            .argName("FORCE")
            .desc("Force option in case Solr is run as root.")
            .build(),
        Option.builder()
            .longOpt("example-dir")
            .hasArg()
            .argName("EXAMPLE_DIR")
            .required(false)
            .desc(
                "Path to the Solr example directory; if not provided, ${serverDir}/../example is expected to exist.")
            .build(),
        Option.builder()
            .longOpt("solr-home")
            .hasArg()
            .argName("SOLR_HOME_DIR")
            .required(false)
            .desc(
                "Path to the Solr home directory; if not provided, ${serverDir}/solr is expected to exist.")
            .build(),
        Option.builder()
            .longOpt("url-scheme")
            .hasArg()
            .argName("SCHEME")
            .required(false)
            .desc("Solr URL scheme: http or https, defaults to http if not specified.")
            .build(),
        Option.builder("p")
            .longOpt("port")
            .hasArg()
            .argName("PORT")
            .required(false)
            .desc("Specify the port to start the Solr HTTP listener on; default is 8983.")
            .build(),
        Option.builder()
            .longOpt("host")
            .hasArg()
            .argName("HOSTNAME")
            .required(false)
            .desc("Specify the hostname for this Solr instance.")
            .build(),
        Option.builder("c")
            .longOpt("cloud")
            .required(false)
            .desc(
                "Start Solr in SolrCloud mode; if -z not supplied, an embedded ZooKeeper instance is started on Solr port+1000, such as 9983 if Solr is bound to 8983.")
            .build(),
        Option.builder("m")
            .longOpt("memory")
            .hasArg()
            .argName("MEM")
            .required(false)
            .desc(
                "Sets the min (-Xms) and max (-Xmx) heap size for the JVM, such as: -m 4g results in: -Xms4g -Xmx4g; by default, this script sets the heap size to 512m.")
            .build(),
        Option.builder()
            .longOpt("jvm-opts")
            .hasArg()
            .argName("OPTS")
            .required(false)
            .desc(
                "Additional options to be passed to the JVM when starting example Solr server(s).")
            .build(),
        SolrCLI.OPTION_ZKHOST,
        SolrCLI.OPTION_ZKHOST_DEPRECATED);
  }

  @Override
  public void runImpl(CommandLine cli) throws Exception {
    this.urlScheme = cli.getOptionValue("url-scheme", "http");
    String exampleType = cli.getOptionValue("example");

    serverDir = new File(cli.getOptionValue("server-dir"));
    if (!serverDir.isDirectory())
      throw new IllegalArgumentException(
          "Value of --server-dir option is invalid! "
              + serverDir.getAbsolutePath()
              + " is not a directory!");

    script = cli.getOptionValue("script");
    if (script != null) {
      if (!(new File(script)).isFile())
        throw new IllegalArgumentException(
            "Value of --script option is invalid! " + script + " not found");
    } else {
      File scriptFile = new File(serverDir.getParentFile(), "bin/solr");
      if (scriptFile.isFile()) {
        script = scriptFile.getAbsolutePath();
      } else {
        scriptFile = new File(serverDir.getParentFile(), "bin/solr.cmd");
        if (scriptFile.isFile()) {
          script = scriptFile.getAbsolutePath();
        } else {
          throw new IllegalArgumentException(
              "Cannot locate the bin/solr script! Please pass --script to this application.");
        }
      }
    }

    exampleDir =
        (cli.hasOption("example-dir"))
            ? new File(cli.getOptionValue("example-dir"))
            : new File(serverDir.getParent(), "example");
    if (!exampleDir.isDirectory())
      throw new IllegalArgumentException(
          "Value of --example-dir option is invalid! "
              + exampleDir.getAbsolutePath()
              + " is not a directory!");

    if (cli.hasOption("solr-home")) {
      solrHomeDir = new File(cli.getOptionValue("solr-home"));
    } else {
      String solrHomeProp = EnvUtils.getProperty("solr.home");
      if (solrHomeProp != null && !solrHomeProp.isEmpty()) {
        solrHomeDir = new File(solrHomeProp);
      } else if ("cloud".equals(exampleType)) {
        solrHomeDir = new File(exampleDir, "cloud");
        if (!solrHomeDir.isDirectory()) solrHomeDir.mkdir();
      } else {
        solrHomeDir = new File(serverDir, "solr");
      }
    }
    if (!solrHomeDir.isDirectory())
      throw new IllegalArgumentException(
          "Value of --solr-home option is invalid! "
              + solrHomeDir.getAbsolutePath()
              + " is not a directory!");

    echoIfVerbose(
        "Running with\nserverDir="
            + serverDir.getAbsolutePath()
            + ",\nexampleDir="
            + exampleDir.getAbsolutePath()
            + ",\nsolrHomeDir="
            + solrHomeDir.getAbsolutePath()
            + "\nscript="
            + script,
        cli);

    if ("cloud".equals(exampleType)) {
      runCloudExample(cli);
    } else if ("techproducts".equals(exampleType)
        || "schemaless".equals(exampleType)
        || "films".equals(exampleType)) {
      runExample(cli, exampleType);
    } else {
      throw new IllegalArgumentException(
          "Unsupported example "
              + exampleType
              + "! Please choose one of: cloud, schemaless, techproducts, or films");
    }
  }

  protected void runExample(CommandLine cli, String exampleName) throws Exception {
    String collectionName = "schemaless".equals(exampleName) ? "gettingstarted" : exampleName;
    String configSet =
        "techproducts".equals(exampleName) ? "sample_techproducts_configs" : "_default";

    boolean isCloudMode = cli.hasOption('c');
    String zkHost = SolrCLI.getOptionWithDeprecatedAndDefault(cli, "z", "zkHost", null);
    int port =
        Integer.parseInt(
            cli.getOptionValue('p', System.getenv().getOrDefault("SOLR_PORT", "8983")));
    Map<String, Object> nodeStatus = startSolr(solrHomeDir, isCloudMode, cli, port, zkHost, 30);

    String solrUrl = SolrCLI.normalizeSolrUrl((String) nodeStatus.get("baseUrl"), false);

    // If the example already exists then let the user know they should delete it, or
    // they may get unusual behaviors.
    boolean alreadyExists = false;
    boolean cloudMode = nodeStatus.get("cloud") != null;
    if (cloudMode) {
      if (SolrCLI.safeCheckCollectionExists(solrUrl, collectionName)) {
        alreadyExists = true;
        echo(
            "\nWARNING: Collection '"
                + collectionName
                + "' already exists, which may make starting this example not work well!");
      }
    } else {
      String coreName = collectionName;
      if (SolrCLI.safeCheckCoreExists(solrUrl, coreName)) {
        alreadyExists = true;
        echo(
            "\nWARNING: Core '"
                + coreName
                + "' already exists, which may make starting this example not work well!");
      }
    }

    if (alreadyExists) {
      echo(
          "You may want to run 'bin/solr delete -c "
              + collectionName
              + " --delete-config' first before running the example to ensure a fresh state.");
    }

    if (!alreadyExists) {
      // invoke the CreateTool
      String[] createArgs =
          new String[] {
            "--name", collectionName,
            "--shards", "1",
            "--replication-factor", "1",
            "--conf-name", collectionName,
            "--conf-dir", configSet,
            "--solr-url", solrUrl
          };
      CreateTool createTool = new CreateTool(stdout);
      int createCode = createTool.runTool(SolrCLI.processCommandLineArgs(createTool, createArgs));
      if (createCode != 0)
        throw new Exception(
            "Failed to create " + collectionName + " using command: " + Arrays.asList(createArgs));
    }

    if ("techproducts".equals(exampleName) && !alreadyExists) {

      File exampledocsDir = new File(this.exampleDir, "exampledocs");
      if (!exampledocsDir.isDirectory()) {
        File readOnlyExampleDir = new File(serverDir.getParentFile(), "example");
        if (readOnlyExampleDir.isDirectory()) {
          exampledocsDir = new File(readOnlyExampleDir, "exampledocs");
        }
      }

      if (exampledocsDir.isDirectory()) {
        echo("Indexing tech product example docs from " + exampledocsDir.getAbsolutePath());

        String[] args =
            new String[] {
              "post",
              "--solr-url",
              solrUrl,
              "--name",
              collectionName,
              "--type",
              "application/xml",
              "--filetypes",
              "xml",
              exampledocsDir.getAbsolutePath()
            };
        PostTool postTool = new PostTool();
        CommandLine postToolCli = SolrCLI.parseCmdLine(postTool, args);
        postTool.runTool(postToolCli);

      } else {
        echo(
            "exampledocs directory not found, skipping indexing step for the techproducts example");
      }
    } else if ("films".equals(exampleName) && !alreadyExists) {
      try (SolrClient solrClient = SolrCLI.getSolrClient(solrUrl)) {
        echo("Adding dense vector field type to films schema");
        SolrCLI.postJsonToSolr(
            solrClient,
            "/" + collectionName + "/schema",
            "{\n"
                + "        \"add-field-type\" : {\n"
                + "          \"name\":\"knn_vector_10\",\n"
                + "          \"class\":\"solr.DenseVectorField\",\n"
                + "          \"vectorDimension\":10,\n"
                + "          \"similarityFunction\":cosine\n"
                + "          \"knnAlgorithm\":hnsw\n"
                + "        }\n"
                + "      }");

        echo(
            "Adding name, genre, directed_by, initial_release_date, and film_vector fields to films schema");
        SolrCLI.postJsonToSolr(
            solrClient,
            "/" + collectionName + "/schema",
            "{\n"
                + "        \"add-field\" : {\n"
                + "          \"name\":\"name\",\n"
                + "          \"type\":\"text_general\",\n"
                + "          \"multiValued\":false,\n"
                + "          \"stored\":true\n"
                + "        },\n"
                + "        \"add-field\" : {\n"
                + "          \"name\":\"genre\",\n"
                + "          \"type\":\"text_general\",\n"
                + "          \"multiValued\":true,\n"
                + "          \"stored\":true\n"
                + "        },\n"
                + "        \"add-field\" : {\n"
                + "          \"name\":\"directed_by\",\n"
                + "          \"type\":\"text_general\",\n"
                + "          \"multiValued\":true,\n"
                + "          \"stored\":true\n"
                + "        },\n"
                + "        \"add-field\" : {\n"
                + "          \"name\":\"initial_release_date\",\n"
                + "          \"type\":\"pdate\",\n"
                + "          \"stored\":true\n"
                + "        },\n"
                + "        \"add-field\" : {\n"
                + "          \"name\":\"film_vector\",\n"
                + "          \"type\":\"knn_vector_10\",\n"
                + "          \"indexed\":true\n"
                + "          \"stored\":true\n"
                + "        },\n"
                + "        \"add-copy-field\" : {\n"
                + "          \"source\":\"genre\",\n"
                + "          \"dest\":\"_text_\"\n"
                + "        },\n"
                + "        \"add-copy-field\" : {\n"
                + "          \"source\":\"name\",\n"
                + "          \"dest\":\"_text_\"\n"
                + "        },\n"
                + "        \"add-copy-field\" : {\n"
                + "          \"source\":\"directed_by\",\n"
                + "          \"dest\":\"_text_\"\n"
                + "        }\n"
                + "      }");

        echo(
            "Adding paramsets \"algo\" and \"algo_b\" to films configuration for relevancy tuning");
        SolrCLI.postJsonToSolr(
            solrClient,
            "/" + collectionName + "/config/params",
            "{\n"
                + "        \"set\": {\n"
                + "        \"algo_a\":{\n"
                + "               \"defType\":\"dismax\",\n"
                + "               \"qf\":\"name\"\n"
                + "             }\n"
                + "           },\n"
                + "           \"set\": {\n"
                + "             \"algo_b\":{\n"
                + "               \"defType\":\"dismax\",\n"
                + "               \"qf\":\"name\",\n"
                + "               \"mm\":\"100%\"\n"
                + "             }\n"
                + "            }\n"
                + "        }\n");

        File filmsJsonFile = new File(this.exampleDir, "films/films.json");
        echo("Indexing films example docs from " + filmsJsonFile.getAbsolutePath());
        String[] args =
            new String[] {
              "post",
              "--solr-url",
              solrUrl,
              "--name",
              collectionName,
              "--type",
              "application/json",
              filmsJsonFile.getAbsolutePath()
            };
        PostTool postTool = new PostTool();
        CommandLine postToolCli = SolrCLI.parseCmdLine(postTool, args);
        postTool.runTool(postToolCli);

      } catch (Exception ex) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, ex);
      }

      echo(
          "\nSolr "
              + exampleName
              + " example launched successfully. Direct your Web browser to "
              + solrUrl
              + " to visit the Solr Admin UI");
    }
  }

  protected void runCloudExample(CommandLine cli) throws Exception {

    boolean prompt = !(cli.hasOption("no-prompt") || cli.hasOption("noprompt"));
    int numNodes = 2;
    int[] cloudPorts = new int[] {8983, 7574, 8984, 7575};
    int defaultPort =
        Integer.parseInt(
            cli.getOptionValue('p', System.getenv().getOrDefault("SOLR_PORT", "8983")));
    if (defaultPort != 8983) {
      // Override the old default port numbers if user has started the example overriding SOLR_PORT
      cloudPorts = new int[] {defaultPort, defaultPort + 1, defaultPort + 2, defaultPort + 3};
    }

    echo("\nWelcome to the SolrCloud example!\n");

    Scanner readInput = prompt ? new Scanner(userInput, StandardCharsets.UTF_8.name()) : null;
    if (prompt) {
      echo(
          "This interactive session will help you launch a SolrCloud cluster on your local workstation.");

      // get the number of nodes to start
      numNodes =
          promptForInt(
              readInput,
              "To begin, how many Solr nodes would you like to run in your local cluster? (specify 1-4 nodes) [2]: ",
              "a number",
              numNodes,
              1,
              4);

      echo("Ok, let's start up " + numNodes + " Solr nodes for your example SolrCloud cluster.");

      // get the ports for each port
      for (int n = 0; n < numNodes; n++) {
        String promptMsg =
            String.format(
                Locale.ROOT, "Please enter the port for node%d [%d]: ", (n + 1), cloudPorts[n]);
        int port = promptForPort(readInput, n + 1, promptMsg, cloudPorts[n]);
        while (!isPortAvailable(port)) {
          port =
              promptForPort(
                  readInput,
                  n + 1,
                  "Oops! Looks like port "
                      + port
                      + " is already being used by another process. Please choose a different port.",
                  cloudPorts[n]);
        }

        cloudPorts[n] = port;
        echoIfVerbose("Using port " + port + " for node " + (n + 1), cli);
      }
    } else {
      echo("Starting up " + numNodes + " Solr nodes for your example SolrCloud cluster.\n");
    }

    // setup a unique solr.solr.home directory for each node
    File node1Dir = setupSolrHomeDir(serverDir, solrHomeDir, "node1");
    for (int n = 2; n <= numNodes; n++) {
      File nodeNDir = new File(solrHomeDir, "node" + n);
      if (!nodeNDir.isDirectory()) {
        echo("Cloning " + node1Dir.getAbsolutePath() + " into\n   " + nodeNDir.getAbsolutePath());
        FileUtils.copyDirectory(node1Dir, nodeNDir);
      } else {
        echo(nodeNDir.getAbsolutePath() + " already exists.");
      }
    }

    // deal with extra args passed to the script to run the example
    String zkHost = SolrCLI.getOptionWithDeprecatedAndDefault(cli, "z", "zkHost", null);

    // start the first node (most likely with embedded ZK)
    Map<String, Object> nodeStatus =
        startSolr(new File(node1Dir, "solr"), true, cli, cloudPorts[0], zkHost, 30);

    if (zkHost == null) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cloudStatus = (Map<String, Object>) nodeStatus.get("cloud");
      if (cloudStatus != null) {
        String zookeeper = (String) cloudStatus.get("ZooKeeper");
        if (zookeeper != null) zkHost = zookeeper;
      }
      if (zkHost == null)
        throw new Exception("Could not get the ZooKeeper connection string for node1!");
    }

    if (numNodes > 1) {
      // start the other nodes
      for (int n = 1; n < numNodes; n++)
        startSolr(
            new File(solrHomeDir, "node" + (n + 1) + "/solr"),
            true,
            cli,
            cloudPorts[n],
            zkHost,
            30);
    }

    String solrUrl = SolrCLI.normalizeSolrUrl((String) nodeStatus.get("baseUrl"), false);

    // wait until live nodes == numNodes
    waitToSeeLiveNodes(zkHost, numNodes);

    // create the collection
    String collectionName = createCloudExampleCollection(numNodes, readInput, prompt, solrUrl);

    echo("\n\nSolrCloud example running, please visit: " + solrUrl + " \n");
  }

  /** wait until the number of live nodes == numNodes. */
  protected void waitToSeeLiveNodes(String zkHost, int numNodes) {
    try (CloudSolrClient cloudClient =
        new CloudSolrClient.Builder(Collections.singletonList(zkHost), Optional.empty()).build()) {
      cloudClient.connect();
      Set<String> liveNodes = cloudClient.getClusterState().getLiveNodes();
      int numLiveNodes = (liveNodes != null) ? liveNodes.size() : 0;
      long timeout = System.nanoTime() + TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);
      while (System.nanoTime() < timeout && numLiveNodes < numNodes) {
        echo(
            "\nWaiting up to "
                + 10
                + " seconds to see "
                + (numNodes - numLiveNodes)
                + " more nodes join the SolrCloud cluster ...");
        try {
          Thread.sleep(2000);
        } catch (InterruptedException ie) {
          Thread.interrupted();
        }
        liveNodes = cloudClient.getClusterState().getLiveNodes();
        numLiveNodes = (liveNodes != null) ? liveNodes.size() : 0;
      }
      if (numLiveNodes < numNodes) {
        echo(
            "\nWARNING: Only "
                + numLiveNodes
                + " of "
                + numNodes
                + " are active in the cluster after "
                + 10
                + " seconds! Please check the solr.log for each node to look for errors.\n");
      }
    } catch (Exception exc) {
      CLIO.err("Failed to see if " + numNodes + " joined the SolrCloud cluster due to: " + exc);
    }
  }

  protected Map<String, Object> startSolr(
      File solrHomeDir,
      boolean cloudMode,
      CommandLine cli,
      int port,
      String zkHost,
      int maxWaitSecs)
      throws Exception {

    String extraArgs = readExtraArgs(cli.getArgs());

    String host = cli.getOptionValue("host");
    String memory = cli.getOptionValue('m');

    String hostArg = (host != null && !"localhost".equals(host)) ? " --host " + host : "";
    String zkHostArg = (zkHost != null) ? " -z " + zkHost : "";
    String memArg = (memory != null) ? " -m " + memory : "";
    String cloudModeArg = cloudMode ? "--cloud " : "";
    String forceArg = cli.hasOption("force") ? " --force" : "";
    String verboseArg = verbose ? "-V" : "";

    String jvmOpts =
        cli.hasOption("jvm-opts") ? cli.getOptionValue("jvm-opts") : cli.getOptionValue('a');
    String jvmOptsArg = (jvmOpts != null) ? " --jvm-opts \"" + jvmOpts + "\"" : "";

    File cwd = new File(System.getProperty("user.dir"));
    File binDir = (new File(script)).getParentFile();

    boolean isWindows = (OS.isFamilyDOS() || OS.isFamilyWin9x() || OS.isFamilyWindows());
    String callScript = (!isWindows && cwd.equals(binDir.getParentFile())) ? "bin/solr" : script;

    String cwdPath = cwd.getAbsolutePath();
    String solrHome = solrHomeDir.toPath().toAbsolutePath().toRealPath().toString();

    // don't display a huge path for solr home if it is relative to the cwd
    if (!isWindows && cwdPath.length() > 1 && solrHome.startsWith(cwdPath))
      solrHome = solrHome.substring(cwdPath.length() + 1);

    final var syspropArg =
        ("techproducts".equals(cli.getOptionValue("example")))
            ? "-Dsolr.modules=clustering,extraction,langid,ltr,scripting -Dsolr.ltr.enabled=true -Dsolr.clustering.enabled=true"
            : "";

    String startCmd =
        String.format(
            Locale.ROOT,
            "\"%s\" start %s -p %d --solr-home \"%s\" --server-dir \"%s\" %s %s %s %s %s %s %s %s",
            callScript,
            cloudModeArg,
            port,
            solrHome,
            serverDir.getAbsolutePath(),
            hostArg,
            zkHostArg,
            memArg,
            forceArg,
            verboseArg,
            extraArgs,
            jvmOptsArg,
            syspropArg);
    startCmd = startCmd.replaceAll("\\s+", " ").trim(); // for pretty printing

    echo("\nStarting up Solr on port " + port + " using command:");
    echo(startCmd + "\n");

    String solrUrl =
        String.format(
            Locale.ROOT, "%s://%s:%d/solr", urlScheme, (host != null ? host : "localhost"), port);

    Map<String, Object> nodeStatus = checkPortConflict(solrUrl, solrHomeDir, port);
    if (nodeStatus != null)
      return nodeStatus; // the server they are trying to start is already running

    int code = 0;
    if (isWindows) {
      // On Windows, the execution doesn't return, so we have to execute async
      // and when calling the script, it seems to be inheriting the environment that launched this
      // app, so we have to prune out env vars that may cause issues
      Map<String, String> startEnv = new HashMap<>();
      Map<String, String> procEnv = EnvironmentUtils.getProcEnvironment();
      if (procEnv != null) {
        for (Map.Entry<String, String> entry : procEnv.entrySet()) {
          String envVar = entry.getKey();
          String envVarVal = entry.getValue();
          if (envVarVal != null && !"EXAMPLE".equals(envVar) && !envVar.startsWith("SOLR_")) {
            startEnv.put(envVar, envVarVal);
          }
        }
      }
      DefaultExecuteResultHandler handler = new DefaultExecuteResultHandler();
      executor.execute(org.apache.commons.exec.CommandLine.parse(startCmd), startEnv, handler);

      // wait for execution.
      try {
        handler.waitFor(3000);
      } catch (InterruptedException ie) {
        // safe to ignore ...
        Thread.interrupted();
      }
      if (handler.hasResult() && handler.getExitValue() != 0) {
        throw new Exception(
            "Failed to start Solr using command: "
                + startCmd
                + " Exception : "
                + handler.getException());
      }
    } else {
      try {
        code = executor.execute(org.apache.commons.exec.CommandLine.parse(startCmd));
      } catch (ExecuteException e) {
        throw new Exception(
            "Failed to start Solr using command: " + startCmd + " Exception : " + e);
      }
    }
    if (code != 0) throw new Exception("Failed to start Solr using command: " + startCmd);

    return getNodeStatus(solrUrl, maxWaitSecs, cli);
  }

  protected Map<String, Object> checkPortConflict(String solrUrl, File solrHomeDir, int port) {
    // quickly check if the port is in use
    if (isPortAvailable(port)) return null; // not in use ... try to start

    Map<String, Object> nodeStatus = null;
    try {
      nodeStatus = (new StatusTool()).getStatus(solrUrl);
    } catch (Exception ignore) {
      /* just trying to determine if this example is already running. */
    }

    if (nodeStatus != null) {
      String solr_home = (String) nodeStatus.get("solr_home");
      if (solr_home != null) {
        String solrHomePath = solrHomeDir.getAbsolutePath();
        if (!solrHomePath.endsWith("/")) solrHomePath += "/";
        if (!solr_home.endsWith("/")) solr_home += "/";

        if (solrHomePath.equals(solr_home)) {
          CharArr arr = new CharArr();
          new JSONWriter(arr, 2).write(nodeStatus);
          echo("Solr is already setup and running on port " + port + " with status:\n" + arr);
          echo(
              "\nIf this is not the example node you are trying to start, please choose a different port.");
          nodeStatus.put("baseUrl", solrUrl);
          return nodeStatus;
        }
      }
    }

    throw new IllegalStateException("Port " + port + " is already being used by another process.");
  }

  protected String readExtraArgs(String[] extraArgsArr) {
    String extraArgs = "";
    if (extraArgsArr != null && extraArgsArr.length > 0) {
      StringBuilder sb = new StringBuilder();
      int app = 0;
      for (int e = 0; e < extraArgsArr.length; e++) {
        String arg = extraArgsArr[e];
        if ("e".equals(arg) || "example".equals(arg)) {
          e++; // skip over the example arg
          continue;
        }

        if (app > 0) sb.append(" ");
        sb.append(arg);
        ++app;
      }
      extraArgs = sb.toString().trim();
    }
    return extraArgs;
  }

  protected String createCloudExampleCollection(
      int numNodes, Scanner readInput, boolean prompt, String solrUrl) throws Exception {
    // yay! numNodes SolrCloud nodes running
    int numShards = 2;
    int replicationFactor = 2;
    String cloudConfig = "_default";
    String collectionName = "gettingstarted";

    File configsetsDir = new File(serverDir, "solr/configsets");

    if (prompt) {
      echo(
          "\nNow let's create a new collection for indexing documents in your "
              + numNodes
              + "-node cluster.");

      while (true) {
        collectionName =
            prompt(
                readInput,
                "Please provide a name for your new collection: [" + collectionName + "] ",
                collectionName);

        // Test for existence and then prompt to either create another collection or skip the
        // creation step
        if (SolrCLI.safeCheckCollectionExists(solrUrl, collectionName)) {
          echo("\nCollection '" + collectionName + "' already exists!");
          int oneOrTwo =
              promptForInt(
                  readInput,
                  "Do you want to re-use the existing collection or create a new one? Enter 1 to reuse, 2 to create new [1]: ",
                  "a 1 or 2",
                  1,
                  1,
                  2);
          if (oneOrTwo == 1) {
            return collectionName;
          } else {
            continue;
          }
        } else {
          break; // user selected a collection that doesn't exist ... proceed on
        }
      }

      numShards =
          promptForInt(
              readInput,
              "How many shards would you like to split " + collectionName + " into? [2]",
              "a shard count",
              2,
              1,
              4);

      replicationFactor =
          promptForInt(
              readInput,
              "How many replicas per shard would you like to create? [2] ",
              "a replication factor",
              2,
              1,
              4);

      echo(
          "Please choose a configuration for the "
              + collectionName
              + " collection, available options are:");
      String validConfigs = "_default or sample_techproducts_configs [" + cloudConfig + "] ";
      cloudConfig = prompt(readInput, validConfigs, cloudConfig);

      // validate the cloudConfig name
      while (!isValidConfig(configsetsDir, cloudConfig)) {
        echo(
            cloudConfig
                + " is not a valid configuration directory! Please choose a configuration for the "
                + collectionName
                + " collection, available options are:");
        cloudConfig = prompt(readInput, validConfigs, cloudConfig);
      }
    } else {
      // must verify if default collection exists
      if (SolrCLI.safeCheckCollectionExists(solrUrl, collectionName)) {
        echo(
            "\nCollection '"
                + collectionName
                + "' already exists! Skipping collection creation step.");
        return collectionName;
      }
    }

    // invoke the CreateTool
    String[] createArgs =
        new String[] {
          "--name", collectionName,
          "--shards", String.valueOf(numShards),
          "--replication-factor", String.valueOf(replicationFactor),
          "--conf-name", collectionName,
          "--conf-dir", cloudConfig,
          "--solr-url", solrUrl
        };

    CreateTool createTool = new CreateTool(stdout);
    int createCode = createTool.runTool(SolrCLI.processCommandLineArgs(createTool, createArgs));

    if (createCode != 0)
      throw new Exception(
          "Failed to create collection using command: " + Arrays.asList(createArgs));

    return collectionName;
  }

  protected boolean isValidConfig(File configsetsDir, String config) {
    File configDir = new File(configsetsDir, config);
    if (configDir.isDirectory()) return true;

    // not a built-in configset ... maybe it's a custom directory?
    configDir = new File(config);
    return configDir.isDirectory();
  }

  protected Map<String, Object> getNodeStatus(String solrUrl, int maxWaitSecs, CommandLine cli)
      throws Exception {
    StatusTool statusTool = new StatusTool();
    echoIfVerbose("\nChecking status of Solr at " + solrUrl + " ...", cli);

    URI solrURI = new URI(solrUrl);
    Map<String, Object> nodeStatus =
        statusTool.waitToSeeSolrUp(solrUrl, maxWaitSecs, TimeUnit.SECONDS);
    nodeStatus.put("baseUrl", solrUrl);
    CharArr arr = new CharArr();
    new JSONWriter(arr, 2).write(nodeStatus);
    String mode = (nodeStatus.get("cloud") != null) ? "cloud" : "standalone";

    echoIfVerbose(
        "\nSolr is running on " + solrURI.getPort() + " in " + mode + " mode with status:\n" + arr,
        cli);

    return nodeStatus;
  }

  protected File setupSolrHomeDir(File serverDir, File solrHomeParentDir, String dirName)
      throws IOException {
    File solrXml = new File(serverDir, "solr/solr.xml");
    if (!solrXml.isFile())
      throw new IllegalArgumentException(
          "Value of --server-dir option is invalid! " + solrXml.getAbsolutePath() + " not found!");

    File zooCfg = new File(serverDir, "solr/zoo.cfg");
    if (!zooCfg.isFile())
      throw new IllegalArgumentException(
          "Value of --server-dir option is invalid! " + zooCfg.getAbsolutePath() + " not found!");

    File solrHomeDir = new File(solrHomeParentDir, dirName + "/solr");
    if (!solrHomeDir.isDirectory()) {
      echo("Creating Solr home directory " + solrHomeDir);
      solrHomeDir.mkdirs();
    } else {
      echo("Solr home directory " + solrHomeDir.getAbsolutePath() + " already exists.");
    }

    copyIfNeeded(solrXml, new File(solrHomeDir, "solr.xml"));
    copyIfNeeded(zooCfg, new File(solrHomeDir, "zoo.cfg"));

    return solrHomeDir.getParentFile();
  }

  protected void copyIfNeeded(File src, File dest) throws IOException {
    if (!dest.isFile()) Files.copy(src.toPath(), dest.toPath());

    if (!dest.isFile())
      throw new IllegalStateException("Required file " + dest.getAbsolutePath() + " not found!");
  }

  protected boolean isPortAvailable(int port) {
    try (Socket s = new Socket("localhost", port)) {
      assert s != null; // To allow compilation..
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  protected Integer promptForPort(Scanner s, int node, String prompt, Integer defVal) {
    return promptForInt(s, prompt, "a port for node " + node, defVal, null, null);
  }

  protected Integer promptForInt(
      Scanner s, String prompt, String label, Integer defVal, Integer min, Integer max) {
    Integer inputAsInt = null;

    String value = prompt(s, prompt, null /* default is null since we handle that here */);
    if (value != null) {
      int attempts = 3;
      while (value != null && --attempts > 0) {
        try {
          inputAsInt = Integer.valueOf(value);

          if (min != null) {
            if (inputAsInt < min) {
              value =
                  prompt(
                      s,
                      String.format(
                          Locale.ROOT,
                          PROMPT_NUMBER_TOO_SMALL,
                          inputAsInt,
                          label,
                          min,
                          max,
                          defVal));
              inputAsInt = null;
              continue;
            }
          }

          if (max != null) {
            if (inputAsInt > max) {
              value =
                  prompt(
                      s,
                      String.format(
                          Locale.ROOT,
                          PROMPT_NUMBER_TOO_LARGE,
                          inputAsInt,
                          label,
                          min,
                          max,
                          defVal));
              inputAsInt = null;
            }
          }

        } catch (NumberFormatException nfe) {
          if (verbose) echo(value + " is not a number!");

          if (min != null && max != null) {
            value =
                prompt(
                    s,
                    String.format(
                        Locale.ROOT, PROMPT_FOR_NUMBER_IN_RANGE, label, min, max, defVal));
          } else {
            value = prompt(s, String.format(Locale.ROOT, PROMPT_FOR_NUMBER, label, defVal));
          }
        }
      }
      if (attempts == 0 && inputAsInt == null)
        echo("Too many failed attempts! Going with default value " + defVal);
    }

    return (inputAsInt != null) ? inputAsInt : defVal;
  }

  protected String prompt(Scanner s, String prompt) {
    return prompt(s, prompt, null);
  }

  protected String prompt(Scanner s, String prompt, String defaultValue) {
    echo(prompt);
    String nextInput = s.nextLine();
    if (nextInput != null) {
      nextInput = nextInput.trim();
      if (nextInput.isEmpty()) nextInput = null;
    }
    return (nextInput != null) ? nextInput : defaultValue;
  }
}
