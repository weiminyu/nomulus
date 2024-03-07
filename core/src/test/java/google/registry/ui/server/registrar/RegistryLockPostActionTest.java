// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.ui.server.registrar.RegistryLockGetActionTest.userFromRegistrarPoc;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.groups.GmailClient;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.model.domain.RegistryLock;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import google.registry.request.JsonActionRunner;
import google.registry.request.JsonResponse;
import google.registry.request.ResponseImpl;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.tools.DomainLockUtils;
import google.registry.util.EmailMessage;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link RegistryLockPostAction}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
final class RegistryLockPostActionTest {

  private static final String EMAIL_MESSAGE_TEMPLATE =
      "Please click the link below to perform the lock \\/ unlock action on domain example.tld. "
          + "Note: this code will expire in one hour.\n\n"
          + "https:\\/\\/registrarconsole.tld\\/registry-lock-verify\\?lockVerificationCode="
          + "[0-9a-zA-Z_\\-]+&isLock=(true|false)";

  private final FakeClock clock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private User userWithoutPermission;
  private User userWithLockPermission;
  private Domain domain;
  private RegistryLockPostAction action;

  @Mock GmailClient gmailClient;
  @Mock HttpServletRequest mockRequest;
  @Mock HttpServletResponse mockResponse;

  @BeforeEach
  void beforeEach() throws Exception {
    userWithLockPermission =
        userFromRegistrarPoc(JpaTransactionManagerExtension.makeRegistrarContact3());
    userWithoutPermission =
        userFromRegistrarPoc(JpaTransactionManagerExtension.makeRegistrarContact2());
    createTld("tld");
    domain = persistResource(DatabaseHelper.newDomain("example.tld"));

    when(mockRequest.getServerName()).thenReturn("registrarconsole.tld");

    action =
        createAction(AuthResult.createUser(UserAuthInfo.create(userWithLockPermission, false)));
  }

  @Test
  void testSuccess_lock() throws Exception {
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  void testSuccess_unlock() throws Exception {
    saveRegistryLock(createLock().asBuilder().setLockCompletionTime(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertSuccess(response, "unlock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  void testSuccess_unlock_relockDurationSet() throws Exception {
    saveRegistryLock(createLock().asBuilder().setLockCompletionTime(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    ImmutableMap<String, Object> request =
        new ImmutableMap.Builder<String, Object>()
            .putAll(unlockRequest())
            .put("relockDurationMillis", Duration.standardDays(1).getMillis())
            .build();
    Map<String, ?> response = action.handleJsonRequest(request);
    assertSuccess(response, "unlock", "Marla.Singer.RegistryLock@crr.com");
    RegistryLock savedUnlockRequest = getMostRecentRegistryLockByRepoId(domain.getRepoId()).get();
    assertThat(savedUnlockRequest.getRelockDuration())
        .isEqualTo(Optional.of(Duration.standardDays(1)));
  }

  @Test
  void testSuccess_unlock_adminUnlockingAdmin() throws Exception {
    saveRegistryLock(
        createLock().asBuilder().isSuperuser(true).setLockCompletionTime(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action = createAction(AuthResult.createUser(UserAuthInfo.create(userWithoutPermission, true)));
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    // we should still email the admin user's email address
    assertSuccess(response, "unlock", "johndoe@theregistrar.com");
  }

  @Test
  void testSuccess_linkedToLoginEmail() throws Exception {
    userWithLockPermission = new User("Marla.Singer@crr.com", "crr.com");
    action =
        createAction(AuthResult.createUser(UserAuthInfo.create(userWithLockPermission, false)));
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  void testFailure_unlock_noLock() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(response, "No lock object for domain example.tld");
  }

  @Test
  void testFailure_unlock_alreadyUnlocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(
        createLock()
            .asBuilder()
            .setLockCompletionTime(clock.nowUtc())
            .setUnlockRequestTime(clock.nowUtc())
            .build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(response, "A pending unlock action already exists for example.tld");
  }

  @Test
  void testFailure_unlock_nonAdminUnlockingAdmin() {
    saveRegistryLock(
        createLock().asBuilder().isSuperuser(true).setLockCompletionTime(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(
        response, "Non-admin user cannot unlock admin-locked domain example.tld");
  }

  @Test
  void testSuccess_adminUser() throws Exception {
    // Admin user should be able to lock/unlock regardless -- and we use the admin user's email
    action = createAction(AuthResult.createUser(UserAuthInfo.create(userWithoutPermission, true)));
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "johndoe@theregistrar.com");
  }

  @Test
  void testSuccess_adminUser_doesNotRequirePassword() throws Exception {
    action = createAction(AuthResult.createUser(UserAuthInfo.create(userWithoutPermission, true)));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "example.tld",
                "isLock", true));
    assertSuccess(response, "lock", "johndoe@theregistrar.com");
  }

  @Test
  void testSuccess_consoleUser() throws Exception {
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of(
                            "TheRegistrar", RegistrarRole.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK))
                    .build())
            .setRegistryLockPassword("hi")
            .build();
    AuthResult consoleAuthResult = AuthResult.createUser(UserAuthInfo.create(consoleUser));
    action = createAction(consoleAuthResult);
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "johndoe@theregistrar.com");
  }

  @Test
  void testSuccess_consoleUser_admin() throws Exception {
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setUserRoles(new UserRoles.Builder().setIsAdmin(true).build())
            .build();
    AuthResult consoleAuthResult = AuthResult.createUser(UserAuthInfo.create(consoleUser));
    action = createAction(consoleAuthResult);
    Map<String, Object> requestMapWithoutPassword =
        ImmutableMap.of(
            "isLock", true,
            "registrarId", "TheRegistrar",
            "domainName", "example.tld");
    Map<String, ?> response = action.handleJsonRequest(requestMapWithoutPassword);
    assertSuccess(response, "lock", "johndoe@theregistrar.com");
  }

  @Test
  void testFailure_noInput() {
    Map<String, ?> response = action.handleJsonRequest(null);
    assertFailureWithMessage(response, "Null JSON");
  }

  @Test
  void testFailure_noRegistrarId() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of());
    assertFailureWithMessage(response, "Missing key for registrarId");
  }

  @Test
  void testFailure_emptyRegistrarId() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of("registrarId", ""));
    assertFailureWithMessage(response, "Missing key for registrarId");
  }

