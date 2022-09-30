package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Maps.filterValues;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.tools.RegistryToolEnvironment.PRODUCTION;
import static google.registry.tools.RegistryToolEnvironment.SANDBOX;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.util.DiffUtils;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.joda.time.DateTime;

@Parameters(separators = " =", commandDescription = "Compare two XML escrow deposits.")
public class FetchEppHistoryCommand implements CommandWithRemoteApi, CommandWithConnection {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableSet<RegistryToolEnvironment> FORBIDDEN_ENVIRONMENTS =
      ImmutableSet.of(PRODUCTION, SANDBOX);

  private static final String HISTORY_FILE = "history_dump.txt";
  private static final String HISTORY_DOWNLOAD_DONE_FILE = "history_dump.done";
  private static final String HISTORY_REPLAY_PROGRESS = "replay_progress.txt";

  @Parameter(
      names = {"--dataDir", "--data-dir"},
      description = "Absolute path to the directory for working data storage.")
  private String dataDir;

  @Parameter(
      names = {"--download-only"},
      description = "When true, only downloads history entries.",
      arity = 1)
  private boolean downloadOnly = true;

  private static final String RESAVE_START_TIME = "2022-09-05T09:00:00Z";
  private static final String RESAVE_END_TIME = "2022-09-10T11:30:00Z";

  // Relevant history events during the pipeline run. DOMAIN_AUTORENEW events are synthesized and
  // can be safely ignored.
  private static final String AFFECTED_DOMAIN_HISTORY_QUERY =
      "select domain_repo_id, history_revision_id, history_modification_time, "
          + "history_registrar_id, domain_name, history_type, history_xml_bytes "
          + "from \"DomainHistory\" h, \"Registrar\" r "
          + "where history_modification_time >= '2022-09-05T09:00:00Z' "
          + "and history_modification_time < '2022-09-10T11:30:00Z' "
          + "and (current_sponsor_registrar_id = registrar_id "
          + "     or creation_registrar_id = registrar_id) "
          + "and r.type = 'REAL' "
          + "and history_type != 'DOMAIN_AUTORENEW' "
          + "and h.creation_time < '2022-09-05T09:10:00Z' "
          + "order by domain_repo_id, history_modification_time";

  private static final String EXISTING_DOMAIN_INITIAL_STATE_QUERY =
      "select h from DomainHistory h where domainRepoId = :repoId and modificationTime in "
          + "(select max(modificationTime) from DomainHistory where domainRepoId = :repoId "
          + "and modificationTime < :cutoff)";

  private static final String DOMAIN_FINAL_STATE_QUERY =
      "select h from DomainHistory h where domainRepoId = :repoId and modificationTime in "
          + "(select min(modificationTime) from DomainHistory where domainRepoId = :repoId "
          + "and modificationTime >= :cutoff)";

  AppEngineConnection connection;

  @Override
  public void run() throws Exception {
    checkState(
        !FORBIDDEN_ENVIRONMENTS.contains(RegistryToolEnvironment.get()),
        "Cannot replay history against production or sandbox.");
    checkArgument(
        dataDir.startsWith("/"), "--data_dir must be an absolute path to a local directory.");

    downloadHistory();

    if (downloadOnly) {
      return;
    }
    replay();
  }

  void downloadHistory() throws IOException {
    File historyDoneFile = new File(dataDir + "/" + HISTORY_DOWNLOAD_DONE_FILE);
    if (historyDoneFile.exists()) {
      logger.atInfo().log("History already downloaded.");
      return;
    }

    String historyFilePathname = dataDir + "/" + HISTORY_FILE;
    ImmutableMap<String, Collection<SimpleHistory>> perDomainHistory = loadHistoryFromDatabase();
    try (PrintWriter writer = new PrintWriter(historyFilePathname, StandardCharsets.UTF_8.name())) {
      perDomainHistory.keySet().stream()
          .sorted()
          .forEach(
              repoId ->
                  perDomainHistory
                      .get(repoId)
                      .forEach(
                          simpleHistory -> {
                            writer.println(simpleHistory);
                          }));
    }
    com.google.common.io.Files.touch(historyDoneFile);
  }

  ImmutableSet<String> loadProgress(String progressFilePath) throws IOException {

    try (Stream<String> stream = Files.lines(Paths.get(progressFilePath), StandardCharsets.UTF_8)) {
      return stream.filter(line -> !line.trim().isEmpty()).collect(ImmutableSet.toImmutableSet());
    }
  }

