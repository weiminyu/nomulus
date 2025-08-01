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

package google.registry.flows.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyResourceDoesNotExist;
import static google.registry.flows.domain.DomainFlowUtils.COLLISION_MESSAGE;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.cloneAndLinkReferences;
import static google.registry.flows.domain.DomainFlowUtils.createFeeCreateResponse;
import static google.registry.flows.domain.DomainFlowUtils.getReservationTypes;
import static google.registry.flows.domain.DomainFlowUtils.isAnchorTenant;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.flows.domain.DomainFlowUtils.isValidReservedCreate;
import static google.registry.flows.domain.DomainFlowUtils.validateCreateCommandContactsAndNameservers;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.validateLaunchCreateNotice;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrationPeriod;
import static google.registry.flows.domain.DomainFlowUtils.validateSecDnsExtension;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsNoticeIfAndOnlyIfNeeded;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsPeriodNotEnded;
import static google.registry.flows.domain.DomainFlowUtils.verifyLaunchPhaseMatchesRegistryPhase;
import static google.registry.flows.domain.DomainFlowUtils.verifyNoCodeMarks;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotBlockedByBsa;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyRegistrarIsActive;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.EppResourceUtils.createDomainRepoId;
import static google.registry.model.eppcommon.StatusValue.SERVER_HOLD;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.QUIET_PERIOD;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.model.tld.label.ReservationType.NAME_COLLISION;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainCreateFlowCustomLogic;
import google.registry.flows.custom.DomainCreateFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainCreateFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.DomainFlowUtils.RegistrantProhibitedException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.flows.exceptions.ContactsProhibitedException;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.DomainCreateData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.ReservationType;
import google.registry.model.tmch.ClaimsList;
import google.registry.model.tmch.ClaimsListDao;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * An EPP flow that creates a new domain resource.
 *
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException}
 * @error {@link AllocationTokenFlowUtils.NonexistentAllocationTokenException}
 * @error {@link google.registry.flows.exceptions.OnlyToolCanPassMetadataException}
 * @error {@link ResourceAlreadyExistsForThisClientException}
 * @error {@link ResourceCreateContentionException}
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link DomainCreateFlow.AnchorTenantCreatePeriodException}
 * @error {@link DomainCreateFlow.MustHaveSignedMarksInCurrentPhaseException}
 * @error {@link DomainCreateFlow.NoGeneralRegistrationsInCurrentPhaseException}
 * @error {@link DomainCreateFlow.NoTrademarkedRegistrationsBeforeSunriseException}
 * @error {@link BulkDomainRegisteredForTooManyYearsException}
 * @error {@link ContactsProhibitedException}
 * @error {@link DomainCreateFlow.SignedMarksOnlyDuringSunriseException}
 * @error {@link DomainFlowTmchUtils.NoMarksFoundMatchingDomainException}
 * @error {@link DomainFlowTmchUtils.FoundMarkNotYetValidException}
 * @error {@link DomainFlowTmchUtils.FoundMarkExpiredException}
 * @error {@link DomainFlowTmchUtils.SignedMarkRevokedErrorException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.AcceptedTooLongAgoException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.DomainNameExistsAsTldException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.ClaimsPeriodEndedException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DashesInThirdAndFourthException}
 * @error {@link DomainFlowUtils.DomainLabelBlockedByBsaException}
 * @error {@link DomainFlowUtils.DomainLabelTooLongException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.DuplicateContactForRoleException}
 * @error {@link DomainFlowUtils.EmptyDomainNamePartException}
 * @error {@link DomainFlowUtils.ExceedsMaxRegistrationYearsException}
 * @error {@link DomainFlowUtils.ExpiredClaimException}
 * @error {@link DomainFlowUtils.FeeDescriptionMultipleMatchesException}
 * @error {@link DomainFlowUtils.FeeDescriptionParseException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredDuringEarlyAccessProgramException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.InvalidDsRecordException}
 * @error {@link DomainFlowUtils.InvalidIdnDomainLabelException}
 * @error {@link DomainFlowUtils.InvalidPunycodeException}
 * @error {@link DomainFlowUtils.InvalidTcnIdChecksumException}
 * @error {@link DomainFlowUtils.InvalidTrademarkValidatorException}
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException}
 * @error {@link DomainFlowUtils.MalformedTcnIdException}
 * @error {@link DomainFlowUtils.MaxSigLifeNotSupportedException}
 * @error {@link DomainFlowUtils.MissingAdminContactException}
 * @error {@link DomainFlowUtils.MissingBillingAccountMapException}
 * @error {@link DomainFlowUtils.MissingClaimsNoticeException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.MissingRegistrantException}
 * @error {@link DomainFlowUtils.MissingTechnicalContactException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedForTldException}
 * @error {@link DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverAllowListException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link RegistrantProhibitedException}
 * @error {@link DomainFlowUtils.RegistrarMustBeActiveForThisOperationException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.UnexpectedClaimsNoticeException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainFlowUtils.UnsupportedMarkTypeException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_CREATE)