  @Test
  void testFailure_unauthorizedRegistrarId() {
    AuthResult authResult =
        AuthResult.createUser(UserAuthInfo.create(userWithLockPermission, false));
    action = createAction(authResult, ImmutableSet.of("TheRegistrar"));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "isLock", true,
                "registrarId", "NewRegistrar",
                "domainName", "example.tld",
                "password", "hi"));
    assertFailureWithMessage(response, "TestUserId doesn't have access to registrar NewRegistrar");
  }

  @Test
  void testFailure_incorrectRegistrarIdForDomain() {
    persistResource(
        domain.asBuilder().setPersistedCurrentSponsorRegistrarId("NewRegistrar").build());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(response, "Domain example.tld is not owned by registrar TheRegistrar");
  }

  @Test
  void testFailure_noDomainName() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of("registrarId", "TheRegistrar", "password", "hi", "isLock", true));
    assertFailureWithMessage(response, "Missing key for domainName");
  }

  @Test
  void testFailure_nonPunycodeDomainName() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "isLock", true,
                "registrarId", "TheRegistrar",
                "domainName", "example.みんな",
                "password", "hi"));
    assertFailureWithMessage(response, "Domain names can only contain a-z, 0-9, '.' and '-'");
  }

  @Test
  void testFailure_noLockParam() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "example.tld",
                "password", "hi"));
    assertFailureWithMessage(response, "Missing key for isLock");
  }

  @Test
  void testFailure_notAllowedOnRegistrar() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setRegistryLockAllowed(false).build());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(response, "Registry lock not allowed for registrar TheRegistrar");
  }

  @Test
  void testFailure_noPassword() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "example.tld",
                "isLock", true));
    assertFailureWithMessage(response, "Incorrect registry lock password for contact");
  }

  @Test
  void testFailure_notEnabledForRegistrarPoc() {
    action = createAction(AuthResult.createUser(UserAuthInfo.create(userWithoutPermission, false)));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "example.tld",
                "isLock", true,
                "password", "hi"));
    assertFailureWithMessage(response, "Incorrect registry lock password for contact");
  }

  @Test
  void testFailure_badPassword() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "example.tld",
                "isLock", true,
                "password", "badPassword"));
    assertFailureWithMessage(response, "Incorrect registry lock password for contact");
  }

  @Test
  void testFailure_invalidDomain() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "bad.tld",
                "isLock", true,
                "password", "hi"));
    assertFailureWithMessage(response, "Domain doesn't exist");
  }

  @Test
  void testSuccess_previousLockUnlocked() throws Exception {
    saveRegistryLock(
        createLock()
            .asBuilder()
            .setLockCompletionTime(clock.nowUtc().minusMinutes(1))
            .setUnlockRequestTime(clock.nowUtc().minusMinutes(1))
            .setUnlockCompletionTime(clock.nowUtc().minusMinutes(1))
            .build());

    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  void testSuccess_previousLockExpired() throws Exception {
    RegistryLock previousLock = saveRegistryLock(createLock());
    String verificationCode = previousLock.getVerificationCode();
    previousLock = getRegistryLockByVerificationCode(verificationCode).get();
    clock.setTo(previousLock.getLockRequestTime().plusHours(2));
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  void testFailure_alreadyPendingLock() {
    saveRegistryLock(createLock());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(
        response, "A pending or completed lock action already exists for example.tld");
  }

  @Test
  void testFailure_alreadyLocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(response, "Domain example.tld is already locked");
  }

  @Test
  void testFailure_alreadyUnlocked() {
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(response, "Domain example.tld is already unlocked");
  }

  @Test
  void testFailure_consoleUser_wrongPassword_noAdmin() {
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of(
                            "TheRegistrar", RegistrarRole.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK))
                    .build())
            .setRegistryLockPassword("hi")
            .build();
    AuthResult consoleAuthResult = AuthResult.createUser(UserAuthInfo.create(consoleUser));
    action = createAction(consoleAuthResult);
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "registrarId", "TheRegistrar",
                "domainName", "example.tld",
                "isLock", true,
                "password", "badPassword"));
    assertFailureWithMessage(response, "Incorrect registry lock password for user");
  }

  private ImmutableMap<String, Object> lockRequest() {
    return fullRequest(true);
  }

  private ImmutableMap<String, Object> unlockRequest() {
    return fullRequest(false);
  }

  private ImmutableMap<String, Object> fullRequest(boolean lock) {
    return ImmutableMap.of(
        "isLock", lock,
        "registrarId", "TheRegistrar",
        "domainName", "example.tld",
        "password", "hi");
  }

  private RegistryLock createLock() {
    Domain domain = loadByForeignKey(Domain.class, "example.tld", clock.nowUtc()).get();
    return new RegistryLock.Builder()
        .setDomainName("example.tld")
        .isSuperuser(false)
        .setVerificationCode(UUID.randomUUID().toString())
        .setRegistrarId("TheRegistrar")
        .setRepoId(domain.getRepoId())
        .setRegistrarPocId("Marla.Singer@crr.com")
        .build();
  }

  private void assertSuccess(Map<String, ?> response, String lockAction, String recipient)
      throws Exception {
    assertThat(response)
        .containsExactly(
            "status", "SUCCESS",
            "results", ImmutableList.of(),
            "message", String.format("Successful %s", lockAction));
    verifyEmail(recipient);
  }

  private void assertFailureWithMessage(Map<String, ?> response, String message) {
    assertThat(response)
        .containsExactly("status", "ERROR", "results", ImmutableList.of(), "message", message);
    verifyNoMoreInteractions(gmailClient);
  }

  private void verifyEmail(String recipient) throws Exception {
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage sentMessage = emailCaptor.getValue();
    assertThat(sentMessage.subject()).matches("Registry (un)?lock verification");
    assertThat(sentMessage.body()).matches(EMAIL_MESSAGE_TEMPLATE);
    assertThat(sentMessage.recipients()).containsExactly(new InternetAddress(recipient));
  }

  private RegistryLockPostAction createAction(AuthResult authResult) {
    return createAction(authResult, ImmutableSet.of("TheRegistrar", "NewRegistrar"));
  }

  private RegistryLockPostAction createAction(
      AuthResult authResult, ImmutableSet<String> accessibleRegistrars) {
    Role role = authResult.userAuthInfo().get().isUserAdmin() ? Role.ADMIN : Role.OWNER;
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            accessibleRegistrars.stream().collect(toImmutableSetMultimap(r -> r, r -> role)));
    JsonActionRunner jsonActionRunner =
        new JsonActionRunner(ImmutableMap.of(), new JsonResponse(new ResponseImpl(mockResponse)));
    DomainLockUtils domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(Alphabets.BASE_58),
            "adminreg",
            new CloudTasksHelper(clock).getTestCloudTasksUtils());
    return new RegistryLockPostAction(
        mockRequest, jsonActionRunner, authResult, registrarAccessor, gmailClient, domainLockUtils);
  }
}
