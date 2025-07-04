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

import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.CHECKSUM;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.COMPRESSION;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.CONF_FILE_SHORT;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.FILE;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.GENERATION;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.INTERVAL_ERR_MSG;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.INTERVAL_PATTERN;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.LEN;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.MAX_WRITE_PER_SECOND;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.OFFSET;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.STATUS;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.TLOG_FILE;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexDeletionPolicy;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.solr.api.JerseyResource;
import org.apache.solr.client.api.model.FileMetaData;
import org.apache.solr.client.api.model.IndexVersionResponse;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.DirectoryFactory.DirContext;
import org.apache.solr.core.IndexDeletionPolicyWrapper;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrDeletionPolicy;
import org.apache.solr.core.SolrEventListener;
import org.apache.solr.core.backup.repository.BackupRepository;
import org.apache.solr.core.backup.repository.LocalFileSystemRepository;
import org.apache.solr.handler.IndexFetcher.IndexFetchResult;
import org.apache.solr.handler.ReplicationHandler.ReplicationHandlerConfig;
import org.apache.solr.handler.admin.api.CoreReplication;
import org.apache.solr.handler.admin.api.ReplicationAPIBase;
import org.apache.solr.handler.admin.api.SnapshotBackupAPI;
import org.apache.solr.handler.api.V2ApiUtils;
import org.apache.solr.jersey.APIConfigProvider;
import org.apache.solr.metrics.MetricsMap;
import org.apache.solr.metrics.SolrMetricsContext;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.update.SolrIndexWriter;
import org.apache.solr.util.NumberUtils;
import org.apache.solr.util.PropertiesInputStream;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A Handler which provides a REST API for replication and serves replication requests from
 * Followers.
 *
 * <p>When running on the leader, it provides the following commands
 *
 * <ol>
 *   <li>Get the current replicable index version (command=indexversion)
 *   <li>Get the list of files for a given index version
 *       (command=filelist&amp;indexversion=&lt;VERSION&gt;)
 *   <li>Get full or a part (chunk) of a given index or a config file
 *       (command=filecontent&amp;file=&lt;FILE_NAME&gt;) You can optionally specify an offset and
 *       length to get that chunk of the file. You can request a configuration file by using "cf"
 *       parameter instead of the "file" parameter.
 *   <li>Get status/statistics (command=details)
 * </ol>
 *
 * <p>When running on the follower, it provides the following commands
 *
 * <ol>
 *   <li>Perform an index fetch now (command=snappull)
 *   <li>Get status/statistics (command=details)
 *   <li>Abort an index fetch (command=abort)
 *   <li>Enable/Disable polling the leader for new versions (command=enablepoll or
 *       command=disablepoll)
 * </ol>
 *
 * @since solr 1.4
 */
