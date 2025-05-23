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

package google.registry.reporting.billing;

import static google.registry.reporting.ReportingModule.PARAM_YEAR_MONTH;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.Job;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.batch.CloudTasksUtils;
import google.registry.config.RegistryConfig.Config;
import google.registry.reporting.ReportingModule;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.io.IOException;
import org.joda.time.YearMonth;

/**
 * Uploads the results of the {@link google.registry.beam.billing.InvoicingPipeline}.
 *
 * <p>This relies on the retry semantics in {@code queue.xml} to ensure proper upload, in spite of
 * fluctuations in generation timing.
 *
 * @see <a
 *     href=https://cloud.google.com/dataflow/docs/reference/rest/v1b3/projects.jobs#Job.JobState>
 *     Job States</a>
 */
@Action(
    service = GaeService.BACKEND,
    path = PublishInvoicesAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class PublishInvoicesAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String JOB_DONE = "JOB_STATE_DONE";
  private static final String JOB_FAILED = "JOB_STATE_FAILED";

  private final String projectId;
  private final String jobRegion;
  private final String jobId;
  private final BillingEmailUtils emailUtils;
  private final Dataflow dataflow;
  private final Response response;
  private final YearMonth yearMonth;
  private final CloudTasksUtils cloudTasksUtils;

  @Inject
  PublishInvoicesAction(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String jobRegion,
      @Parameter(ReportingModule.PARAM_JOB_ID) String jobId,
      BillingEmailUtils emailUtils,
      Dataflow dataflow,
      Response response,
      YearMonth yearMonth,
      CloudTasksUtils cloudTasksUtils) {
    this.projectId = projectId;
    this.jobRegion = jobRegion;
    this.jobId = jobId;
    this.emailUtils = emailUtils;
    this.dataflow = dataflow;
    this.response = response;
    this.yearMonth = yearMonth;
    this.cloudTasksUtils = cloudTasksUtils;
  }

  static final String PATH = "/_dr/task/publishInvoices";

  @Override
  public void run() {
    try {
      logger.atInfo().log("Starting publish job.");
      Job job = dataflow.projects().locations().jobs().get(projectId, jobRegion, jobId).execute();
      String state = job.getCurrentState();
      switch (state) {
        case JOB_DONE -> {
          logger.atInfo().log("Dataflow job %s finished successfully, publishing results.", jobId);
          response.setStatus(SC_OK);
          enqueueCopyDetailReportsTask();
          emailUtils.emailOverallInvoice();
        }
        case JOB_FAILED -> {
          logger.atSevere().log("Dataflow job %s finished unsuccessfully.", jobId);
          response.setStatus(SC_NO_CONTENT);
          emailUtils.sendAlertEmail(
              String.format("Dataflow job %s ended in status failure.", jobId));
        }
        default -> {
          logger.atInfo().log("Job in non-terminal state %s, retrying:", state);
          response.setStatus(SC_SERVICE_UNAVAILABLE);
        }
      }
    } catch (IOException e) {
      emailUtils.sendAlertEmail(String.format("Publish action failed due to %s", e.getMessage()));
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Template launch failed: %s", e.getMessage()));
    }
  }

  private void enqueueCopyDetailReportsTask() {
    cloudTasksUtils.enqueue(
        BillingModule.CRON_QUEUE,
        cloudTasksUtils.createTask(
            CopyDetailReportsAction.class,
            POST,
            ImmutableMultimap.of(PARAM_YEAR_MONTH, yearMonth.toString())));
  }
}
