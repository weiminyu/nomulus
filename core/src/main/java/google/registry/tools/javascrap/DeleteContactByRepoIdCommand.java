// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.model.EppResourceUtils.isLinked;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.appengine.tools.remoteapi.RemoteApiInstaller;
import com.google.appengine.tools.remoteapi.RemoteApiOptions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.contact.Contact;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.tools.CommandWithConnection;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.tools.RemoteApiOptionsUtil;
import google.registry.tools.ServiceConnection;
import google.registry.util.Clock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Command that safely deletes unused contact.
 *
 * <p>This is created to fix the issue identified in b/261192144. After the out-of-band contact_id
 * change, there are a few pairs of contacts that share contact_ids. They are not causing problems
 * to RDE right now, but may do so if their contact_ids are put in use in the future.
 *
 * <p>This command allows us to delete a {@link Contact} by its {@code repoId}. It goes through most
 * of the checks in the {@code ContactDeleteFlow} that ensure the safety of the deletion. It also
 * generates a history event.
 *
 * <p>The {@code ContactDeleteFlow} itself cannot be used because deletion by EPP command is based
 * on contact_id, and there is no control on which one of a pair is deleted.
 */
@Parameters(
    separators = " =",
    commandDescription = "Safely delete unused contacts by repo_id to fix RDE.")
public class DeleteContactByRepoIdCommand extends ConfirmingCommand
    implements CommandWithRemoteApi, CommandWithConnection {

  private static final String HISTORY_REASON = "Delete unused contacts to fix RDE for b/261192144";

  @Parameter(names = "--contact_repo_id", description = "The repoId of the Contact to be deleted.")
  private String contactRepoId;

  private ServiceConnection connection;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminRegistrarId;

  @Inject Clock clock;

  @Inject @CredentialModule.LocalCredentialJson String localCredentialJson;

  @Override
  protected String prompt() {
    checkArgument(!Strings.isNullOrEmpty(contactRepoId), "repo_id must be present.");
    return "";
  }

  @Override
  protected String execute() throws Exception {
    DateTime now = clock.nowUtc();
    jpaTm()
        .transact(
            () -> {
              VKey<Contact> contactVKey = VKey.create(Contact.class, contactRepoId);
              checkState(!isLinked(contactVKey, now), "Contact %s is still in use.", contactRepoId);
              Contact existingContact = jpaTm().loadByKey(contactVKey);

              try {
                //
                verifyNoDisallowedStatuses(
                    existingContact,
                    ImmutableSet.of(
                        StatusValue.CLIENT_DELETE_PROHIBITED,
                        StatusValue.PENDING_DELETE,
                        StatusValue.SERVER_DELETE_PROHIBITED));
              } catch (Exception e) {
                Throwables.throwIfUnchecked(e);
                throw new RuntimeException(e);
              }
              if (existingContact.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
                throw new IllegalStateException("Cannot delete contacts pending transfer yet.");
              }
              // Wipe out PII on contact deletion.
              Contact newContact =
                  existingContact
                      .asBuilder()
                      .wipeOut()
                      .setStatusValues(null)
                      .setDeletionTime(now)
                      .build();
              HistoryEntry contactHistory =
                  HistoryEntry.createBuilderForResource(newContact)
                      .setRegistrarId(registryAdminRegistrarId)
                      .setBySuperuser(true)
                      .setRequestedByRegistrar(false)
                      .setModificationTime(jpaTm().getTransactionTime())
                      .setReason(HISTORY_REASON)
                      .setType(HistoryEntry.Type.CONTACT_DELETE)
                      .build();
              jpaTm().insert(contactHistory);
              jpaTm().update(newContact);
            });

    return String.format("Deleted contact %s", contactRepoId);
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  /**
   * Installs the remote API so that the worker threads can use Datastore for ID generation.
   *
   * <p>Lifted from the RegistryCli class
   */
  private RemoteApiInstaller createInstaller() {
    RemoteApiInstaller installer = new RemoteApiInstaller();
    RemoteApiOptions options = new RemoteApiOptions();
    options.server(connection.getServer().getHost(), getPort(connection.getServer()));
    if (RegistryConfig.areServersLocal()) {
      // Use dev credentials for localhost.
      options.useDevelopmentServerCredential();
    } else {
      try {
        RemoteApiOptionsUtil.useGoogleCredentialStream(
            options, new ByteArrayInputStream(localCredentialJson.getBytes(UTF_8)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    try {
      installer.install(options);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return installer;
  }

  private static int getPort(URL url) {
    return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
  }
}
