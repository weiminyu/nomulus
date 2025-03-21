// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.rde;

import static com.google.common.base.Verify.verify;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.rde.RdeUtils.findMostRecentPrefixForWatermark;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.cloud.storage.BlobId;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Tld;
import google.registry.rde.EscrowTaskRunner.EscrowTask;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that uploads a small XML RDE report to ICANN after {@link RdeUploadAction} has finished.
 */
@Action(
    service = GaeService.BACKEND,
    path = RdeReportAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public final class RdeReportAction implements Runnable, EscrowTask {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/rdeReport";

  @Inject GcsUtils gcsUtils;
  @Inject EscrowTaskRunner runner;
  @Inject Response response;
  @Inject RdeReporter reporter;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject @Parameter(RdeModule.PARAM_PREFIX) Optional<String> prefix;
  @Inject @Config("rdeBucket") String bucket;
  @Inject @Config("rdeInterval") Duration interval;
  @Inject @Config("rdeReportLockTimeout") Duration timeout;
  @Inject @Key("rdeStagingDecryptionKey") PGPPrivateKey stagingDecryptionKey;
  @Inject RdeReportAction() {}

  @Override
  public void run() {
    runner.lockRunAndRollForward(this, Tld.get(tld), timeout, CursorType.RDE_REPORT, interval);
  }

  @Override
  public void runWithLock(DateTime watermark) throws Exception {
    Optional<Cursor> cursor =
        tm().transact(
                () ->
                    tm().loadByKeyIfPresent(
                            Cursor.createScopedVKey(CursorType.RDE_UPLOAD, Tld.get(tld))));
    DateTime cursorTime = getCursorTimeOrStartOfTime(cursor);
    if (isBeforeOrAt(cursorTime, watermark)) {
      throw new NoContentException(
          String.format(
              "Waiting on RdeUploadAction for TLD %s to send %s report; "
                  + "last upload completion was at %s",
              tld, watermark, cursorTime));
    }
    int revision =
        RdeRevision.getCurrentRevision(tld, watermark, FULL)
            .orElseThrow(
                () -> new IllegalStateException("RdeRevision was not set on generated deposit"));
    if (prefix.isEmpty()) {
      prefix = Optional.of(findMostRecentPrefixForWatermark(watermark, bucket, tld, gcsUtils));
    }
    String name = prefix.get() + RdeNamingUtils.makeRydeFilename(tld, watermark, FULL, 1, revision);
    BlobId reportFilename = BlobId.of(bucket, name + "-report.xml.ghostryde");
    verify(gcsUtils.existsAndNotEmpty(reportFilename), "Missing file: %s", reportFilename);
    reporter.send(readReportFromGcs(reportFilename));
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("OK %s %s\n", tld, watermark));
    logger.atInfo().log("Successfully sent report %s.", reportFilename);
  }

  /** Reads and decrypts the XML file from cloud storage. */
  private byte[] readReportFromGcs(BlobId reportFilename) throws IOException {
    try (InputStream gcsInput = gcsUtils.openInputStream(reportFilename);
        InputStream ghostrydeDecoder = Ghostryde.decoder(gcsInput, stagingDecryptionKey)) {
      return ByteStreams.toByteArray(ghostrydeDecoder);
    }
  }
}
