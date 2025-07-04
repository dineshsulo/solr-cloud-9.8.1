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
package org.apache.solr.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Backups do checksum validation against a footer value not present in 'SimpleText'
@LuceneTestCase.SuppressCodecs({"SimpleText"})
@SolrTestCaseJ4.SuppressSSL // Currently, unknown why SSL does not work with this test
public class TestReplicationHandlerBackup extends SolrJettyTestBase {

  JettySolrRunner leaderJetty;
  ReplicationTestHelper.SolrInstance leader = null;
  SolrClient leaderClient;

  private static final String CONF_DIR =
      "solr" + File.separator + "collection1" + File.separator + "conf" + File.separator;

  private static String context = "/solr";

  boolean addNumberToKeepInRequest = true;
  String backupKeepParamName = ReplicationHandler.NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM;
  private static long docsSeed; // see indexDocs()
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static JettySolrRunner createAndStartJetty(ReplicationTestHelper.SolrInstance instance)
      throws Exception {
    FileUtils.copyFile(
        new File(SolrTestCaseJ4.TEST_HOME(), "solr.xml"),
        new File(instance.getHomeDir(), "solr.xml"));
    Properties nodeProperties = new Properties();
    nodeProperties.setProperty("solr.data.dir", instance.getDataDir());
    JettyConfig jettyConfig = JettyConfig.builder().setPort(0).build();
    JettySolrRunner jetty = new JettySolrRunner(instance.getHomeDir(), nodeProperties, jettyConfig);
    jetty.start();
    return jetty;
  }

  private static SolrClient createNewSolrClient(int port) {
    final String baseUrl = buildUrl(port, context);
    return new HttpSolrClient.Builder(baseUrl)
        .withConnectionTimeout(15000, TimeUnit.MILLISECONDS)
        .withSocketTimeout(60000, TimeUnit.MILLISECONDS)
        .build();
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    String configFile = "solrconfig-leader1.xml";

    if (random().nextBoolean()) {
      configFile = "solrconfig-leader1-keepOneBackup.xml";
      addNumberToKeepInRequest = false;
      backupKeepParamName = ReplicationHandler.NUMBER_BACKUPS_TO_KEEP_INIT_PARAM;
    }
    leader =
        new ReplicationTestHelper.SolrInstance(
            createTempDir("solr-instance").toFile(), "leader", null);
    leader.setUp();
    leader.copyConfigFile(CONF_DIR + configFile, "solrconfig.xml");

    leaderJetty = createAndStartJetty(leader);
    leaderClient = createNewSolrClient(leaderJetty.getLocalPort());
    docsSeed = random().nextLong();
  }

  @Override
  @After
  public void tearDown() throws Exception {
    super.tearDown();
    if (null != leaderClient) {
      leaderClient.close();
      leaderClient = null;
    }
    if (null != leaderJetty) {
      leaderJetty.stop();
      leaderJetty = null;
    }
    leader = null;
  }

  @Test
  public void testBackupOnCommit() throws Exception {
    final BackupStatusChecker backupStatus =
        new BackupStatusChecker(leaderClient, "/" + DEFAULT_TEST_CORENAME + "/replication");

    final String lastBackupDir = backupStatus.checkBackupSuccess();
    // sanity check no backups yet
    assertNull("Already have a successful backup", lastBackupDir);

    // Index
    int nDocs = BackupRestoreUtils.indexDocs(leaderClient, DEFAULT_TEST_COLLECTION_NAME, docsSeed);

    final String newBackupDir = backupStatus.waitForDifferentBackupDir(lastBackupDir, 30);
    // Validate
    verify(Paths.get(leader.getDataDir(), newBackupDir), nDocs);
  }

  private void verify(Path backup, int nDocs) throws IOException {
    log.info("Verifying ndocs={} in {}", nDocs, backup);
    try (Directory dir = new NIOFSDirectory(backup);
        IndexReader reader = DirectoryReader.open(dir)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      TopDocs hits = searcher.search(new MatchAllDocsQuery(), 1);
      assertEquals(nDocs, hits.totalHits.value);
    }
  }