public final class DomainCreateFlow implements MutatingFlow {

  /** Anchor tenant creates should always be for 2 years, since they get 2 years free. */
  private static final int ANCHOR_TENANT_CREATE_VALID_YEARS = 2;

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject ResourceCommand resourceCommand;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainCreateFlowCustomLogic flowCustomLogic;
  @Inject DomainFlowTmchUtils tmchUtils;
  @Inject DomainPricingLogic pricingLogic;

  @Inject DomainCreateFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeCreateCommandExtension.class,
        SecDnsCreateExtension.class,
        MetadataExtension.class,
        LaunchCreateExtension.class,
        AllocationTokenExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    verifyRegistrarIsActive(registrarId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    DomainCommand.Create command = cloneAndLinkReferences((Create) resourceCommand, now);
    Period period = command.getPeriod();
    verifyUnitIsYears(period);
    int years = period.getValue();
    validateRegistrationPeriod(years);
    verifyResourceDoesNotExist(Domain.class, targetId, now, registrarId);
    // Validate that this is actually a legal domain name on a TLD that the registrar has access to.
    InternetDomainName domainName = validateDomainName(command.getDomainName());
    String domainLabel = domainName.parts().getFirst();
    Tld tld = Tld.get(domainName.parent().toString());
    validateCreateCommandContactsAndNameservers(command, tld, domainName);
    TldState tldState = tld.getTldState(now);
    Optional<LaunchCreateExtension> launchCreate =
        eppInput.getSingleExtension(LaunchCreateExtension.class);
    boolean hasSignedMarks =
        launchCreate.isPresent() && !launchCreate.get().getSignedMarks().isEmpty();
    boolean hasClaimsNotice = launchCreate.isPresent() && launchCreate.get().getNotice() != null;
    if (launchCreate.isPresent()) {
      verifyNoCodeMarks(launchCreate.get());
      validateLaunchCreateNotice(launchCreate.get().getNotice(), domainLabel, isSuperuser, now);
    }
    boolean isSunriseCreate = hasSignedMarks && (tldState == START_DATE_SUNRISE);
    Optional<AllocationToken> allocationToken =
        AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
            registrarId,
            now,
            eppInput.getSingleExtension(AllocationTokenExtension.class),
            tld,
            command.getDomainName(),
            CommandName.CREATE);
    boolean defaultTokenUsed =
        allocationToken.map(t -> t.getTokenType().equals(TokenType.DEFAULT_PROMO)).orElse(false);
    boolean isAnchorTenant =
        isAnchorTenant(
            domainName, allocationToken, eppInput.getSingleExtension(MetadataExtension.class));
    verifyAnchorTenantValidPeriod(isAnchorTenant, years);

