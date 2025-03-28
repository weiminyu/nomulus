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

import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.rde.RdeMode.THIN;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.cloud.storage.BlobId;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.common.Cursor;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;

/**
 * Action that re-encrypts a BRDA escrow deposit and puts it into the upload bucket.
 *
 * <p>This action is run for each BRDA staging file it generates. The staging file is encrypted with
 * our internal {@link Ghostryde} encryption. We then re-encrypt it as a RyDE file, which is what
 * the third-party escrow provider understands.
 *
 * <p>Then we put the RyDE file (along with our digital signature) into the configured BRDA bucket.
 * This bucket is special because a separate script will rsync it to the third party escrow provider
 * SFTP server. This is why the internal staging files are stored in the separate RDE bucket.
 *
 * @see <a
 *     href="http://newgtlds.icann.org/en/applicants/agb/agreement-approved-09jan14-en.htm">Registry
 *     Agreement</a>
 */
@Action(
    service = GaeService.BACKEND,
    path = BrdaCopyAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public final class BrdaCopyAction implements Runnable {

  public static final String PATH = "/_dr/task/brdaCopy";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject GcsUtils gcsUtils;
  @Inject @Config("brdaBucket") String brdaBucket;
  @Inject @Config("rdeBucket") String stagingBucket;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject @Parameter(RdeModule.PARAM_WATERMARK) DateTime watermark;
  @Inject @Parameter(RdeModule.PARAM_PREFIX) Optional<String> prefix;
  @Inject @Key("brdaReceiverKey") PGPPublicKey receiverKey;
  @Inject @Key("brdaSigningKey") PGPKeyPair signingKey;
  @Inject @Key("rdeStagingDecryptionKey") PGPPrivateKey stagingDecryptionKey;
  @Inject BrdaCopyAction() {}

  @Override
  public void run() {
    try {
      copyAsRyde();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void copyAsRyde() throws IOException {
    // TODO(b/217772483): consider guarding this action with a lock and check if there is work.
    // Not urgent since file writes on GCS are atomic.
    Optional<Cursor> cursor =
        tm().transact(() -> tm().loadByKeyIfPresent(Cursor.createScopedVKey(BRDA, Tld.get(tld))));
    DateTime brdaCursorTime = getCursorTimeOrStartOfTime(cursor);
    if (isBeforeOrAt(brdaCursorTime, watermark)) {
      throw new NoContentException(
          String.format(
              "Waiting on RdeStagingAction for TLD %s to copy BRDA deposit for %s to GCS; "
                  + "last BRDA staging completion was before %s",
              tld, watermark, brdaCursorTime));
    }
    int revision =
        RdeRevision.getCurrentRevision(tld, watermark, THIN)
            .orElseThrow(
                () -> new IllegalStateException("RdeRevision was not set on generated deposit"));
    String nameWithoutPrefix = RdeNamingUtils.makeRydeFilename(tld, watermark, THIN, 1, revision);
    String name = prefix.orElse("") + nameWithoutPrefix;
    BlobId xmlFilename = BlobId.of(stagingBucket, name + ".xml.ghostryde");
    BlobId xmlLengthFilename = BlobId.of(stagingBucket, name + ".xml.length");
    BlobId rydeFile = BlobId.of(brdaBucket, nameWithoutPrefix + ".ryde");
    BlobId sigFile = BlobId.of(brdaBucket, nameWithoutPrefix + ".sig");

    long xmlLength = readXmlLength(xmlLengthFilename);

    logger.atInfo().log("Writing files '%s' and '%s'.", rydeFile, sigFile);
    try (InputStream gcsInput = gcsUtils.openInputStream(xmlFilename);
        InputStream ghostrydeDecoder = Ghostryde.decoder(gcsInput, stagingDecryptionKey);
        OutputStream rydeOut = gcsUtils.openOutputStream(rydeFile);
        OutputStream sigOut = gcsUtils.openOutputStream(sigFile);
        RydeEncoder rydeEncoder =
            new RydeEncoder.Builder()
                .setRydeOutput(rydeOut, receiverKey)
                .setSignatureOutput(sigOut, signingKey)
                .setFileMetadata(nameWithoutPrefix, xmlLength, watermark)
                .build()) {
      ByteStreams.copy(ghostrydeDecoder, rydeEncoder);
    }
  }

  /** Reads the contents of a file from Cloud Storage that contains nothing but an integer. */
  private long readXmlLength(BlobId xmlLengthFilename) throws IOException {
    try (InputStream input = gcsUtils.openInputStream(xmlLengthFilename)) {
      return Ghostryde.readLength(input);
    }
  }
}
