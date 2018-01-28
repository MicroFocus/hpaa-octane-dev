/*
 * © Copyright 2013 EntIT Software LLC
 *  Certain versions of software and/or documents (“Material”) accessible here may contain branding from
 *  Hewlett-Packard Company (now HP Inc.) and Hewlett Packard Enterprise Company.  As of September 1, 2017,
 *  the Material is now offered by Micro Focus, a separately owned and operated company.  Any reference to the HP
 *  and Hewlett Packard Enterprise/HPE marks is historical in nature, and the HP and Hewlett Packard Enterprise/HPE
 *  marks are the property of their respective owners.
 * __________________________________________________________________
 * MIT License
 *
 * Copyright (c) 2018 Micro Focus Company, L.P.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ___________________________________________________________________
 *
 */

package com.hpe.application.automation.tools.octane.tests;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.dto.configuration.OctaneConfiguration;
import com.hp.octane.integrations.dto.connectivity.OctaneResponse;
import com.hpe.application.automation.tools.octane.tests.build.BuildHandlerUtils;
import com.hp.mqm.client.exception.FileNotFoundException;
import com.hp.mqm.client.exception.RequestException;
import com.hp.mqm.client.exception.TemporarilyUnavailableException;
import com.hpe.application.automation.tools.octane.ResultQueue;
import com.hpe.application.automation.tools.octane.client.RetryModel;
import com.hpe.application.automation.tools.octane.configuration.ConfigurationService;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.*;
import hudson.util.TimeUnit2;
import jenkins.YesNoMaybe;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.Date;

@Extension(dynamicLoadable = YesNoMaybe.NO)
public class TestDispatcher extends AbstractSafeLoggingAsyncPeriodWork {
	private static Logger logger = LogManager.getLogger(TestDispatcher.class);
	private static ObjectMapper objectMapper = new ObjectMapper();

	static final String TEST_AUDIT_FILE = "mqmTests_audit.json";

	@Inject
	private RetryModel retryModel;

	private ResultQueue queue;

	public TestDispatcher() {
		super("MQM Test Dispatcher");
	}

	@Override
	protected void doExecute(TaskListener listener) throws IOException, InterruptedException {
		if (queue.peekFirst() == null) {
			return;
		}
		if (retryModel.isQuietPeriod()) {
			logger.info("There are pending test results, but we are in quiet period");
			return;
		}

		OctaneConfiguration configuration = OctaneSDK.getInstance().getPluginServices().getOctaneConfiguration();
		ResultQueue.QueueItem item;
		while ((item = queue.peekFirst()) != null) {
			Job project = (Job) Jenkins.getInstance().getItemByFullName(item.getProjectName());
			if (project == null) {
				logger.warn("Project [" + item.getProjectName() + "] no longer exists, pending test results can't be submitted");
				queue.remove();
				continue;
			}
			Run build = project.getBuildByNumber(item.getBuildNumber());
			if (build == null) {
				logger.warn("Build [" + item.getProjectName() + "#" + item.getBuildNumber() + "] no longer exists, pending test results can't be submitted");
				queue.remove();
				continue;
			}

			boolean needTestResult = OctaneSDK.getInstance().getTestsService().isTestsResultRelevant(ConfigurationService.getModel().getIdentity(), BuildHandlerUtils.getJobCiId(build));

			if (needTestResult) {
				try {
					String id = null;
					try {
						File resultFile = new File(build.getRootDir(), TestListener.TEST_RESULT_FILE);
						OctaneResponse response = OctaneSDK.getInstance().getTestsService().pushTestsResult(new FileInputStream(resultFile));
						TestsResultPushResponseDTO responseDTO = objectMapper.readValue(response.getBody(), TestsResultPushResponseDTO.class);
						id = responseDTO.id;
					} catch (TemporarilyUnavailableException e) {
						logger.warn("Server temporarily unavailable, will try later", e);
						audit(configuration, build, null, true);
						break;
					} catch (RequestException e) {
						logger.warn("Failed to submit test results [" + build.getParent().getName() + "#" + build.getNumber() + "]", e);
					}

					if (id != null) {
						logger.info("Successfully pushed test results of build [" + item.getProjectName() + "#" + item.getBuildNumber() + "]");
						queue.remove();
					} else {
						logger.warn("Failed to push test results of build [" + item.getProjectName() + "#" + item.getBuildNumber() + "]");
						if (!queue.failed()) {
							logger.warn("Maximum number of attempts reached, operation will not be re-attempted for this build");
						}
					}
					audit(configuration, build, id, false);
				} catch (FileNotFoundException e) {
					logger.warn("File no longer exists, failed to push test results of build [" + item.getProjectName() + "#" + item.getBuildNumber() + "]");
					queue.remove();
				}
			} else {
				logger.info("Test result not needed for build [" + item.getProjectName() + "#" + item.getBuildNumber() + "]");
				queue.remove();
			}
		}
	}

	private void audit(OctaneConfiguration octaneConfiguration, Run build, String id, boolean temporarilyUnavailable) throws IOException, InterruptedException {
		FilePath auditFile = new FilePath(new File(build.getRootDir(), TEST_AUDIT_FILE));
		JSONArray audit;
		if (auditFile.exists()) {
			InputStream is = auditFile.read();
			audit = JSONArray.fromObject(IOUtils.toString(is, "UTF-8"));
			IOUtils.closeQuietly(is);
		} else {
			audit = new JSONArray();
		}
		JSONObject event = new JSONObject();
		event.put("id", id);
		event.put("pushed", id != null);
		event.put("date", DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(new Date()));
		event.put("location", octaneConfiguration.getUrl());
		event.put("sharedSpace", octaneConfiguration.getSharedSpace());
		if (temporarilyUnavailable) {
			event.put("temporarilyUnavailable", true);
		}
		audit.add(event);
		auditFile.write(audit.toString(), "UTF-8");
	}

	@Override
	public long getRecurrencePeriod() {
		String value = System.getProperty("MQM.TestDispatcher.Period");
		if (!StringUtils.isEmpty(value)) {
			return Long.valueOf(value);
		}
		return TimeUnit2.SECONDS.toMillis(10);
	}

	@Inject
	public void setTestResultQueue(TestsResultQueue queue) {
		this.queue = queue;
	}

	void _setTestResultQueue(ResultQueue queue) {
		this.queue = queue;
	}

	void _setRetryModel(RetryModel retryModel) {
		this.retryModel = retryModel;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
	private static final class TestsResultPushResponseDTO {
		private String id;
		private String status;
		private Boolean fromOlderPush;
		private String until;
	}
}