    // Superusers can create reserved domains, force creations on domains that require a claims
    // notice without specifying a claims key, ignore the registry phase, and override blocks on
    // registering premium domains.
    if (!isSuperuser) {
      checkAllowedAccessToTld(registrarId, tld.getTldStr());
      checkHasBillingAccount(registrarId, tld.getTldStr());
      boolean isValidReservedCreate = isValidReservedCreate(domainName, allocationToken);
      ClaimsList claimsList = ClaimsListDao.get();
      verifyIsGaOrSpecialCase(
          tld,
          claimsList,
          now,
          domainLabel,
          allocationToken,
          isAnchorTenant,
          isValidReservedCreate,
          hasSignedMarks);
      if (launchCreate.isPresent()) {
        verifyLaunchPhaseMatchesRegistryPhase(tld, launchCreate.get(), now);
      }
      if (!isAnchorTenant && !isValidReservedCreate) {
        verifyNotReserved(domainName, isSunriseCreate);
      }
      if (hasClaimsNotice) {
        verifyClaimsPeriodNotEnded(tld, now);
      }
      if (now.isBefore(tld.getClaimsPeriodEnd())) {
        verifyClaimsNoticeIfAndOnlyIfNeeded(
            domainName, claimsList, hasSignedMarks, hasClaimsNotice);
      }
      verifyPremiumNameIsNotBlocked(targetId, now, registrarId);
      verifySignedMarkOnlyInSunrise(hasSignedMarks, tldState);
    }
    String signedMarkId = null;
    if (hasSignedMarks) {
      // If a signed mark was provided, then it must match the desired domain label. Get the mark
      // at this point so that we can verify it before the "after validation" extension point.
      signedMarkId =
          tmchUtils
              .verifySignedMarks(launchCreate.get().getSignedMarks(), domainLabel, now)
              .getId();
    }
    verifyNotBlockedByBsa(domainName, tld, now, allocationToken);
    flowCustomLogic.afterValidation(
        DomainCreateFlowCustomLogic.AfterValidationParameters.newBuilder()
            .setDomainName(domainName)
            .setYears(years)
            .setSignedMarkId(Optional.ofNullable(signedMarkId))
            .build());
    Optional<FeeCreateCommandExtension> feeCreate =
        eppInput.getSingleExtension(FeeCreateCommandExtension.class);
    FeesAndCredits feesAndCredits =
        pricingLogic.getCreatePrice(
            tld, targetId, now, years, isAnchorTenant, isSunriseCreate, allocationToken);
    validateFeeChallenge(feeCreate, feesAndCredits, defaultTokenUsed);
    Optional<SecDnsCreateExtension> secDnsCreate =
        validateSecDnsExtension(eppInput.getSingleExtension(SecDnsCreateExtension.class));
    DateTime registrationExpirationTime = leapSafeAddYears(now, years);
    String repoId = createDomainRepoId(tm().allocateId(), tld.getTldStr());
    long historyRevisionId = tm().allocateId();
    HistoryEntryId domainHistoryId = new HistoryEntryId(repoId, historyRevisionId);
    historyBuilder.setRevisionId(historyRevisionId);
    // Bill for the create.
    BillingEvent createBillingEvent =
        createBillingEvent(
            tld,
            isAnchorTenant,
            isSunriseCreate,
            isReserved(domainName, isSunriseCreate),
            years,
            feesAndCredits,
            domainHistoryId,
            allocationToken,
            now);
    // Create a new autorenew billing event and poll message starting at the expiration time.
    BillingRecurrence autorenewBillingEvent =
        createAutorenewBillingEvent(
            domainHistoryId, registrationExpirationTime, isAnchorTenant, allocationToken);
    PollMessage.Autorenew autorenewPollMessage =
        createAutorenewPollMessage(domainHistoryId, registrationExpirationTime);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(createBillingEvent, autorenewBillingEvent, autorenewPollMessage);
    // Bill for EAP cost, if any.
    if (!feesAndCredits.getEapCost().isZero()) {
      entitiesToSave.add(createEapBillingEvent(feesAndCredits, createBillingEvent));
    }

    ImmutableSet<ReservationType> reservationTypes = getReservationTypes(domainName);
    ImmutableSet<StatusValue> statuses =
        reservationTypes.contains(NAME_COLLISION)
            ? ImmutableSet.of(SERVER_HOLD)
            : ImmutableSet.of();
    Domain.Builder domainBuilder =
        new Domain.Builder()
            .setCreationRegistrarId(registrarId)
            .setPersistedCurrentSponsorRegistrarId(registrarId)
            .setRepoId(repoId)
            .setIdnTableName(validateDomainNameWithIdnTables(domainName))
            .setRegistrationExpirationTime(registrationExpirationTime)
            .setAutorenewBillingEvent(autorenewBillingEvent.createVKey())
            .setAutorenewPollMessage(autorenewPollMessage.createVKey())
            .setLaunchNotice(hasClaimsNotice ? launchCreate.get().getNotice() : null)
            .setSmdId(signedMarkId)
            .setDsData(secDnsCreate.map(SecDnsCreateExtension::getDsData).orElse(null))
            .setRegistrant(command.getRegistrant())
            .setAuthInfo(command.getAuthInfo())
            .setDomainName(targetId)
            .setNameservers(command.getNameservers().stream().collect(toImmutableSet()))
            .setStatusValues(statuses)
            .setContacts(command.getContacts())
            .addGracePeriod(
                GracePeriod.forBillingEvent(GracePeriodStatus.ADD, repoId, createBillingEvent))
            .setLordnPhase(
                hasSignedMarks
                    ? LordnPhase.SUNRISE
                    : hasClaimsNotice ? LordnPhase.CLAIMS : LordnPhase.NONE);
    Domain domain = domainBuilder.build();
    if (allocationToken.isPresent()
        && allocationToken.get().getTokenType().equals(TokenType.BULK_PRICING)) {
      if (years > 1) {
        throw new BulkDomainRegisteredForTooManyYearsException(allocationToken.get().getToken());
      }
      domain = domain.asBuilder().setCurrentBulkToken(allocationToken.get().createVKey()).build();
    }
    DomainHistory domainHistory =
        buildDomainHistory(domain, tld, now, period, tld.getAddGracePeriodLength());
    if (reservationTypes.contains(NAME_COLLISION)) {
      entitiesToSave.add(
          createNameCollisionOneTimePollMessage(targetId, domainHistory, registrarId, now));
    }
    entitiesToSave.add(domain, domainHistory);
    if (allocationToken.isPresent() && allocationToken.get().getTokenType().isOneTimeUse()) {
      entitiesToSave.add(
          AllocationTokenFlowUtils.redeemToken(
              allocationToken.get(), domainHistory.getHistoryEntryId()));
    }
    if (domain.shouldPublishToDns()) {
      requestDomainDnsRefresh(domain.getDomainName());
    }
    EntityChanges entityChanges =
        flowCustomLogic.beforeSave(
            DomainCreateFlowCustomLogic.BeforeSaveParameters.newBuilder()
                .setNewDomain(domain)
                .setHistoryEntry(domainHistory)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .setYears(years)
                .build());
    persistEntityChanges(entityChanges);

