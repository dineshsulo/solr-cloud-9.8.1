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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteResultHandler;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.ExternalPaths;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tests the SolrCLI.RunExampleTool implementation that supports bin/solr start -e [example] */
@SolrTestCaseJ4.SuppressSSL(bugUrl = "https://issues.apache.org/jira/browse/SOLR-5776")
public class TestSolrCLIRunExample extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @BeforeClass
  public static void beforeClass() {
    assumeFalse(
        "FIXME: This test does not work with whitespace in CWD (https://issues.apache.org/jira/browse/SOLR-8877)",
        Paths.get(".").toAbsolutePath().toString().contains(" "));
    // to be true
    System.setProperty("solr.directoryFactory", "solr.NRTCachingDirectoryFactory");
  }

  @AfterClass
  public static void cleanupDirectoryFactory() {
    System.clearProperty("solr.directoryFactory");
  }

  /**
   * Overrides the call to exec bin/solr to start Solr nodes to start them using the Solr
   * test-framework instead of the script, since the script depends on a full build.
   */
  private static class RunExampleExecutor extends DefaultExecutor implements Closeable {

    private PrintStream stdout;
    private final List<CommandLine> commandsExecuted = new ArrayList<>();
    private MiniSolrCloudCluster solrCloudCluster;
    private JettySolrRunner standaloneSolr;

    RunExampleExecutor(PrintStream stdout) {
      super();
      this.stdout = stdout;
    }

    /**
     * Override the call to execute a command asynchronously to occur synchronously during a unit
     * test.
     */
    @Override
    public void execute(
        org.apache.commons.exec.CommandLine cmd, Map<String, String> env, ExecuteResultHandler erh)
        throws IOException {
      int code = execute(cmd);
      if (code != 0)
        throw new RuntimeException("Failed to execute cmd: " + joinArgs(cmd.getArguments()));
    }

    @Override
    public int execute(org.apache.commons.exec.CommandLine cmd) throws IOException {
      // collect the commands as they are executed for analysis by the test
      commandsExecuted.add(cmd);

      String exe = cmd.getExecutable();
      if (exe.endsWith("solr")) {
        String[] args = cmd.getArguments();
        if ("start".equals(args[0])) {
          if (!hasFlag("--cloud", args) && !hasFlag("-c", args)) {
            return startStandaloneSolr(args);
          }

          String solrHomeDir = getArg("--solr-home", args);
          int port = Integer.parseInt(getArg("-p", args));
          Path solrXmlPath = Paths.get(solrHomeDir).resolve("solr.xml");
          if (!Files.exists(solrXmlPath)) {
            String solrServerDir = getArg("--server-dir", args);
            solrXmlPath = Paths.get(solrServerDir).resolve("solr").resolve("solr.xml");
          }
          String solrxml = Files.readString(solrXmlPath, Charset.defaultCharset());

          JettyConfig jettyConfig = JettyConfig.builder().setPort(port).build();
          try {
            if (solrCloudCluster == null) {
              Path logDir = createTempDir("solr_logs");
              System.setProperty("solr.log.dir", logDir.toString());
              System.setProperty("host", "localhost");
              System.setProperty("jetty.port", String.valueOf(port));
              solrCloudCluster = new MiniSolrCloudCluster(1, createTempDir(), solrxml, jettyConfig);
            } else {
              // another member of this cluster -- not supported yet, due to how
              // MiniSolrCloudCluster works
              throw new IllegalArgumentException(
                  "Only launching one SolrCloud node is supported by this test!");
            }
          } catch (Exception e) {
            if (e instanceof RuntimeException) {
              throw (RuntimeException) e;
            } else {
              throw new RuntimeException(e);
            }
          }
        } else if ("stop".equals(args[0])) {

          int port = Integer.parseInt(getArg("-p", args));

          // stop the requested node
          if (standaloneSolr != null) {
            int localPort = standaloneSolr.getLocalPort();
            if (port == localPort) {
              try {
                standaloneSolr.stop();
                log.info("Stopped standalone Solr instance running on port {}", port);
              } catch (Exception e) {
                if (e instanceof RuntimeException) {
                  throw (RuntimeException) e;
                } else {
                  throw new RuntimeException(e);
                }
              }
            } else {
              throw new IllegalArgumentException("No Solr is running on port " + port);
            }
          } else {
            if (solrCloudCluster != null) {
              try {
                solrCloudCluster.shutdown();
                log.info("Stopped SolrCloud test cluster");
              } catch (Exception e) {
                if (e instanceof RuntimeException) {
                  throw (RuntimeException) e;
                } else {
                  throw new RuntimeException(e);
                }
              }
            } else {
              throw new IllegalArgumentException("No Solr nodes found to stop!");
            }
          }
        }
      } else {
        String cmdLine = joinArgs(cmd.getArguments());
        log.info("Executing command: {}", cmdLine);
        try {
          return super.execute(cmd);
        } catch (Exception exc) {
          log.error("Execute command [{}] failed due to: {}", cmdLine, exc, exc);
          throw exc;
        }
      }

      return 0;
    }

    protected String joinArgs(String[] args) {
      if (args == null || args.length == 0) return "";

      StringBuilder sb = new StringBuilder();
      for (int a = 0; a < args.length; a++) {
        if (a > 0) sb.append(' ');
        sb.append(args[a]);
      }
      return sb.toString();
    }

    protected int startStandaloneSolr(String[] args) {

      if (standaloneSolr != null) {
        throw new IllegalStateException(
            "Test is already running a standalone Solr instance "
                + standaloneSolr.getBaseUrl()
                + "! This indicates a bug in the unit test logic.");
      }

      if (solrCloudCluster != null) {
        throw new IllegalStateException(
            "Test is already running a mini SolrCloud cluster! "
                + "This indicates a bug in the unit test logic.");
      }

      int port = Integer.parseInt(getArg("-p", args));

      File solrHomeDir = new File(getArg("--solr-home", args));

      System.setProperty("host", "localhost");
      System.setProperty("jetty.port", String.valueOf(port));
      System.setProperty("solr.log.dir", createTempDir("solr_logs").toString());

      standaloneSolr = new JettySolrRunner(solrHomeDir.getAbsolutePath(), "/solr", port);
      Thread bg =
          new Thread() {
            @Override
            public void run() {
              try {
                standaloneSolr.start();
              } catch (Exception e) {
                if (e instanceof RuntimeException) {
                  throw (RuntimeException) e;
                } else {
                  throw new RuntimeException(e);
                }
              }
            }
          };
      bg.start();

      return 0;
    }

    protected String getArg(String arg, String[] args) {
      for (int a = 0; a < args.length; a++) {
        if (arg.equals(args[a])) {
          if (a + 1 >= args.length)
            throw new IllegalArgumentException(
                "Missing required value for the " + arg + " option!");

          return args[a + 1];
        }
      }
      throw new IllegalArgumentException(
          "Missing required arg "
              + arg
              + " needed to execute command: "
              + commandsExecuted.get(commandsExecuted.size() - 1));
    }

    protected boolean hasFlag(String flag, String[] args) {
      for (String arg : args) {
        if (flag.equals(arg)) return true;
      }
      return false;
    }

    @Override
    public void close() {
      if (solrCloudCluster != null) {
        try {
          solrCloudCluster.shutdown();
        } catch (Exception e) {
          log.warn("Failed to shutdown MiniSolrCloudCluster due to: ", e);
        }
      }

      if (standaloneSolr != null) {
        try {
          standaloneSolr.stop();
        } catch (Exception exc) {
          log.warn("Failed to shutdown standalone Solr due to: ", exc);
        }
        standaloneSolr = null;
      }
    }
  }

  protected List<Closeable> closeables = new ArrayList<>();

  @Override
  @After
  public void tearDown() throws Exception {
    super.tearDown();

    if (closeables != null) {
      for (Closeable toClose : closeables) {
        try {
          toClose.close();
        } catch (Exception ignore) {
        }
      }
      closeables.clear();
      closeables = null;
    }
  }

  @Test
  public void testTechproductsExample() throws Exception {
    testExample("techproducts");
  }

  @Test
  public void testSchemalessExample() throws Exception {
    testExample("schemaless");
  }

  @Test
  public void testFilmsExample() throws Exception {
    testExample("films");
  }

  protected void testExample(String exampleName) throws Exception {
    File defaultSolrHomeDir = new File(ExternalPaths.SERVER_HOME);
    if (!defaultSolrHomeDir.isDirectory())
      fail(defaultSolrHomeDir.getAbsolutePath() + " not found and is required to run this test!");

    Path tmpDir = createTempDir();
    File testSolrHomeDir = tmpDir.toFile();
    File solrServerDir = defaultSolrHomeDir.getParentFile();

    for (int pass = 0; pass < 2; pass++) {
      // need a port to start the example server on
      int bindPort = -1;
      try (ServerSocket socket = new ServerSocket(0)) {
        bindPort = socket.getLocalPort();
      }

      log.info("Selected port {} to start {} example Solr instance on ...", bindPort, exampleName);

      String[] toolArgs =
          new String[] {
            "-e", exampleName,
            "--server-dir", solrServerDir.getAbsolutePath(),
            "--solr-home", testSolrHomeDir.getAbsolutePath(),
            "-p", String.valueOf(bindPort)
          };

      // capture tool output to stdout
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      PrintStream stdoutSim = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

      RunExampleExecutor executor = new RunExampleExecutor(stdoutSim);
      closeables.add(executor);

      RunExampleTool tool = new RunExampleTool(executor, System.in, stdoutSim);
      try {
        int status = tool.runTool(SolrCLI.processCommandLineArgs(tool, toolArgs));

        if (status == -1) {
          // maybe it's the port, try again
          try (ServerSocket socket = new ServerSocket(0)) {
            bindPort = socket.getLocalPort();
          }
          Thread.sleep(100);
          status = tool.runTool(SolrCLI.processCommandLineArgs(tool, toolArgs));
        }

        assertEquals("it should be ok " + tool + " " + Arrays.toString(toolArgs), 0, status);
      } catch (Exception e) {
        log.error(
            "RunExampleTool failed due to: {}; stdout from tool prior to failure: {}",
            e,
            baos.toString(StandardCharsets.UTF_8.name())); // nowarn
        throw e;
      }

      String toolOutput = baos.toString(StandardCharsets.UTF_8.name());

      // dump all the output written by the SolrCLI commands to stdout
      // System.out.println("\n\n"+toolOutput+"\n\n");

      if ("techproducts".equals(exampleName)) {
        try (SolrClient solrClient =
            getHttpSolrClient("http://localhost:" + bindPort + "/solr", exampleName)) {
          SolrQuery query = new SolrQuery("*:*");
          QueryResponse qr = solrClient.query(query);
          long numFound = qr.getResults().getNumFound();
          if (numFound == 0) {
            // brief wait in case of timing issue in getting the new docs committed
            log.warn(
                "Going to wait for 1 second before re-trying query for techproduct example docs ...");
            try {
              Thread.sleep(1000);
            } catch (InterruptedException ignore) {
              Thread.interrupted();
            }
            numFound = solrClient.query(query).getResults().getNumFound();
          }
          assertEquals(
              "expected 31 docs in the "
                  + exampleName
                  + " example but found "
                  + numFound
                  + ", output: "
                  + toolOutput,
              31,
              numFound);
        }
      }

      // stop the test instance
      executor.execute(org.apache.commons.exec.CommandLine.parse("bin/solr stop -p " + bindPort));
    }
  }

  /**
   * Tests the interactive SolrCloud example; we cannot test the non-interactive because we need
   * control over the port and can only test with one node since the test relies on setting the host
   * and jetty.port system properties, i.e. there is no test coverage for the --no-prompt option.
   */
  @Test
  public void testInteractiveSolrCloudExample() throws Exception {
    File solrHomeDir = new File(ExternalPaths.SERVER_HOME);
    if (!solrHomeDir.isDirectory())
      fail(solrHomeDir.getAbsolutePath() + " not found and is required to run this test!");

    Path tmpDir = createTempDir();
    File solrExampleDir = tmpDir.toFile();

    File solrServerDir = solrHomeDir.getParentFile();

    String[] toolArgs =
        new String[] {
          "--example", "cloud",
          "--server-dir", solrServerDir.getAbsolutePath(),
          "--example-dir", solrExampleDir.getAbsolutePath()
        };

    int bindPort = -1;
    try (ServerSocket socket = new ServerSocket(0)) {
      bindPort = socket.getLocalPort();
    }

    String collectionName = "testCloudExamplePrompt";

    // this test only support launching one SolrCloud node due to how MiniSolrCloudCluster works
    // and the need for setting the host and port system properties ...
    String userInput = "1\n" + bindPort + "\n" + collectionName + "\n2\n2\n_default\n";

    // simulate user input from stdin
    InputStream userInputSim = new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8));

    // capture tool output to stdout
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream stdoutSim = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

    RunExampleExecutor executor = new RunExampleExecutor(stdoutSim);
    closeables.add(executor);

    RunExampleTool tool = new RunExampleTool(executor, userInputSim, stdoutSim);
    try {
      tool.runTool(SolrCLI.processCommandLineArgs(tool, toolArgs));
    } catch (Exception e) {
      System.err.println(
          "RunExampleTool failed due to: "
              + e
              + "; stdout from tool prior to failure: "
              + baos.toString(StandardCharsets.UTF_8.name()));
      throw e;
    }

    String toolOutput = baos.toString(StandardCharsets.UTF_8.name());

    // verify Solr is running on the expected port and verify the collection exists
    String solrUrl = "http://localhost:" + bindPort + "/solr";
    if (!SolrCLI.safeCheckCollectionExists(solrUrl, collectionName)) {
      fail(
          "After running Solr cloud example, test collection '"
              + collectionName
              + "' not found in Solr at: "
              + solrUrl
              + "; tool output: "
              + toolOutput);
    }

    // index some docs - to verify all is good for both shards
    try (CloudSolrClient cloudClient =
        new RandomizingCloudSolrClientBuilder(
                Collections.singletonList(executor.solrCloudCluster.getZkServer().getZkAddress()),
                Optional.empty())
            .withDefaultCollection(collectionName)
            .build()) {

      int numDocs = 10;
      for (int d = 0; d < numDocs; d++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", "doc" + d);
        doc.setField("str_s", "a");
        cloudClient.add(doc);
      }
      cloudClient.commit();

      QueryResponse qr = cloudClient.query(new SolrQuery("str_s:a"));
      if (qr.getResults().getNumFound() != numDocs) {
        fail(
            "Expected "
                + numDocs
                + " to be found in the "
                + collectionName
                + " collection but only found "
                + qr.getResults().getNumFound());
      }
    }

    File node1SolrHome = new File(solrExampleDir, "cloud/node1/solr");
    if (!node1SolrHome.isDirectory()) {
      fail(
          node1SolrHome.getAbsolutePath()
              + " not found! run cloud example failed; tool output: "
              + toolOutput);
    }

    // delete the collection
    DeleteTool deleteTool = new DeleteTool(stdoutSim);
    String[] deleteArgs = new String[] {"--name", collectionName, "--solr-url", solrUrl};
    deleteTool.runTool(SolrCLI.processCommandLineArgs(deleteTool, deleteArgs));

    // dump all the output written by the SolrCLI commands to stdout
    // System.out.println(toolOutput);

    // stop the test instance
    executor.execute(org.apache.commons.exec.CommandLine.parse("bin/solr stop -p " + bindPort));
  }

  @Test
  public void testFailExecuteScript() throws Exception {
    File solrHomeDir = new File(ExternalPaths.SERVER_HOME);
    if (!solrHomeDir.isDirectory())
      fail(solrHomeDir.getAbsolutePath() + " not found and is required to run this test!");

    Path tmpDir = createTempDir();
    File solrExampleDir = tmpDir.toFile();
    File solrServerDir = solrHomeDir.getParentFile();

    // need a port to start the example server on
    int bindPort = -1;
    try (ServerSocket socket = new ServerSocket(0)) {
      bindPort = socket.getLocalPort();
    }

    File toExecute = new File(tmpDir.toString(), "failExecuteScript");
    assertTrue(
        "Should have been able to create file '" + toExecute.getAbsolutePath() + "' ",
        toExecute.createNewFile());

    String[] toolArgs =
        new String[] {
          "-e", "techproducts",
          "--server-dir", solrServerDir.getAbsolutePath(),
          "--example-dir", solrExampleDir.getAbsolutePath(),
          "-p", String.valueOf(bindPort),
          "--script", toExecute.getAbsolutePath()
        };

    // capture tool output to stdout
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream stdoutSim = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

    DefaultExecutor executor = DefaultExecutor.builder().get();

    RunExampleTool tool = new RunExampleTool(executor, System.in, stdoutSim);
    int code = tool.runTool(SolrCLI.processCommandLineArgs(tool, toolArgs));
    assertEquals("Execution should have failed with return code 1", 1, code);
  }
}