  void replay() throws IOException {
    String historyFilePath = dataDir + "/" + HISTORY_FILE;
    String progressFilePath = dataDir + "/" + HISTORY_REPLAY_PROGRESS;
    ImmutableSet<String> completedDomains = loadProgress(progressFilePath);

    try (PrintWriter progressWriter = new PrintWriter(new FileWriter(progressFilePath, true))) {
      ImmutableMap<String, Collection<SimpleHistory>> perDomainHistory =
          loadHistoryFromFile(historyFilePath);
      perDomainHistory.keySet().stream()
          .filter(repoId -> !completedDomains.contains(repoId))
          .sorted()
          .forEach(
              repoId ->
                  replayOneDomain(
                      repoId, perDomainHistory.get(repoId), completedDomains, progressWriter));
    }
  }

  void replayOneDomain(
      String repoId,
      Collection<SimpleHistory> events,
      ImmutableSet<String> completedDomains,
      PrintWriter progressWriter) {
    Iterator<SimpleHistory> remainingEvents = initializeDomainAndReturnEvents(repoId, events);
    ImmutableList.Builder<String> responses = ImmutableList.builder();
    try {
      while (remainingEvents.hasNext()) {
        SimpleHistory h = remainingEvents.next();
        responses.add(playEppXml(h.registrarId(), h.historyXmlBytes()));
      }
    } catch (IOException e) {
      throw new RuntimeException(Joiner.on("\n").join(responses.build()), e);
    }
    DomainHistory lastHistory =
        jpaTm()
            .transact(
                () ->
                    (DomainHistory)
                        Iterables.getOnlyElement(
                            jpaTm()
                                .getEntityManager()
                                .createQuery(DOMAIN_FINAL_STATE_QUERY)
                                .setParameter("repoId", repoId)
                                .setParameter("cutoff", DateTime.parse(RESAVE_END_TIME))
                                .getResultList()));
    if (lastHistory.getXmlBytes() != null && lastHistory.getXmlBytes().length > 0) {
      try {
        responses.add(
            playEppXml(lastHistory.getRegistrarId(), new String(lastHistory.getXmlBytes(), UTF_8)));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    Domain finalActualState = getDomainFromHistory(lastHistory);
    Domain finalReplayedState =
        jpaTm().transact(() -> jpaTm().loadByKey(VKey.createSql(Domain.class, repoId)));

    String result = "FAILED";
    if (finalActualState.equals(finalReplayedState)) {
      result = "SUCCEEDED";
    } else {
      logger.atWarning().log(
          DiffUtils.prettyPrintEntityDeepDiff(
              finalActualState.toDiffableFieldMap(), finalReplayedState.toDiffableFieldMap()));
    }
    if (result == "FAILED") {
      finalReplayedState = finalReplayedState.cloneProjectedAtTime(DateTime.parse(RESAVE_END_TIME));
      if (finalActualState.equals(finalReplayedState)) {
        result = "SUCCEEDED_WITH_RESAVE";
      }
    }

    progressWriter.println(repoId + "," + result);
    progressWriter.flush();
  }

  String playEppXml(String clientId, String xml) throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("dryRun", false);
    params.put("clientId", clientId);
    params.put("superuser", true);
    params.put("xml", URLEncoder.encode(xml, UTF_8.toString()));
    String requestBody =
        Joiner.on('&').withKeyValueSeparator("=").join(filterValues(params, Objects::nonNull));
    return nullToEmpty(
        connection.sendPostRequest(
            "/_dr/epptool",
            ImmutableMap.<String, String>of(),
            MediaType.FORM_DATA,
            requestBody.getBytes(UTF_8)));
  }

  Iterator<SimpleHistory> initializeDomainAndReturnEvents(
      String repoId, Collection<SimpleHistory> events) {
    PeekingIterator<SimpleHistory> it = Iterators.peekingIterator(events.iterator());
    Domain initialState;
    if (it.peek().historyType().equals(HistoryEntry.Type.DOMAIN_CREATE)) {
      // New domain created during the safety buffer. Use its content as the initial state
      SimpleHistory creationEvent = it.next();
      VKey<DomainHistory> vkey =
          VKey.createSql(
              DomainHistory.class,
              new DomainHistoryId(creationEvent.domainRepoId(), creationEvent.historyRevisionId()));
      initialState = getDomainFromHistory(jpaTm().transact(() -> jpaTm().loadByKey(vkey)));
    } else {
      initialState =
          getDomainFromHistory(
              (DomainHistory)
                  jpaTm()
                      .transact(
                          () ->
                              Iterables.getOnlyElement(
                                  jpaTm()
                                      .getEntityManager()
                                      .createQuery(EXISTING_DOMAIN_INITIAL_STATE_QUERY)
                                      .setParameter("repoId", repoId)
                                      .setParameter("cutoff", DateTime.parse(RESAVE_START_TIME))
                                      .getResultList())));
    }
    jpaTm().transact(() -> jpaTm().put(initialState));
    return it;
  }

  private Domain getDomainFromHistory(DomainHistory history) {
    return new Domain.Builder()
        .copyFrom(
            history
                .getDomainBase()
                .orElseThrow(
                    () -> new IllegalStateException(history.getDomainHistoryId().toString())))
        .build();
  }

  ImmutableMap<String, Collection<SimpleHistory>> loadHistoryFromFile(String path)
      throws IOException {
    ImmutableMultimap.Builder<String, SimpleHistory> builder = getSortedHistoryMapBuilder();
    try (Stream<String> stream = Files.lines(Paths.get(path), StandardCharsets.UTF_8)) {
      stream
          .filter(line -> !line.trim().isEmpty())
          .map(SimpleHistory::fromString)
          .forEach(simpleHistory -> builder.put(simpleHistory.domainRepoId(), simpleHistory));
    }
    ImmutableMultimap<String, SimpleHistory> perDomainHistory = builder.build();
    logger.atInfo().log(
        "Finished loading %s domains with %s rows from file.",
        perDomainHistory.keySet().size(), perDomainHistory.size());
    return perDomainHistory.asMap();
  }

  ImmutableMap<String, Collection<SimpleHistory>> loadHistoryFromDatabase() throws IOException {
    // Cannot use replica, see b/249342270.
    ImmutableMultimap<String, SimpleHistory> perDomainHistory =
        jpaTm()
            .transact(
                () -> {
                  ImmutableMultimap.Builder<String, SimpleHistory> builder =
                      getSortedHistoryMapBuilder();
                  Stream<?> resultStream =
                      (Stream<?>)
                          jpaTm()
                              .getEntityManager()
                              .createNativeQuery(AFFECTED_DOMAIN_HISTORY_QUERY)
                              .getResultStream();
                  resultStream
                      .map(Object[].class::cast)
                      .forEach(
                          row -> {
                            builder.put((String) row[0], SimpleHistory.create(row));
                          });
                  return builder.build();
                });

    Preconditions.checkState(perDomainHistory != null);
    logger.atInfo().log(
        "Finished loading %s domains with %s rows from the database.",
        perDomainHistory.keySet().size(), perDomainHistory.size());
    return perDomainHistory.asMap();
  }

  /**
   * Returns a builder for a {@link ImmutableMultimap} of {@link SimpleHistory} instances which are
   * grouped by domain and ordered by modification time.
   */
  ImmutableMultimap.Builder<String, SimpleHistory> getSortedHistoryMapBuilder() {
    return ImmutableMultimap.<String, SimpleHistory>builder()
        .orderValuesBy(
            Comparator.comparing(SimpleHistory::historyModificationTime, DateTime::compareTo));
  }

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  @AutoValue
  abstract static class SimpleHistory {
    abstract String domainRepoId();

    abstract long historyRevisionId();

    abstract DateTime historyModificationTime();

    abstract String registrarId();

    abstract String domainName();

    abstract HistoryEntry.Type historyType();

    abstract String historyXmlBytes();

    @Override
    public final String toString() {
      return String.format(
          "%s,%s,%s,%s,%s,%s,%s\n",
          domainRepoId(),
          historyRevisionId(),
          historyModificationTime(),
          registrarId(),
          domainName(),
          historyType(),
          historyXmlBytes().replaceAll("[\r\n]", ""));
    }

    public static SimpleHistory fromString(String line) {
      checkNotNull(line, "Input is null.");
      List<String> fields = Splitter.on(",").trimResults().limit(6).splitToList(line);
      checkArgument(fields.size() == 6, "Line missing fields: %s", line);

      return new google.registry.tools.AutoValue_FetchEppHistoryCommand_SimpleHistory(
          fields.get(0),
          Long.valueOf(fields.get(1)),
          DateTime.parse(fields.get(2)),
          fields.get(3),
          fields.get(4),
          HistoryEntry.Type.valueOf(fields.get(5)),
          fields.get(6));
    }

    public static SimpleHistory create(Object[] row) {
      Preconditions.checkArgument(row.length == 7, "Wrong number of columns in row.");
      return new google.registry.tools.AutoValue_FetchEppHistoryCommand_SimpleHistory(
          (String) row[0],
          ((BigInteger) row[1]).longValue(),
          new DateTime(row[2]),
          (String) row[3],
          (String) row[4],
          HistoryEntry.Type.valueOf((String) row[5]),
          row[6] == null ? "" : new String((byte[]) row[6], StandardCharsets.UTF_8));
    }
  }
}