    // If the registrar is participating in tiered pricing promos, return the standard price in the
    // response (even if the actual charged price is less)
    boolean shouldShowDefaultPrice =
        defaultTokenUsed
            && RegistryConfig.getTieredPricingPromotionRegistrarIds().contains(registrarId);
    FeesAndCredits responseFeesAndCredits =
        shouldShowDefaultPrice
            ? pricingLogic.getCreatePrice(
                tld, targetId, now, years, isAnchorTenant, isSunriseCreate, Optional.empty())
            : feesAndCredits;

    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setResData(DomainCreateData.create(targetId, now, registrationExpirationTime))
                .setResponseExtensions(createResponseExtensions(feeCreate, responseFeesAndCredits))
                .build());
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  /**
   * Verifies that signed marks are only sent during sunrise.
   *
   * <p>A trademarked domain name requires either a signed mark or a claims notice. We then need to
   * send out a LORDN message - either a "sunrise" LORDN if we have a signed mark, or a "claims"
   * LORDN if we have a claims notice.
   *
   * <p>This verification prevents us from either sending out a "sunrise" LORDN out of sunrise, or
   * not sending out any LORDN, for a trademarked domain with a signed mark in GA.
   */
  static void verifySignedMarkOnlyInSunrise(boolean hasSignedMarks, TldState tldState)
      throws EppException {
    if (hasSignedMarks && tldState != START_DATE_SUNRISE) {
      throw new SignedMarksOnlyDuringSunriseException();
    }
  }

  /**
   * Verifies anchor tenant creates are only done for {@value ANCHOR_TENANT_CREATE_VALID_YEARS} year
   * periods, as anchor tenants get exactly that many years of free registration.
   */
  static void verifyAnchorTenantValidPeriod(boolean isAnchorTenant, int registrationYears)
      throws EppException {
    if (isAnchorTenant && registrationYears != ANCHOR_TENANT_CREATE_VALID_YEARS) {
      throw new AnchorTenantCreatePeriodException(registrationYears);
    }
  }

  /**
   * Prohibit registrations unless they're in GA or a special case.
   *
   * <p>Non-trademarked names can be registered at any point with a special allocation token
   * registration behavior.
   *
   * <p>Trademarked names require signed marks in sunrise no matter what, and can be registered with
   * a special allocation token behavior in any quiet period that is post-sunrise.
   *
   * <p>Note that "superuser" status isn't tested here - this should only be called for
   * non-superusers.
   */
  private void verifyIsGaOrSpecialCase(
      Tld tld,
      ClaimsList claimsList,
      DateTime now,
      String domainLabel,
      Optional<AllocationToken> allocationToken,
      boolean isAnchorTenant,
      boolean isValidReservedCreate,
      boolean hasSignedMarks)
      throws NoGeneralRegistrationsInCurrentPhaseException,
          MustHaveSignedMarksInCurrentPhaseException,
          NoTrademarkedRegistrationsBeforeSunriseException {
    // We allow general registration during GA.
    TldState currentState = tld.getTldState(now);
    if (currentState.equals(GENERAL_AVAILABILITY)) {
      return;
    }

    // Determine if there should be any behavior dictated by the allocation token
    RegistrationBehavior behavior =
        allocationToken
            .map(AllocationToken::getRegistrationBehavior)
            .orElse(RegistrationBehavior.DEFAULT);
    // Bypass most TLD state checks if that behavior is specified by the token
    if (behavior.equals(RegistrationBehavior.BYPASS_TLD_STATE)
        || behavior.equals(RegistrationBehavior.ANCHOR_TENANT)) {
      // Non-trademarked names with the state check bypassed are always available
      if (claimsList.getClaimKey(domainLabel).isEmpty()) {
        return;
      }
      if (!currentState.equals(START_DATE_SUNRISE)) {
        // Trademarked domains cannot be registered until after the sunrise period has ended, unless
        // a valid signed mark is provided. Signed marks can only be provided during sunrise.
        // Thus, when bypassing TLD state checks, a post-sunrise state is always fine.
        if (tld.getTldStateTransitions().headMap(now).containsValue(START_DATE_SUNRISE)) {
          return;
        } else {
          // If sunrise hasn't happened yet, trademarked domains are unavailable
          throw new NoTrademarkedRegistrationsBeforeSunriseException(domainLabel);
        }
      }
    }

    // Otherwise, signed marks are necessary and sufficient in the sunrise period
    if (currentState.equals(START_DATE_SUNRISE)) {
      if (!hasSignedMarks) {
        throw new MustHaveSignedMarksInCurrentPhaseException();
      }
      return;
    }

    // Anchor tenant overrides any remaining considerations to allow registration
    if (isAnchorTenant) {
      return;
    }

    // We allow creates of specifically reserved domain names during quiet periods
    if (currentState.equals(QUIET_PERIOD)) {
      if (isValidReservedCreate) {
        return;
      }
    }
    // All other phases do not allow registration
    throw new NoGeneralRegistrationsInCurrentPhaseException();
  }

  private DomainHistory buildDomainHistory(
      Domain domain, Tld tld, DateTime now, Period period, Duration addGracePeriod) {
    // We ignore prober transactions
    if (tld.getTldType() == TldType.REAL) {
      historyBuilder.setDomainTransactionRecords(
          ImmutableSet.of(
              DomainTransactionRecord.create(
                  tld.getTldStr(),
                  now.plus(addGracePeriod),
                  TransactionReportField.netAddsFieldFromYears(period.getValue()),
                  1)));
    }
    return historyBuilder.setType(DOMAIN_CREATE).setPeriod(period).setDomain(domain).build();
  }

  private BillingEvent createBillingEvent(
      Tld tld,
      boolean isAnchorTenant,
      boolean isSunriseCreate,
      boolean isReserved,
      int years,
      FeesAndCredits feesAndCredits,
      HistoryEntryId domainHistoryId,
      Optional<AllocationToken> allocationToken,
      DateTime now) {
    ImmutableSet.Builder<Flag> flagsBuilder = new ImmutableSet.Builder<>();
    // Sunrise and anchor tenancy are orthogonal tags and thus both can be present together.
    if (isSunriseCreate) {
      flagsBuilder.add(Flag.SUNRISE);
    }
    if (isAnchorTenant) {
      flagsBuilder.add(Flag.ANCHOR_TENANT);
    } else if (isReserved) {
      // Don't add this flag if the domain is an anchor tenant (which are also reserved); only add
      // it if it's reserved for other reasons.
      flagsBuilder.add(Flag.RESERVED);
    }
    return new BillingEvent.Builder()
        .setReason(Reason.CREATE)
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setPeriodYears(years)
        .setCost(feesAndCredits.getCreateCost())
        .setEventTime(now)
        .setAllocationToken(allocationToken.map(AllocationToken::createVKey).orElse(null))
        .setBillingTime(
            now.plus(
                isAnchorTenant
                    ? tld.getAnchorTenantAddGracePeriodLength()
                    : tld.getAddGracePeriodLength()))
        .setFlags(flagsBuilder.build())
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private BillingRecurrence createAutorenewBillingEvent(
      HistoryEntryId domainHistoryId,
      DateTime registrationExpirationTime,
      boolean isAnchorTenant,
      Optional<AllocationToken> allocationToken) {
    // Non-standard renewal behaviors can occur for anchor tenants (always NONPREMIUM pricing) or if
    // explicitly configured in the token (either NONPREMIUM or directly SPECIFIED). Use DEFAULT if
    // none is configured.
    RenewalPriceBehavior renewalPriceBehavior =
        isAnchorTenant
            ? RenewalPriceBehavior.NONPREMIUM
            : allocationToken
                .map(AllocationToken::getRenewalPriceBehavior)
                .orElse(RenewalPriceBehavior.DEFAULT);
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setEventTime(registrationExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setDomainHistoryId(domainHistoryId)
        .setRenewalPriceBehavior(renewalPriceBehavior)
        .setRenewalPrice(allocationToken.flatMap(AllocationToken::getRenewalPrice).orElse(null))
        .build();
  }

  private Autorenew createAutorenewPollMessage(
      HistoryEntryId domainHistoryId, DateTime registrationExpirationTime) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setEventTime(registrationExpirationTime)
        .setMsg("Domain was auto-renewed.")
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private static BillingEvent createEapBillingEvent(
      FeesAndCredits feesAndCredits, BillingEvent createBillingEvent) {
    return new BillingEvent.Builder()
        .setReason(Reason.FEE_EARLY_ACCESS)
        .setTargetId(createBillingEvent.getTargetId())
        .setRegistrarId(createBillingEvent.getRegistrarId())
        .setPeriodYears(1)
        .setCost(feesAndCredits.getEapCost())
        .setEventTime(createBillingEvent.getEventTime())
        .setBillingTime(createBillingEvent.getBillingTime())
        .setFlags(createBillingEvent.getFlags())
        .setDomainHistoryId(createBillingEvent.getHistoryEntryId())
        .build();
  }

  private static PollMessage.OneTime createNameCollisionOneTimePollMessage(
      String domainName, HistoryEntry historyEntry, String registrarId, DateTime now) {
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(registrarId)
        .setEventTime(now)
        .setMsg(COLLISION_MESSAGE) // Remind the registrar of the name collision policy.
        .setResponseData(
            ImmutableList.of(
                DomainPendingActionNotificationResponse.create(
                    domainName, true, historyEntry.getTrid(), now)))
        .setHistoryEntry(historyEntry)
        .build();
  }

  private static ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      Optional<FeeCreateCommandExtension> feeCreate, FeesAndCredits feesAndCredits) {
    return feeCreate
        .map(
            feeCreateCommandExtension ->
                ImmutableList.of(
                    createFeeCreateResponse(feeCreateCommandExtension, feesAndCredits)))
        .orElseGet(ImmutableList::of);
  }

  /** Signed marks are only allowed during sunrise. */
  static class SignedMarksOnlyDuringSunriseException extends CommandUseErrorException {
    public SignedMarksOnlyDuringSunriseException() {
      super("Signed marks are only allowed during sunrise");
    }
  }

  /** The current registry phase does not allow for general registrations. */
  static class NoGeneralRegistrationsInCurrentPhaseException extends CommandUseErrorException {
    public NoGeneralRegistrationsInCurrentPhaseException() {
      super("The current registry phase does not allow for general registrations");
    }
  }

  /** The current registry phase allows registrations only with signed marks. */
  static class MustHaveSignedMarksInCurrentPhaseException extends CommandUseErrorException {
    public MustHaveSignedMarksInCurrentPhaseException() {
      super("The current registry phase requires a signed mark for registrations");
    }
  }

  /** Trademarked domains cannot be registered before the sunrise period. */
  static class NoTrademarkedRegistrationsBeforeSunriseException
      extends ParameterValuePolicyErrorException {
    public NoTrademarkedRegistrationsBeforeSunriseException(String domainLabel) {
      super(
          String.format(
              "The trademarked label %s cannot be registered before the sunrise period.",
              domainLabel));
    }
  }

  /** Anchor tenant domain create is for the wrong number of years. */
  static class AnchorTenantCreatePeriodException extends ParameterValuePolicyErrorException {
    public AnchorTenantCreatePeriodException(int invalidYears) {
      super(
          String.format(
              "Anchor tenant domain creates must be for a period of %s years, got %s instead.",
              ANCHOR_TENANT_CREATE_VALID_YEARS, invalidYears));
    }
  }

  /** Bulk pricing domain registered for too many years. */
  static class BulkDomainRegisteredForTooManyYearsException extends CommandUseErrorException {
    public BulkDomainRegisteredForTooManyYearsException(String token) {
      super(
          String.format(
              "The bulk token %s cannot be used to register names for longer than 1 year.", token));
    }
  }
}