  @Test
  public void doTestBackup() throws Exception {
    final BackupStatusChecker backupStatus =
        new BackupStatusChecker(leaderClient, "/" + DEFAULT_TEST_CORENAME + "/replication");

    String lastBackupDir = backupStatus.checkBackupSuccess();
    assertNull("Already have a successful backup", lastBackupDir);

    final Path[] snapDir = new Path[5]; // One extra for the backup on commit
    // First snapshot location

    int nDocs = BackupRestoreUtils.indexDocs(leaderClient, DEFAULT_TEST_COLLECTION_NAME, docsSeed);

    lastBackupDir = backupStatus.waitForDifferentBackupDir(lastBackupDir, 30);
    snapDir[0] = Paths.get(leader.getDataDir(), lastBackupDir);

    final boolean namedBackup = random().nextBoolean();

    String[] backupNames = null;
    if (namedBackup) {
      backupNames = new String[4];
    }
    for (int i = 0; i < 4; i++) {
      final String backupName = TestUtil.randomSimpleString(random(), 1, 20) + "_" + i;
      if (!namedBackup) {
        if (addNumberToKeepInRequest) {
          runBackupCommand(
              leaderJetty, ReplicationHandler.CMD_BACKUP, "&" + backupKeepParamName + "=2");
        } else {
          runBackupCommand(leaderJetty, ReplicationHandler.CMD_BACKUP, "");
        }
        lastBackupDir = backupStatus.waitForDifferentBackupDir(lastBackupDir, 30);
      } else {
        runBackupCommand(leaderJetty, ReplicationHandler.CMD_BACKUP, "&name=" + backupName);
        lastBackupDir = backupStatus.waitForBackupSuccess(backupName, 30);
        backupNames[i] = backupName;
      }
      snapDir[i + 1] = Paths.get(leader.getDataDir(), lastBackupDir);
      verify(snapDir[i + 1], nDocs);
    }

    // Test Deletion of named backup
    if (namedBackup) {
      testDeleteNamedBackup(backupNames);
    } else {
      // 5 backups got created. 4 explicitly and one because a commit was called.
      // Only the last two should still exist.
      final List<String> remainingBackups = new ArrayList<>();

      try (DirectoryStream<Path> stream =
          Files.newDirectoryStream(Paths.get(leader.getDataDir()), "snapshot*")) {
        Iterator<Path> iter = stream.iterator();
        while (iter.hasNext()) {
          remainingBackups.add(iter.next().getFileName().toString());
        }
      }

      // Depending on the use of backupKeepParamName there should either be 2 or 1 backups remaining
      if (backupKeepParamName.equals(ReplicationHandler.NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM)) {
        assertEquals(remainingBackups.toString(), 2, remainingBackups.size());

        if (Files.exists(snapDir[0]) || Files.exists(snapDir[1]) || Files.exists(snapDir[2])) {
          fail(
              "Backup should have been cleaned up because "
                  + backupKeepParamName
                  + " was set to 2.");
        }
      } else {
        assertEquals(remainingBackups.toString(), 1, remainingBackups.size());

        if (Files.exists(snapDir[0])
            || Files.exists(snapDir[1])
            || Files.exists(snapDir[2])
            || Files.exists(snapDir[3])) {
          fail(
              "Backup should have been cleaned up because "
                  + backupKeepParamName
                  + " was set to 1.");
        }
      }
    }
  }

  private void testDeleteNamedBackup(String backupNames[]) throws Exception {
    final BackupStatusChecker backupStatus =
        new BackupStatusChecker(leaderClient, "/" + DEFAULT_TEST_CORENAME + "/replication");
    for (int i = 0; i < 2; i++) {
      final Path p = Paths.get(leader.getDataDir(), "snapshot." + backupNames[i]);
      assertTrue("WTF: Backup doesn't exist: " + p, Files.exists(p));
      runBackupCommand(
          leaderJetty, ReplicationHandler.CMD_DELETE_BACKUP, "&name=" + backupNames[i]);
      backupStatus.waitForBackupDeletionSuccess(backupNames[i], 30);
      assertFalse("backup still exists after deletion: " + p, Files.exists(p));
    }
  }

  public static void runBackupCommand(JettySolrRunner leaderJetty, String cmd, String params)
      throws IOException {
    String leaderUrl =
        buildUrl(leaderJetty.getLocalPort(), context)
            + "/"
            + DEFAULT_TEST_CORENAME
            + ReplicationHandler.PATH
            + "?wt=xml&command="
            + cmd
            + params;
    URL url = URI.create(leaderUrl).toURL();
    try (InputStream stream = url.openStream()) {
      assert stream != null;
    }
  }
}