public class ReplicationHandler extends RequestHandlerBase
    implements SolrCoreAware, APIConfigProvider<ReplicationHandlerConfig> {

  public static final String PATH = "/replication";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  SolrCore core;

  private volatile boolean closed = false;

  @Override
  public Name getPermissionName(AuthorizationContext request) {
    return Name.READ_PERM;
  }

  private static final class CommitVersionInfo {
    public final long version;
    public final long generation;

    private CommitVersionInfo(long g, long v) {
      generation = g;
      version = v;
    }

    /**
     * builds a CommitVersionInfo data for the specified IndexCommit. Will never be null, ut version
     * and generation may be zero if there are problems extracting them from the commit data
     */
    public static CommitVersionInfo build(IndexCommit commit) {
      long generation = commit.getGeneration();
      long version = 0;
      try {
        final Map<String, String> commitData = commit.getUserData();
        String commitTime = commitData.get(SolrIndexWriter.COMMIT_TIME_MSEC_KEY);
        if (commitTime != null) {
          try {
            version = Long.parseLong(commitTime);
          } catch (NumberFormatException e) {
            log.warn("Version in commitData was not formatted correctly: {}", commitTime, e);
          }
        }
      } catch (IOException e) {
        log.warn("Unable to get version from commitData, commit: {}", commit, e);
      }
      return new CommitVersionInfo(generation, version);
    }

    @Override
    public String toString() {
      return "generation=" + generation + ",version=" + version;
    }
  }

  private IndexFetcher pollingIndexFetcher;

  private ReentrantLock indexFetchLock = new ReentrantLock();

  private ExecutorService restoreExecutor =
      ExecutorUtil.newMDCAwareSingleThreadExecutor(new SolrNamedThreadFactory("restoreExecutor"));

  private volatile Future<Boolean> restoreFuture;

  private volatile String currentRestoreName;

  private String includeConfFiles;

  private NamedList<String> confFileNameAlias = new NamedList<>();

  private boolean isLeader = false;

  private boolean isFollower = false;

  private boolean replicateOnOptimize = false;

  private boolean replicateOnCommit = false;

  private boolean replicateOnStart = false;

  private volatile ScheduledExecutorService executorService;

  private volatile long executorStartTime;

  private int numTimesReplicated = 0;

  private final Map<String, FileInfo> confFileInfoCache = new HashMap<>();

  private Long reserveCommitDuration = readIntervalMs("00:00:10");

  volatile IndexCommit indexCommitPoint;

  volatile NamedList<?> snapShootDetails;

  private AtomicBoolean replicationEnabled = new AtomicBoolean(true);

  private Long pollIntervalNs;
  private String pollIntervalStr;

  private PollListener pollListener;

  private final ReplicationHandlerConfig replicationHandlerConfig = new ReplicationHandlerConfig();

  public interface PollListener {
    void onComplete(SolrCore solrCore, IndexFetchResult fetchResult) throws IOException;
  }

  /** Disable the timer task for polling */
  private AtomicBoolean pollDisabled = new AtomicBoolean(false);

  String getPollInterval() {
    return pollIntervalStr;
  }

  public void setPollListener(PollListener pollListener) {
    this.pollListener = pollListener;
  }

  public boolean isFollower() {
    return this.isFollower;
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    rsp.setHttpCaching(false);
    final SolrParams solrParams = req.getParams();
    String command = solrParams.required().get(COMMAND);

    // This command does not give the current index version of the leader
    // It gives the current 'replicateable' index version
    if (command.equals(CMD_INDEX_VERSION)) {
      final SolrJerseyResponse indexVersionResponse = getIndexVersionResponse();
      V2ApiUtils.squashIntoSolrResponseWithoutHeader(rsp, indexVersionResponse);
    } else if (command.equals(CMD_GET_FILE)) {
      getFileStream(solrParams, rsp, req);
    } else if (command.equals(CMD_GET_FILE_LIST)) {
      final CoreReplication coreReplicationAPI = new CoreReplication(core, req, rsp);
      V2ApiUtils.squashIntoSolrResponseWithoutHeader(
          rsp,
          coreReplicationAPI.fetchFileList(Long.parseLong(solrParams.required().get(GENERATION))));
    } else if (command.equalsIgnoreCase(CMD_BACKUP)) {
      doSnapShoot(new ModifiableSolrParams(solrParams), rsp, req);
    } else if (command.equalsIgnoreCase(CMD_RESTORE)) {
      restore(new ModifiableSolrParams(solrParams), rsp, req);
    } else if (command.equalsIgnoreCase(CMD_RESTORE_STATUS)) {
      populateRestoreStatus(rsp);
    } else if (command.equalsIgnoreCase(CMD_DELETE_BACKUP)) {
      deleteSnapshot(new ModifiableSolrParams(solrParams), rsp);
    } else if (command.equalsIgnoreCase(CMD_FETCH_INDEX)) {
      fetchIndex(solrParams, rsp);
    } else if (command.equalsIgnoreCase(CMD_DISABLE_POLL)) {
      disablePoll(rsp);
    } else if (command.equalsIgnoreCase(CMD_ENABLE_POLL)) {
      enablePoll(rsp);
    } else if (command.equalsIgnoreCase(CMD_ABORT_FETCH)) {
      if (abortFetch()) {
        rsp.add(STATUS, OK_STATUS);
      } else {
        reportErrorOnResponse(rsp, "No follower configured", null);
      }
    } else if (command.equals(CMD_SHOW_COMMITS)) {
      populateCommitInfo(rsp);
    } else if (command.equals(CMD_DETAILS)) {
      getReplicationDetails(
          rsp, getBoolWithBackwardCompatibility(solrParams, "follower", "slave", true));
    } else if (CMD_ENABLE_REPL.equalsIgnoreCase(command)) {
      replicationEnabled.set(true);
      rsp.add(STATUS, OK_STATUS);
    } else if (CMD_DISABLE_REPL.equalsIgnoreCase(command)) {
      replicationEnabled.set(false);
      rsp.add(STATUS, OK_STATUS);
    }
  }

  /**
   * This method adds an Object of FileStream to the response . The FileStream implements a custom
   * protocol which is understood by IndexFetcher.FileFetcher
   *
   * @see IndexFetcher.LocalFsFileFetcher
   * @see IndexFetcher.DirectoryFileFetcher
   */
  private void getFileStream(SolrParams solrParams, SolrQueryResponse rsp, SolrQueryRequest req)
      throws IOException {
    final CoreReplication coreReplicationAPI = new CoreReplication(core, req, rsp);
    String fileName;
    String dirType;

    if (solrParams.get(CONF_FILE_SHORT) != null) {
      fileName = solrParams.get(CONF_FILE_SHORT);
      dirType = CONF_FILE_SHORT;
    } else if (solrParams.get(TLOG_FILE) != null) {
      fileName = solrParams.get(TLOG_FILE);
      dirType = TLOG_FILE;
    } else if (solrParams.get(FILE) != null) {
      fileName = solrParams.get(FILE);
      dirType = FILE;
    } else {
      reportErrorOnResponse(
          rsp,
          "Missing file parameter",
          new SolrException(SolrException.ErrorCode.BAD_REQUEST, "File not specified in request"));
      return;
    }

    coreReplicationAPI.fetchFile(
        fileName,
        dirType,
        solrParams.get(OFFSET),
        solrParams.get(LEN),
        Boolean.parseBoolean(solrParams.get(COMPRESSION)),
        solrParams.getBool(CHECKSUM, false),
        solrParams.getDouble(MAX_WRITE_PER_SECOND, Double.MAX_VALUE),
        solrParams.getLong(GENERATION));
  }

  static boolean getBoolWithBackwardCompatibility(
      SolrParams params, String preferredKey, String alternativeKey, boolean defaultValue) {
    Boolean value = params.getBool(preferredKey);
    if (value != null) {
      return value;
    }
    return params.getBool(alternativeKey, defaultValue);
  }

  @SuppressWarnings("unchecked")
  static <T> T getObjectWithBackwardCompatibility(
      SolrParams params, String preferredKey, String alternativeKey, T defaultValue) {
    Object value = params.get(preferredKey);
    if (value != null) {
      return (T) value;
    }
    value = params.get(alternativeKey);
    if (value != null) {
      return (T) value;
    }
    return defaultValue;
  }

  @SuppressWarnings("unchecked")
  public static <T> T getObjectWithBackwardCompatibility(
      NamedList<?> params, String preferredKey, String alternativeKey) {
    Object value = params.get(preferredKey);
    if (value != null) {
      return (T) value;
    }
    return (T) params.get(alternativeKey);
  }

  private void reportErrorOnResponse(SolrQueryResponse response, String message, Exception e) {
    response.add(STATUS, ERR_STATUS);
    response.add(MESSAGE, message);
    if (e != null) {
      response.add(EXCEPTION, e);
    }
  }

  public boolean abortFetch() {
    IndexFetcher fetcher = currentIndexFetcher;
    if (fetcher != null) {
      fetcher.abortFetch();
      return true;
    } else {
      return false;
    }
  }

  @SuppressWarnings("deprecation")
  private void deleteSnapshot(ModifiableSolrParams params, SolrQueryResponse rsp) {
    params.required().get(NAME);

    String location = params.get(CoreAdminParams.BACKUP_LOCATION);
    core.getCoreContainer().assertPathAllowed(location == null ? null : Path.of(location));
    SnapShooter snapShooter = new SnapShooter(core, location, params.get(NAME));
    snapShooter.validateDeleteSnapshot();
    snapShooter.deleteSnapAsync(this);
    rsp.add(STATUS, OK_STATUS);
  }

  private void fetchIndex(SolrParams solrParams, SolrQueryResponse rsp)
      throws InterruptedException {
    String leaderUrl =
        getObjectWithBackwardCompatibility(solrParams, LEADER_URL, LEGACY_LEADER_URL, null);
    if (!isFollower && leaderUrl == null) {
      reportErrorOnResponse(rsp, "No follower configured or no 'leaderUrl' specified", null);
      return;
    }
    final SolrParams paramsCopy = new ModifiableSolrParams(solrParams);
    final IndexFetchResult[] results = new IndexFetchResult[1];
    Thread fetchThread =
        new Thread(
            () -> {
              IndexFetchResult result = doFetch(paramsCopy, false);
              results[0] = result;
            },
            "explicit-fetchindex-cmd");
    fetchThread.setDaemon(false);
    fetchThread.start();
    if (solrParams.getBool(WAIT, false)) {
      fetchThread.join();
      if (results[0] == null) {
        reportErrorOnResponse(rsp, "Unable to determine result of synchronous index fetch", null);
      } else if (results[0].getSuccessful()) {
        rsp.add(STATUS, OK_STATUS);
      } else {
        reportErrorOnResponse(rsp, results[0].getMessage(), null);
      }
    } else {
      rsp.add(STATUS, OK_STATUS);
    }
  }

  private List<NamedList<Object>> getCommits() {
    Map<Long, IndexCommit> commits = core.getDeletionPolicy().getCommits();
    List<NamedList<Object>> l = new ArrayList<>();

    for (IndexCommit c : commits.values()) {
      try {
        NamedList<Object> nl = new NamedList<>();
        nl.add("indexVersion", IndexDeletionPolicyWrapper.getCommitTimestamp(c));
        nl.add(GENERATION, c.getGeneration());
        List<String> commitList = new ArrayList<>(c.getFileNames().size());
        commitList.addAll(c.getFileNames());
        Collections.sort(commitList);
        nl.add(CMD_GET_FILE_LIST, commitList);
        l.add(nl);
      } catch (IOException e) {
        log.warn("Exception while reading files for commit {}", c, e);
      }
    }
    return l;
  }

  static Long getCheckSum(Checksum checksum, Path f) {
    checksum.reset();
    byte[] buffer = new byte[1024 * 1024];
    try (InputStream in = Files.newInputStream(f)) {
      int bytesRead;
      while ((bytesRead = in.read(buffer)) >= 0) checksum.update(buffer, 0, bytesRead);
      return checksum.getValue();
    } catch (Exception e) {
      log.warn("Exception in finding checksum of {}", f, e);
    }
    return null;
  }

  private volatile IndexFetcher currentIndexFetcher;

  public IndexFetchResult doFetch(SolrParams solrParams, boolean forceReplication) {
    String leaderUrl =
        solrParams == null
            ? null
            : ReplicationHandler.getObjectWithBackwardCompatibility(
                solrParams, LEADER_URL, LEGACY_LEADER_URL, null);
    if (!indexFetchLock.tryLock()) return IndexFetchResult.LOCK_OBTAIN_FAILED;
    if (core.getCoreContainer().isShutDown()) {
      log.warn("I was asked to replicate but CoreContainer is shutting down");
      return IndexFetchResult.CONTAINER_IS_SHUTTING_DOWN;
    }
    try {
      if (leaderUrl != null) {
        if (currentIndexFetcher != null && currentIndexFetcher != pollingIndexFetcher) {
          currentIndexFetcher.destroy();
        }
        currentIndexFetcher = new IndexFetcher(solrParams.toNamedList(), this, core);
      } else {
        currentIndexFetcher = pollingIndexFetcher;
      }
      return currentIndexFetcher.fetchLatestIndex(forceReplication);
    } catch (Exception e) {
      log.error("Index fetch failed", e);
      if (currentIndexFetcher != pollingIndexFetcher) {
        currentIndexFetcher.destroy();
      }
      return new IndexFetchResult(IndexFetchResult.FAILED_BY_EXCEPTION_MESSAGE, false, e);
    } finally {
      if (pollingIndexFetcher != null) {
        if (currentIndexFetcher != pollingIndexFetcher) {
          currentIndexFetcher.destroy();
        }
        currentIndexFetcher = pollingIndexFetcher;
      }
      indexFetchLock.unlock();
    }
  }

  boolean isReplicating() {
    return indexFetchLock.isLocked();
  }

  private void restore(SolrParams params, SolrQueryResponse rsp, SolrQueryRequest req)
      throws IOException {
    if (restoreFuture != null && !restoreFuture.isDone()) {
      throw new SolrException(
          ErrorCode.BAD_REQUEST,
          "Restore in progress. Cannot run multiple restore operations" + "for the same core");
    }
    String name = params.get(NAME);
    String location = params.get(CoreAdminParams.BACKUP_LOCATION);
    String repoName = params.get(CoreAdminParams.BACKUP_REPOSITORY);
    CoreContainer cc = core.getCoreContainer();
    BackupRepository repo = null;
    if (repoName != null) {
      repo = cc.newBackupRepository(repoName);
      location = repo.getBackupLocation(location);
      if (location == null) {
        throw new IllegalArgumentException("location is required");
      }
    } else {
      repo = new LocalFileSystemRepository();
      // If location is not provided then assume that the restore index is present inside the data
      // directory.
      if (location == null) {
        location = core.getDataDir();
      }
    }
    if ("file".equals(repo.createURI("x").getScheme())) {
      core.getCoreContainer().assertPathAllowed(Paths.get(location));
    }

    URI locationUri = repo.createDirectoryURI(location);

    // If name is not provided then look for the last unnamed( the ones with the snapshot.timestamp
    // format) snapshot folder since we allow snapshots to be taken without providing a name. Pick
    // the latest timestamp.
    if (name == null) {
      String[] filePaths = repo.listAll(locationUri);
      List<OldBackupDirectory> dirs = new ArrayList<>();
      for (String f : filePaths) {
        OldBackupDirectory obd = new OldBackupDirectory(locationUri, f);
        if (obd.getTimestamp().isPresent()) {
          dirs.add(obd);
        }
      }
      Collections.sort(dirs);
      if (dirs.size() == 0) {
        throw new SolrException(
            ErrorCode.BAD_REQUEST,
            "No backup name specified and none found in " + core.getDataDir());
      }
      name = dirs.get(0).getDirName();
    } else {
      // "snapshot." is prefixed by snapshooter
      name = "snapshot." + name;
    }

    RestoreCore restoreCore = RestoreCore.create(repo, core, locationUri, name);
    try {
      MDC.put("RestoreCore.core", core.getName());
      MDC.put("RestoreCore.backupLocation", location);
      MDC.put("RestoreCore.backupName", name);
      restoreFuture = restoreExecutor.submit(restoreCore);
      currentRestoreName = name;
      rsp.add(STATUS, OK_STATUS);
    } finally {
      MDC.remove("RestoreCore.core");
      MDC.remove("RestoreCore.backupLocation");
      MDC.remove("RestoreCore.backupName");
    }
  }

  private void populateRestoreStatus(SolrQueryResponse rsp) {
    NamedList<Object> restoreStatus = new SimpleOrderedMap<>();
    if (restoreFuture == null) {
      restoreStatus.add(STATUS, "No restore actions in progress");
      rsp.add(CMD_RESTORE_STATUS, restoreStatus);
      rsp.add(STATUS, OK_STATUS);
      return;
    }

    restoreStatus.add("snapshotName", currentRestoreName);
    if (restoreFuture.isDone()) {
      try {
        boolean success = restoreFuture.get();
        if (success) {
          restoreStatus.add(STATUS, SUCCESS);
        } else {
          restoreStatus.add(STATUS, FAILED);
        }
      } catch (Exception e) {
        restoreStatus.add(STATUS, FAILED);
        restoreStatus.add(EXCEPTION, e.getMessage());
        rsp.add(CMD_RESTORE_STATUS, restoreStatus);
        reportErrorOnResponse(rsp, "Unable to read restorestatus", e);
        return;
      }
    } else {
      restoreStatus.add(STATUS, "In Progress");
    }

    rsp.add(CMD_RESTORE_STATUS, restoreStatus);
    rsp.add(STATUS, OK_STATUS);
  }

  private void populateCommitInfo(SolrQueryResponse rsp) {
    rsp.add(CMD_SHOW_COMMITS, getCommits());
    rsp.add(STATUS, OK_STATUS);
  }

  private void doSnapShoot(SolrParams params, SolrQueryResponse rsp, SolrQueryRequest req) {
    try {
      int numberToKeep = params.getInt(NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM, 0);
      String location = params.get(CoreAdminParams.BACKUP_LOCATION);
      String repoName = params.get(CoreAdminParams.BACKUP_REPOSITORY);
      String commitName = params.get(CoreAdminParams.COMMIT_NAME);
      String name = params.get(NAME);
      doSnapShoot(
          numberToKeep,
          replicationHandlerConfig.numberBackupsToKeep,
          location,
          repoName,
          commitName,
          name,
          core,
          (nl) -> snapShootDetails = nl);
      rsp.add(STATUS, OK_STATUS);
    } catch (SolrException e) {
      throw e;
    } catch (Exception e) {
      log.error("Exception while creating a snapshot", e);
      reportErrorOnResponse(
          rsp, "Error encountered while creating a snapshot: " + e.getMessage(), e);
    }
  }

  public static void doSnapShoot(
      int numberToKeep,
      int numberBackupsToKeep,
      String location,
      String repoName,
      String commitName,
      String name,
      SolrCore core,
      Consumer<NamedList<?>> result)
      throws IOException {
    if (numberToKeep > 0 && numberBackupsToKeep > 0) {
      throw new SolrException(
          ErrorCode.BAD_REQUEST,
          "Cannot use "
              + NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM
              + " if "
              + NUMBER_BACKUPS_TO_KEEP_INIT_PARAM
              + " was specified in the configuration.");
    }
    numberToKeep = Math.max(numberToKeep, numberBackupsToKeep);
    if (numberToKeep < 1) {
      numberToKeep = Integer.MAX_VALUE;
    }

    CoreContainer cc = core.getCoreContainer();
    BackupRepository repo = null;
    if (repoName != null) {
      repo = cc.newBackupRepository(repoName);
      location = repo.getBackupLocation(location);
      if (location == null) {
        throw new IllegalArgumentException("location is required");
      }
    } else {
      repo = new LocalFileSystemRepository();
      if (location == null) {
        location = core.getDataDir();
      } else {
        location =
            core.getCoreDescriptor().getInstanceDir().resolve(location).normalize().toString();
      }
    }
    if ("file".equals(repo.createURI("x").getScheme())) {
      core.getCoreContainer().assertPathAllowed(Paths.get(location));
    }

    // small race here before the commit point is saved
    URI locationUri = repo.createDirectoryURI(location);
    SnapShooter snapShooter = new SnapShooter(repo, core, locationUri, name, commitName);
    snapShooter.validateCreateSnapshot();
    snapShooter.createSnapAsync(numberToKeep, result);
  }

  public IndexVersionResponse getIndexVersionResponse() throws IOException {

    IndexCommit commitPoint = indexCommitPoint; // make a copy so it won't change
    IndexVersionResponse rsp = new IndexVersionResponse();
    if (commitPoint == null) {
      // if this handler is 'lazy', we may not have tracked the last commit
      // because our commit listener is registered on inform
      commitPoint = core.getDeletionPolicy().getLatestCommit();
    }

    if (commitPoint != null && replicationEnabled.get()) {
      //
      // There is a race condition here.  The commit point may be changed / deleted by the time
      // we get around to reserving it.  This is a very small window though, and should not result
      // in a catastrophic failure, but will result in the client getting an empty file list for
      // the CMD_GET_FILE_LIST command.
      //
      core.getDeletionPolicy()
          .setReserveDuration(commitPoint.getGeneration(), reserveCommitDuration);
      rsp.indexVersion = IndexDeletionPolicyWrapper.getCommitTimestamp(commitPoint);
      rsp.generation = commitPoint.getGeneration();
    } else {
      // This happens when replication is not configured to happen after startup and no
      // commit/optimize has happened yet.
      rsp.indexVersion = 0L;
      rsp.generation = 0L;
    }
    rsp.status = OK_STATUS;

    return rsp;
  }

  /**
   * For configuration files, checksum of the file is included because, unlike index files, they may
   * have same content but different timestamps.
   *
   * <p>The local conf files information is cached so that everytime it does not have to compute the
   * checksum. The cache is refreshed only if the lastModified of the file changes
   */
  public List<FileMetaData> getConfFileInfoFromCache(
      NamedList<String> nameAndAlias, final Map<String, FileInfo> confFileInfoCache) {
    List<FileMetaData> confFiles = new ArrayList<>();
    synchronized (confFileInfoCache) {
      Checksum checksum = null;
      for (int i = 0; i < nameAndAlias.size(); i++) {
        String cf = nameAndAlias.getName(i);
        Path f = core.getResourceLoader().getConfigPath().resolve(cf);
        if (!Files.exists(f) || Files.isDirectory(f)) continue; // must not happen
        FileInfo info = confFileInfoCache.get(cf);
        long lastModified = 0;
        long size = 0;
        try {
          lastModified = Files.getLastModifiedTime(f).toMillis();
          size = Files.size(f);
        } catch (IOException e) {
          // proceed with zeroes for now, will probably error on checksum anyway
        }
        if (info == null || info.lastmodified != lastModified || info.fileMetaData.size != size) {
          if (checksum == null) checksum = new Adler32();
          info = new FileInfo(lastModified, cf, size, getCheckSum(checksum, f));
          confFileInfoCache.put(cf, info);
        }
        FileMetaData m = info.fileMetaData;
        if (nameAndAlias.getVal(i) != null) m.alias = nameAndAlias.getVal(i);
        confFiles.add(m);
      }
    }
    return confFiles;
  }

  static class FileInfo {
    long lastmodified;
    FileMetaData fileMetaData;

    public FileInfo(long lasmodified, String name, long size, long checksum) {
      this.lastmodified = lasmodified;
      this.fileMetaData = new FileMetaData(size, name, checksum);
    }
  }

  private void disablePoll(SolrQueryResponse rsp) {
    if (pollingIndexFetcher != null) {
      pollDisabled.set(true);
      log.info("inside disable poll, value of pollDisabled = {}", pollDisabled);
      rsp.add(STATUS, OK_STATUS);
    } else {
      reportErrorOnResponse(rsp, "No follower configured", null);
    }
  }

  private void enablePoll(SolrQueryResponse rsp) {
    if (pollingIndexFetcher != null) {
      pollDisabled.set(false);
      log.info("inside enable poll, value of pollDisabled = {}", pollDisabled);
      rsp.add(STATUS, OK_STATUS);
    } else {
      reportErrorOnResponse(rsp, "No follower configured", null);
    }
  }

  boolean isPollingDisabled() {
    return pollDisabled.get();
  }

  @SuppressForbidden(
      reason = "Need currentTimeMillis, to output next execution time in replication details")
  private void markScheduledExecutionStart() {
    executorStartTime = System.currentTimeMillis();
  }

  private Date getNextScheduledExecTime() {
    Date nextTime = null;
    if (executorStartTime > 0)
      nextTime =
          new Date(
              executorStartTime
                  + TimeUnit.MILLISECONDS.convert(pollIntervalNs, TimeUnit.NANOSECONDS));
    return nextTime;
  }

  int getTimesReplicatedSinceStartup() {
    return numTimesReplicated;
  }

  void setTimesReplicatedSinceStartup() {
    numTimesReplicated++;
  }

  @Override
  public Category getCategory() {
    return Category.REPLICATION;
  }

  @Override
  public String getDescription() {
    return "ReplicationHandler provides replication of index and configuration files from Leader to Followers";
  }

  public NamedList<String> getConfFileNameAlias() {
    return confFileNameAlias;
  }

  public Map<String, FileInfo> getConfFileInfoCache() {
    return confFileInfoCache;
  }

  public String getIncludeConfFiles() {
    return includeConfFiles;
  }

  public Long getReserveCommitDuration() {
    return reserveCommitDuration;
  }

  /** returns the CommitVersionInfo for the current searcher, or null on error. */
  private CommitVersionInfo getIndexVersion() {
    try {
      return core.withSearcher(
          searcher -> CommitVersionInfo.build(searcher.getIndexReader().getIndexCommit()));
    } catch (IOException e) {
      log.warn("Unable to get index commit: ", e);
      return null;
    }
  }

  // TODO: Handle compatibility in 8.x
  @Override
  public void initializeMetrics(SolrMetricsContext parentContext, String scope) {
    super.initializeMetrics(parentContext, scope);
    solrMetricsContext.gauge(
        () ->
            (core != null && !core.isClosed()
                ? NumberUtils.readableSize(core.getIndexSize())
                : parentContext.nullString()),
        true,
        "indexSize",
        getCategory().toString(),
        scope);
    solrMetricsContext.gauge(
        () ->
            (core != null && !core.isClosed()
                ? getIndexVersion().toString()
                : parentContext.nullString()),
        true,
        "indexVersion",
        getCategory().toString(),
        scope);
    solrMetricsContext.gauge(
        () ->
            (core != null && !core.isClosed()
                ? getIndexVersion().generation
                : parentContext.nullNumber()),
        true,
        GENERATION,
        getCategory().toString(),
        scope);
    solrMetricsContext.gauge(
        () -> (core != null && !core.isClosed() ? core.getIndexDir() : parentContext.nullString()),
        true,
        "indexPath",
        getCategory().toString(),
        scope);
    solrMetricsContext.gauge(() -> isLeader, true, "isLeader", getCategory().toString(), scope);
    solrMetricsContext.gauge(() -> isFollower, true, "isFollower", getCategory().toString(), scope);
    final MetricsMap fetcherMap =
        new MetricsMap(
            map -> {
              IndexFetcher fetcher = currentIndexFetcher;
              if (fetcher != null) {
                map.put(LEADER_URL, fetcher.getLeaderCoreUrl());
                if (getPollInterval() != null) {
                  map.put(ReplicationAPIBase.POLL_INTERVAL, getPollInterval());
                }
                map.put("isPollingDisabled", isPollingDisabled());
                map.put("isReplicating", isReplicating());
                long elapsed = fetcher.getReplicationTimeElapsed();
                long val = fetcher.getTotalBytesDownloaded();
                if (elapsed > 0) {
                  map.put("timeElapsed", elapsed);
                  map.put("bytesDownloaded", val);
                  map.put("downloadSpeed", val / elapsed);
                }
                Properties props = loadReplicationProperties();
                addReplicationProperties(map::putNoEx, props);
              }
            });
    solrMetricsContext.gauge(fetcherMap, true, "fetcher", getCategory().toString(), scope);
    solrMetricsContext.gauge(
        () -> isLeader && includeConfFiles != null ? includeConfFiles : "",
        true,
        "confFilesToReplicate",
        getCategory().toString(),
        scope);
    solrMetricsContext.gauge(
        () -> isLeader ? getReplicateAfterStrings() : Collections.<String>emptyList(),
        true,
        REPLICATE_AFTER,
        getCategory().toString(),
        scope);
    solrMetricsContext.gauge(
        () -> isLeader && replicationEnabled.get(),
        true,
        "replicationEnabled",
        getCategory().toString(),
        scope);
  }

  // TODO Should a failure retrieving any piece of info mark the overall request as a failure?  Is
  // there a core set of values that are required to make a response here useful?
  /** Used for showing statistics and progress information. */
  private NamedList<Object> getReplicationDetails(
      SolrQueryResponse rsp, boolean showFollowerDetails) {
    NamedList<Object> details = new SimpleOrderedMap<>();
    NamedList<Object> leader = new SimpleOrderedMap<>();
    NamedList<Object> follower = new SimpleOrderedMap<>();

    details.add("indexSize", NumberUtils.readableSize(core.getIndexSize()));
    details.add("indexPath", core.getIndexDir());
    details.add(CMD_SHOW_COMMITS, getCommits());
    details.add("isLeader", String.valueOf(isLeader));
    details.add("isFollower", String.valueOf(isFollower));
    CommitVersionInfo vInfo = getIndexVersion();
    details.add("indexVersion", null == vInfo ? 0 : vInfo.version);
    details.add(GENERATION, null == vInfo ? 0 : vInfo.generation);

    IndexCommit commit = indexCommitPoint; // make a copy so it won't change

    if (isLeader) {
      if (includeConfFiles != null) leader.add(CONF_FILES, includeConfFiles);
      leader.add(REPLICATE_AFTER, getReplicateAfterStrings());
      leader.add("replicationEnabled", String.valueOf(replicationEnabled.get()));
    }

    if (isLeader && commit != null) {
      CommitVersionInfo repCommitInfo = CommitVersionInfo.build(commit);
      leader.add("replicableVersion", repCommitInfo.version);
      leader.add("replicableGeneration", repCommitInfo.generation);
    }

    IndexFetcher fetcher = currentIndexFetcher;
    if (fetcher != null) {
      Properties props = loadReplicationProperties();
      if (showFollowerDetails) {
        try {
          NamedList<Object> nl = fetcher.getDetails();
          follower.add("leaderDetails", nl.get(CMD_DETAILS));
        } catch (Exception e) {
          log.warn("Exception while invoking 'details' method for replication on leader ", e);
          follower.add(ERR_STATUS, "invalid_leader");
        }
      }
      follower.add(LEADER_URL, fetcher.getLeaderCoreUrl());
      if (getPollInterval() != null) {
        follower.add(ReplicationAPIBase.POLL_INTERVAL, getPollInterval());
      }
      Date nextScheduled = getNextScheduledExecTime();
      if (nextScheduled != null && !isPollingDisabled()) {
        follower.add(NEXT_EXECUTION_AT, nextScheduled.toString());
      } else if (isPollingDisabled()) {
        follower.add(NEXT_EXECUTION_AT, "Polling disabled");
      }
      addReplicationProperties(follower::add, props);

      follower.add("currentDate", new Date().toString());
      follower.add("isPollingDisabled", String.valueOf(isPollingDisabled()));
      boolean isReplicating = isReplicating();
      follower.add("isReplicating", String.valueOf(isReplicating));
      if (isReplicating) {
        try {
          long bytesToDownload = 0;
          List<String> filesToDownload = new ArrayList<>();
          for (Map<String, Object> file : fetcher.getFilesToDownload()) {
            filesToDownload.add((String) file.get(NAME));
            bytesToDownload += (Long) file.get(SIZE);
          }

          // get list of conf files to download
          for (Map<String, Object> file : fetcher.getConfFilesToDownload()) {
            filesToDownload.add((String) file.get(NAME));
            bytesToDownload += (Long) file.get(SIZE);
          }

          follower.add("filesToDownload", filesToDownload);
          follower.add("numFilesToDownload", String.valueOf(filesToDownload.size()));
          follower.add("bytesToDownload", NumberUtils.readableSize(bytesToDownload));

          long bytesDownloaded = 0;
          List<String> filesDownloaded = new ArrayList<>();
          for (Map<String, Object> file : fetcher.getFilesDownloaded()) {
            filesDownloaded.add((String) file.get(NAME));
            bytesDownloaded += (Long) file.get(SIZE);
          }

          // get list of conf files downloaded
          for (Map<String, Object> file : fetcher.getConfFilesDownloaded()) {
            filesDownloaded.add((String) file.get(NAME));
            bytesDownloaded += (Long) file.get(SIZE);
          }

          Map<String, Object> currentFile = fetcher.getCurrentFile();
          String currFile = null;
          long currFileSize = 0, currFileSizeDownloaded = 0;
          float percentDownloaded = 0;
          if (currentFile != null) {
            currFile = (String) currentFile.get(NAME);
            currFileSize = (Long) currentFile.get(SIZE);
            if (currentFile.containsKey("bytesDownloaded")) {
              currFileSizeDownloaded = (Long) currentFile.get("bytesDownloaded");
              bytesDownloaded += currFileSizeDownloaded;
              if (currFileSize > 0)
                percentDownloaded = (float) (currFileSizeDownloaded * 100) / currFileSize;
            }
          }
          follower.add("filesDownloaded", filesDownloaded);
          follower.add("numFilesDownloaded", String.valueOf(filesDownloaded.size()));

          long estimatedTimeRemaining = 0;

          Date replicationStartTimeStamp = fetcher.getReplicationStartTimeStamp();
          if (replicationStartTimeStamp != null) {
            follower.add("replicationStartTime", replicationStartTimeStamp.toString());
          }
          long elapsed = fetcher.getReplicationTimeElapsed();
          follower.add("timeElapsed", String.valueOf(elapsed) + "s");

          if (bytesDownloaded > 0)
            estimatedTimeRemaining =
                ((bytesToDownload - bytesDownloaded) * elapsed) / bytesDownloaded;
          float totalPercent = 0;
          long downloadSpeed = 0;
          if (bytesToDownload > 0) totalPercent = (float) (bytesDownloaded * 100) / bytesToDownload;
          if (elapsed > 0) downloadSpeed = (bytesDownloaded / elapsed);
          if (currFile != null) follower.add("currentFile", currFile);
          follower.add("currentFileSize", NumberUtils.readableSize(currFileSize));
          follower.add(
              "currentFileSizeDownloaded", NumberUtils.readableSize(currFileSizeDownloaded));
          follower.add("currentFileSizePercent", String.valueOf(percentDownloaded));
          follower.add("bytesDownloaded", NumberUtils.readableSize(bytesDownloaded));
          follower.add("totalPercent", String.valueOf(totalPercent));
          follower.add("timeRemaining", String.valueOf(estimatedTimeRemaining) + "s");
          follower.add("downloadSpeed", NumberUtils.readableSize(downloadSpeed));
        } catch (Exception e) {
          log.error("Exception while writing replication details: ", e);
        }
      }
    }

    if (isLeader) details.add("leader", leader);
    if (follower.size() > 0) details.add("follower", follower);

    NamedList<?> snapshotStats = snapShootDetails;
    if (snapshotStats != null) details.add(CMD_BACKUP, snapshotStats);

    if (rsp.getValues().get(STATUS) == null) {
      rsp.add(STATUS, OK_STATUS);
    }
    rsp.add(CMD_DETAILS, details);
    return details;
  }

  private void addReplicationProperties(BiConsumer<String, Object> consumer, Properties props) {
    addVal(consumer, IndexFetcher.INDEX_REPLICATED_AT, props, Date.class);
    addVal(consumer, IndexFetcher.INDEX_REPLICATED_AT_LIST, props, List.class);
    addVal(consumer, IndexFetcher.REPLICATION_FAILED_AT_LIST, props, List.class);
    addVal(consumer, IndexFetcher.TIMES_INDEX_REPLICATED, props, Integer.class);
    addVal(consumer, IndexFetcher.CONF_FILES_REPLICATED, props, String.class);
    addVal(consumer, IndexFetcher.TIMES_CONFIG_REPLICATED, props, Integer.class);
    addVal(consumer, IndexFetcher.CONF_FILES_REPLICATED_AT, props, Date.class);
    addVal(consumer, IndexFetcher.LAST_CYCLE_BYTES_DOWNLOADED, props, Long.class);
    addVal(consumer, IndexFetcher.TIMES_FAILED, props, Integer.class);
    addVal(consumer, IndexFetcher.REPLICATION_FAILED_AT, props, Date.class);
    addVal(consumer, IndexFetcher.PREVIOUS_CYCLE_TIME_TAKEN, props, Long.class);
    addVal(consumer, IndexFetcher.CLEARED_LOCAL_IDX, props, Boolean.class);
  }

  private void addVal(
      BiConsumer<String, Object> consumer, String key, Properties props, Class<?> clzz) {
    Object val = formatVal(key, props, clzz);
    if (val != null) {
      consumer.accept(key, val);
    }
  }

  private Object formatVal(String key, Properties props, Class<?> clzz) {
    String s = props.getProperty(key);
    if (s == null || s.trim().length() == 0) return null;
    if (clzz == Date.class) {
      try {
        Long l = Long.parseLong(s);
        return new Date(l).toString();
      } catch (NumberFormatException e) {
        return null;
      }
    } else if (clzz == List.class) {
      String ss[] = s.split(",");
      List<String> l = new ArrayList<>();
      for (String s1 : ss) {
        l.add(new Date(Long.parseLong(s1)).toString());
      }
      return l;
    } else if (clzz == Long.class) {
      try {
        Long l = Long.parseLong(s);
        return l;
      } catch (NumberFormatException e) {
        return null;
      }
    } else if (clzz == Integer.class) {
      try {
        Integer i = Integer.parseInt(s);
        return i;
      } catch (NumberFormatException e) {
        return null;
      }
    } else if (clzz == Boolean.class) {
      return Boolean.parseBoolean(s);
    } else {
      return s;
    }
  }

  private List<String> getReplicateAfterStrings() {
    List<String> replicateAfter = new ArrayList<>();
    if (replicateOnCommit) replicateAfter.add("commit");
    if (replicateOnOptimize) replicateAfter.add("optimize");
    if (replicateOnStart) replicateAfter.add("startup");
    return replicateAfter;
  }

  Properties loadReplicationProperties() {
    Directory dir = null;
    try {
      try {
        dir =
            core.getDirectoryFactory()
                .get(
                    core.getDataDir(),
                    DirContext.META_DATA,
                    core.getSolrConfig().indexConfig.lockType);
        IndexInput input;
        try {
          input = dir.openInput(IndexFetcher.REPLICATION_PROPERTIES, IOContext.DEFAULT);
        } catch (FileNotFoundException | NoSuchFileException e) {
          return new Properties();
        }

        try {
          final InputStream is = new PropertiesInputStream(input);
          Properties props = new Properties();
          props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
          return props;
        } finally {
          input.close();
        }
      } finally {
        if (dir != null) {
          core.getDirectoryFactory().release(dir);
        }
      }
    } catch (IOException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }
  }

  //  void refreshCommitpoint() {
  //    IndexCommit commitPoint = core.getDeletionPolicy().getLatestCommit();
  //    if(replicateOnCommit || (replicateOnOptimize && commitPoint.getSegmentCount() == 1)) {
  //      indexCommitPoint = commitPoint;
  //    }
  //  }

  private void setupPolling(String intervalStr) {
    pollIntervalStr = intervalStr;
    pollIntervalNs = readIntervalNs(pollIntervalStr);
    if (pollIntervalNs == null || pollIntervalNs <= 0) {
      log.info(" No value set for 'pollInterval'. Timer Task not started.");
      return;
    }
    final Map<String, String> context = MDC.getCopyOfContextMap();
    Runnable task =
        () -> {
          MDC.setContextMap(context);
          if (pollDisabled.get()) {
            log.info("Poll disabled");
            return;
          }
          ExecutorUtil.setServerThreadFlag(true); // so PKI auth works
          try {
            log.debug("Polling for index modifications");
            markScheduledExecutionStart();
            IndexFetchResult fetchResult = doFetch(null, false);
            if (pollListener != null) pollListener.onComplete(core, fetchResult);
          } catch (Exception e) {
            log.error("Exception in fetching index", e);
          } finally {
            ExecutorUtil.setServerThreadFlag(null);
          }
        };
    executorService =
        Executors.newSingleThreadScheduledExecutor(new SolrNamedThreadFactory("indexFetcher"));
    // Randomize initial delay, with a minimum of 1ms
    long initialDelayNs =
        new Random().nextLong() % pollIntervalNs
            + TimeUnit.NANOSECONDS.convert(1, TimeUnit.MILLISECONDS);
    executorService.scheduleWithFixedDelay(
        task, initialDelayNs, pollIntervalNs, TimeUnit.NANOSECONDS);
    log.info(
        "Poll scheduled at an interval of {}ms",
        TimeUnit.MILLISECONDS.convert(pollIntervalNs, TimeUnit.NANOSECONDS));
  }

  @Override
  @SuppressWarnings({"resource"})
  public void inform(SolrCore core) {
    this.core = core;
    registerCloseHook();
    Object nbtk = initArgs.get(NUMBER_BACKUPS_TO_KEEP_INIT_PARAM);
    if (nbtk != null) {
      replicationHandlerConfig.numberBackupsToKeep = Integer.parseInt(nbtk.toString());
    } else {
      replicationHandlerConfig.numberBackupsToKeep = 0;
    }
    NamedList<?> follower = getObjectWithBackwardCompatibility(initArgs, "follower", "slave");
    boolean enableFollower = isEnabled(follower);
    if (enableFollower) {
      currentIndexFetcher = pollingIndexFetcher = new IndexFetcher(follower, this, core);
      setupPolling((String) follower.get(ReplicationAPIBase.POLL_INTERVAL));
      isFollower = true;
    }
    NamedList<?> leader = getObjectWithBackwardCompatibility(initArgs, "leader", "master");
    boolean enableLeader = isEnabled(leader);

    if (enableLeader || (enableFollower && !currentIndexFetcher.fetchFromLeader)) {
      if (core.getCoreContainer().getZkController() != null) {
        log.warn(
            "SolrCloud is enabled for core {} but so is old-style replication. "
                + "Make sure you intend this behavior, it usually indicates a mis-configuration. "
                + "Leader setting is {} and follower setting is {}",
            core.getName(),
            enableLeader,
            enableFollower);
      }
    }

    if (!enableFollower && !enableLeader) {
      enableLeader = true;
      leader = new NamedList<>();
    }

    if (enableLeader) {
      includeConfFiles = (String) leader.get(CONF_FILES);
      if (includeConfFiles != null && includeConfFiles.trim().length() > 0) {
        List<String> files = Arrays.asList(includeConfFiles.split(","));
        for (String file : files) {
          if (file.trim().length() == 0) continue;
          String[] strs = file.trim().split(":");
          // if there is an alias add it or it is null
          confFileNameAlias.add(strs[0], strs.length > 1 ? strs[1] : null);
        }
        log.info("Replication enabled for following config files: {}", includeConfFiles);
      }
      List<?> backup = leader.getAll("backupAfter");
      boolean backupOnCommit = backup.contains("commit");
      boolean backupOnOptimize = !backupOnCommit && backup.contains("optimize");
      List<?> replicateAfter = leader.getAll(REPLICATE_AFTER);
      replicateOnCommit = replicateAfter.contains("commit");
      replicateOnOptimize = !replicateOnCommit && replicateAfter.contains("optimize");

      if (!replicateOnCommit && !replicateOnOptimize) {
        replicateOnCommit = true;
      }

      // if we only want to replicate on optimize, we need the deletion policy to
      // save the last optimized commit point.
      if (replicateOnOptimize) {
        IndexDeletionPolicyWrapper wrapper = core.getDeletionPolicy();
        IndexDeletionPolicy policy = wrapper == null ? null : wrapper.getWrappedDeletionPolicy();
        if (policy instanceof SolrDeletionPolicy) {
          SolrDeletionPolicy solrPolicy = (SolrDeletionPolicy) policy;
          if (solrPolicy.getMaxOptimizedCommitsToKeep() < 1) {
            solrPolicy.setMaxOptimizedCommitsToKeep(1);
          }
        } else {
          log.warn("Replication can't call setMaxOptimizedCommitsToKeep on {}", policy);
        }
      }

      if (replicateOnOptimize || backupOnOptimize) {
        core.getUpdateHandler()
            .registerOptimizeCallback(getEventListener(backupOnOptimize, replicateOnOptimize));
      }
      if (replicateOnCommit || backupOnCommit) {
        replicateOnCommit = true;
        core.getUpdateHandler()
            .registerCommitCallback(getEventListener(backupOnCommit, replicateOnCommit));
      }
      if (replicateAfter.contains("startup")) {
        replicateOnStart = true;
        RefCounted<SolrIndexSearcher> s = core.getNewestSearcher(false);
        try {
          DirectoryReader reader = (s == null) ? null : s.get().getIndexReader();
          if (reader != null
              && reader.getIndexCommit() != null
              && reader.getIndexCommit().getGeneration() != 1L) {
            try {
              if (replicateOnOptimize) {
                Collection<IndexCommit> commits = DirectoryReader.listCommits(reader.directory());
                for (IndexCommit ic : commits) {
                  if (ic.getSegmentCount() == 1) {
                    if (indexCommitPoint == null
                        || indexCommitPoint.getGeneration() < ic.getGeneration())
                      indexCommitPoint = ic;
                  }
                }
              } else {
                indexCommitPoint = reader.getIndexCommit();
              }
            } finally {
              // We don't need to save commit points for replication, the SolrDeletionPolicy
              // always saves the last commit point (and the last optimized commit point, if needed)
              /*
              if(indexCommitPoint != null){
               core.getDeletionPolicy().saveCommitPoint(indexCommitPoint.getGeneration());
              }
              */
            }
          }

          // ensure the writer is init'd so that we have a list of commit points
          RefCounted<IndexWriter> iw =
              core.getUpdateHandler().getSolrCoreState().getIndexWriter(core);
          iw.decref();

        } catch (IOException e) {
          log.warn("Unable to get IndexCommit on startup", e);
        } finally {
          if (s != null) s.decref();
        }
      }
      isLeader = true;
    }

    {
      final String reserve = (String) initArgs.get(RESERVE);
      if (reserve != null && !reserve.trim().isEmpty()) {
        reserveCommitDuration = readIntervalMs(reserve);
      }
    }
    log.info("Commits will be reserved for {} ms", reserveCommitDuration);
  }

  @Override
  public Collection<Class<? extends JerseyResource>> getJerseyResources() {
    return List.of(CoreReplication.class, SnapshotBackupAPI.class);
  }

  @Override
  public Boolean registerV2() {
    return Boolean.TRUE;
  }

  // check leader or follower is enabled
  private boolean isEnabled(NamedList<?> params) {
    if (params == null) return false;
    Object enable = params.get("enable");
    if (enable == null) return true;
    if (enable instanceof String) return StrUtils.parseBool((String) enable);
    return Boolean.TRUE.equals(enable);
  }

  private final CloseHook startShutdownHook =
      new CloseHook() {
        @Override
        public void preClose(SolrCore core) {
          if (executorService != null) {
            // we don't wait for shutdown - this can deadlock core reload
            executorService.shutdown();
          }
        }

        @Override
        public void postClose(SolrCore core) {
          if (pollingIndexFetcher != null) {
            pollingIndexFetcher.destroy();
          }
          if (currentIndexFetcher != null && currentIndexFetcher != pollingIndexFetcher) {
            currentIndexFetcher.destroy();
          }
        }
      };
  private final CloseHook finishShutdownHook =
      new CloseHook() {
        @Override
        public void preClose(SolrCore core) {
          ExecutorUtil.shutdownAndAwaitTermination(restoreExecutor);
          if (restoreFuture != null) {
            restoreFuture.cancel(false);
          }
        }
      };

  /** register a closehook */
  private void registerCloseHook() {
    core.addCloseHook(startShutdownHook);
    core.addCloseHook(finishShutdownHook);
  }

  public void shutdown() {
    startShutdownHook.preClose(core);
    startShutdownHook.postClose(core);
    finishShutdownHook.preClose(core);
    finishShutdownHook.postClose(core);

    ExecutorUtil.shutdownAndAwaitTermination(executorService);

    core.removeCloseHook(startShutdownHook);
    core.removeCloseHook(finishShutdownHook);
  }

  /**
   * Register a listener for postcommit/optimize
   *
   * @param snapshoot do a snapshoot
   * @param getCommit get a commitpoint also
   * @return an instance of the eventlistener
   */
  private SolrEventListener getEventListener(final boolean snapshoot, final boolean getCommit) {
    return new SolrEventListener() {
      /**
       * This refreshes the latest replicateable index commit and optionally can create Snapshots as
       * well
       */
      @Override
      public void postCommit() {
        IndexCommit currentCommitPoint = core.getDeletionPolicy().getLatestCommit();

        if (getCommit) {
          // IndexCommit oldCommitPoint = indexCommitPoint;
          indexCommitPoint = currentCommitPoint;

          // We don't need to save commit points for replication, the SolrDeletionPolicy
          // always saves the last commit point (and the last optimized commit point, if needed)
          /*
          if (indexCommitPoint != null) {
            core.getDeletionPolicy().saveCommitPoint(indexCommitPoint.getGeneration());
          }
          if(oldCommitPoint != null){
            core.getDeletionPolicy().releaseCommitPointAndExtendReserve(oldCommitPoint.getGeneration());
          }
          */
        }
        if (snapshoot) {
          try {
            int numberToKeep = replicationHandlerConfig.numberBackupsToKeep;
            if (numberToKeep < 1) {
              numberToKeep = Integer.MAX_VALUE;
            }
            SnapShooter snapShooter = new SnapShooter(core, null, null);
            snapShooter.validateCreateSnapshot();
            snapShooter.createSnapAsync(numberToKeep, (nl) -> snapShootDetails = nl);
          } catch (Exception e) {
            log.error("Exception while snapshooting", e);
          }
        }
      }

      @Override
      public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) {
        /*no op*/
      }

      @Override
      public void postSoftCommit() {}
    };
  }

  private Long readIntervalMs(String interval) {
    return TimeUnit.MILLISECONDS.convert(readIntervalNs(interval), TimeUnit.NANOSECONDS);
  }

  private Long readIntervalNs(String interval) {
    if (interval == null) return null;
    int result = 0;
    Matcher m = INTERVAL_PATTERN.matcher(interval.trim());
    if (m.find()) {
      String hr = m.group(1);
      String min = m.group(2);
      String sec = m.group(3);
      result = 0;
      try {
        if (sec != null && sec.length() > 0) result += Integer.parseInt(sec);
        if (min != null && min.length() > 0) result += (60 * Integer.parseInt(min));
        if (hr != null && hr.length() > 0) result += (60 * 60 * Integer.parseInt(hr));
        return TimeUnit.NANOSECONDS.convert(result, TimeUnit.SECONDS);
      } catch (NumberFormatException e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, INTERVAL_ERR_MSG);
      }
    } else {
      throw new SolrException(ErrorCode.SERVER_ERROR, INTERVAL_ERR_MSG);
    }
  }

  private static final String SUCCESS = "success";

  private static final String FAILED = "failed";

  public static final String EXCEPTION = "exception";

  public static final String LEADER_URL = "leaderUrl";

  /**
   * @deprecated Only used for backwards compatibility. Use {@link #LEADER_URL}
   */
  @Deprecated public static final String LEGACY_LEADER_URL = "masterUrl";

  public static final String FETCH_FROM_LEADER = "fetchFromLeader";

  // in case of TLOG replica, if leaderVersion = zero, don't do commit
  // otherwise updates from current tlog won't copied over properly to the new tlog, leading to data
  // loss
  public static final String SKIP_COMMIT_ON_LEADER_VERSION_ZERO = "skipCommitOnLeaderVersionZero";

  /**
   * @deprecated Only used for backwards compatibility. Use {@link
   *     #SKIP_COMMIT_ON_LEADER_VERSION_ZERO}
   */
  @Deprecated
  public static final String LEGACY_SKIP_COMMIT_ON_LEADER_VERSION_ZERO =
      "skipCommitOnMasterVersionZero";

  public static final String MESSAGE = "message";

  public static final String COMMAND = "command";

  public static final String CMD_DETAILS = "details";

  public static final String CMD_BACKUP = "backup";

  public static final String CMD_RESTORE = "restore";

  public static final String CMD_RESTORE_STATUS = "restorestatus";

  public static final String CMD_FETCH_INDEX = "fetchindex";

  public static final String CMD_ABORT_FETCH = "abortfetch";

  public static final String CMD_GET_FILE_LIST = "filelist";

  public static final String CMD_GET_FILE = "filecontent";

  public static final String CMD_DISABLE_POLL = "disablepoll";

  public static final String CMD_DISABLE_REPL = "disablereplication";

  public static final String CMD_ENABLE_REPL = "enablereplication";

  public static final String CMD_ENABLE_POLL = "enablepoll";

  public static final String CMD_INDEX_VERSION = "indexversion";

  public static final String CMD_SHOW_COMMITS = "commits";

  public static final String CMD_DELETE_BACKUP = "deletebackup";

  public static final String SIZE = "size";

  public static final String ALIAS = "alias";

  public static final String CONF_CHECKSUM = "confchecksum";

  public static final String CONF_FILES = "confFiles";

  public static final String REPLICATE_AFTER = "replicateAfter";

  public static final String RESERVE = "commitReserveDuration";

  public static final String EXTERNAL = "external";

  public static final String INTERNAL = "internal";

  public static final String ERR_STATUS = "ERROR";

  public static final String OK_STATUS = "OK";

  public static final String NEXT_EXECUTION_AT = "nextExecutionAt";

  public static final String NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM = "numberToKeep";

  public static final String NUMBER_BACKUPS_TO_KEEP_INIT_PARAM = "maxNumberOfBackups";

  /**
   * Boolean param for tests that can be specified when using {@link #CMD_FETCH_INDEX} to force the
   * current request to block until the fetch is complete. <b>NOTE:</b> This param is not advised
   * for non-test code, since the duration of the fetch for non-trivial indexes will likeley cause
   * the request to time out.
   *
   * @lucene.internal
   */
  public static final String WAIT = "wait";

  public static class ReplicationHandlerConfig implements APIConfigProvider.APIConfig {

    private int numberBackupsToKeep = 0; // zero: do not delete old backups

    public int getNumberBackupsToKeep() {
      return numberBackupsToKeep;
    }
  }

  @Override
  public ReplicationHandlerConfig provide() {
    return replicationHandlerConfig;
  }

  @Override
  public Class<ReplicationHandlerConfig> getConfigClass() {
    return ReplicationHandlerConfig.class;
  }
}
