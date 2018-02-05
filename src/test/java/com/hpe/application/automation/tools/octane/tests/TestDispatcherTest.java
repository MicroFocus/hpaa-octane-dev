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

import com.hpe.application.automation.tools.model.OctaneServerSettingsModel;
import com.hpe.application.automation.tools.octane.OctaneServerMock;
import com.hpe.application.automation.tools.octane.client.RetryModel;
import com.hpe.application.automation.tools.octane.configuration.ConfigurationService;
import hudson.FilePath;
import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.Maven;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.util.Secret;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.ToolInstallations;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings({"squid:S2699", "squid:S3658", "squid:S2259", "squid:S1872", "squid:S2925", "squid:S109", "squid:S1607", "squid:S2698"})
public class TestDispatcherTest {

	private static int octaneServerMockPort;
	private static String sharedSpaceId = TestDispatcherTest.class.getSimpleName();
	private static TestApiPreflightHandler testApiPreflightHandler = new TestApiPreflightHandler();
	private static TestApiPushTestsResultHandler testApiPushTestsResultHandler = new TestApiPushTestsResultHandler();
	private static AbstractProject project;
	private static TestQueue queue;

	@ClassRule
	public static final JenkinsRule rule = new JenkinsRule();


	@BeforeClass
	public static void initClass() throws Exception {
		System.setProperty("MQM.TestDispatcher.Period", "1000");

		//  prepare project
		Maven.MavenInstallation mavenInstallation = ToolInstallations.configureMaven3();
		project = rule.createFreeStyleProject("TestDispatcher");
		((FreeStyleProject) project).getBuildersList().add(new Maven(String.format("--settings \"%s\\conf\\settings.xml\" install -Dmaven.repo.local=\"%s\\m2-temp\"",
				System.getenv("MAVEN_HOME"), System.getenv("TEMP")), mavenInstallation.getName(), null, null, "-Dmaven.test.failure.ignore=true"));
		((FreeStyleProject) project).getPublishersList().add(new JUnitResultArchiver("**/target/surefire-reports/*.xml"));
		project.setScm(new CopyResourceSCM("/helloWorldRoot"));

		//  prepare Octane Server Mock
		OctaneServerMock octaneServerMock = OctaneServerMock.getInstance();
		octaneServerMockPort = octaneServerMock.getPort();
		octaneServerMock.addTestSpecificHandler(testApiPreflightHandler);
		octaneServerMock.addTestSpecificHandler(testApiPushTestsResultHandler);

		//  configure plugin for the server
		OctaneServerSettingsModel model = new OctaneServerSettingsModel(
				"http://127.0.0.1:" + octaneServerMockPort + "/ui?p=" + sharedSpaceId,
				"username",
				Secret.fromString("password"),
				"");
		ConfigurationService.configurePlugin(model);

		TestDispatcher testDispatcher = ExtensionUtil.getInstance(rule, TestDispatcher.class);
		queue = new TestQueue();
		testDispatcher._setTestResultQueue(queue);
		queue.waitForTicks(1); // needed to avoid occasional interaction with the client we just overrode (race condition)

		RetryModel retryModel = new RetryModel();
		testDispatcher._setRetryModel(retryModel);
	}

	@AfterClass
	public static void cleanup() {
		OctaneServerMock octaneServerMock = OctaneServerMock.getInstance();
		octaneServerMock.removeTestSpecificHandler(testApiPreflightHandler);
		octaneServerMock.removeTestSpecificHandler(testApiPushTestsResultHandler);
	}

	@Test
	public void testDispatcher() throws Exception {
		testApiPreflightHandler.respondWithError = false;
		testApiPreflightHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.respondWithErrorFailsNumber = 0;
		testApiPushTestsResultHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.testResults.clear();

		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(4);
		assertEquals(1, testApiPreflightHandler.lastSessionHits);
		assertEquals(testApiPushTestsResultHandler.testResults.get(0), IOUtils.toString(new FileInputStream(new File(build.getRootDir(), "mqmTests.xml"))));
		verifyAudit(false, build, true);
		testApiPushTestsResultHandler.testResults.clear();
		testApiPreflightHandler.lastSessionHits = 0;

		FreeStyleBuild build2 = executeBuild();
		queue.waitForTicks(4);
		assertEquals(1, testApiPreflightHandler.lastSessionHits);
		assertEquals(testApiPushTestsResultHandler.testResults.get(0), IOUtils.toString(new FileInputStream(new File(build2.getRootDir(), "mqmTests.xml"))));
		verifyAudit(false, build2, true);
		assertEquals(0, queue.size());
		testApiPushTestsResultHandler.testResults.clear();
		testApiPreflightHandler.lastSessionHits = 0;
	}

	@Test
	public void testDispatcherBatch() throws Exception {
		FreeStyleBuild build1 = ((FreeStyleProject) project).scheduleBuild2(0).get();
		FreeStyleBuild build2 = ((FreeStyleProject) project).scheduleBuild2(0).get();
		FreeStyleBuild build3 = ((FreeStyleProject) project).scheduleBuild2(0).get();
		queue.add(Arrays.asList(build1, build2, build3));
		queue.waitForTicks(6);

		assertEquals(3, testApiPreflightHandler.lastSessionHits);
		assertEquals(testApiPushTestsResultHandler.testResults.get(0), IOUtils.toString(new FileInputStream(new File(build1.getRootDir(), "mqmTests.xml"))));
		assertEquals(testApiPushTestsResultHandler.testResults.get(1), IOUtils.toString(new FileInputStream(new File(build2.getRootDir(), "mqmTests.xml"))));
		assertEquals(testApiPushTestsResultHandler.testResults.get(2), IOUtils.toString(new FileInputStream(new File(build3.getRootDir(), "mqmTests.xml"))));
		assertEquals(0, queue.size());

		verifyAudit(false, build1, true);
		verifyAudit(false, build2, true);
		verifyAudit(false, build3, true);

		testApiPreflightHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.testResults.clear();
	}

	@Test
	public void testDispatcherSharedSpaceFailure() throws Exception {
		testApiPreflightHandler.lastSessionHits = 0;
		testApiPreflightHandler.respondWithError = true;
		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(4);
		//System.out.println(String.format("OUR PRINT OUT 3 seconds: %s", testDispatcher._getTestResultQueue().periodIndex));
		assertEquals(1, testApiPreflightHandler.lastSessionHits);
		verifyAudit(false, build);
		// starting quite period 3 seconds

		executeBuild();
		queue.waitForTicks(4);
		//System.out.println(String.format("OUR PRINT OUT (tarting quite period 10 seconds): %s", testDispatcher._getTestResultQueue().periodIndex));

		assertEquals(2, testApiPreflightHandler.lastSessionHits);
		verifyAudit(false, build);

		//quite period 10 seconds
		executeBuild();
		queue.waitForTicks(4);
		//System.out.println(String.format("OUR PRINT OUT (quite period  2 minutes): %s", testDispatcher._getTestResultQueue().periodIndex));

		assertEquals(3, testApiPreflightHandler.lastSessionHits);
		verifyAudit(false, build);

		//quite period 2 minutes
		executeBuild();
		queue.waitForTicks(4);

		//System.out.println(String.format("Entering validation", testDispatcher._getTestResultQueue().periodIndex));
		//enter long quite period

		assertEquals(4, testApiPreflightHandler.lastSessionHits);
		//assertEquals(4, queue.size());

		testApiPreflightHandler.lastSessionHits = 0;
		testApiPreflightHandler.respondWithError = false;
		testApiPushTestsResultHandler.testResults.clear();
	}

	@Test
	public void testDispatcherBodyFailure() throws Exception {
		// body post fails for the first time, succeeds afterwards
		//
		testApiPreflightHandler.respondWithError = false;
		testApiPreflightHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.respondWithErrorFailsNumber = 1;
		testApiPushTestsResultHandler.testResults.clear();
		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(4);
		assertEquals(2, testApiPreflightHandler.lastSessionHits);
		assertEquals(2, testApiPushTestsResultHandler.lastSessionHits);
		assertEquals(testApiPushTestsResultHandler.testResults.get(0), IOUtils.toString(new FileInputStream(new File(build.getRootDir(), "mqmTests.xml"))));
		Thread.sleep(2000);
		verifyAudit(false, build, false, true);

		assertEquals(0, queue.size());
		assertEquals(0, queue.getDiscards());

		// body post fails for two consecutive times
		//
		testApiPreflightHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.respondWithErrorFailsNumber = 2;
		testApiPushTestsResultHandler.testResults.clear();
		build = executeBuild();
		queue.waitForTicks(4);
		assertEquals(2, testApiPreflightHandler.lastSessionHits);
		assertEquals(2, testApiPushTestsResultHandler.lastSessionHits);
		assertEquals(0, testApiPushTestsResultHandler.testResults.size());
		verifyAudit(false, build, false, false);

		assertEquals(0, queue.size());
		assertEquals(1, queue.getDiscards());
	}

	@Test
	public void testDispatchMatrixBuild() throws Exception {
		AbstractProject tmp = project;

		//  prepare Matrix project
		Maven.MavenInstallation mavenInstallation = ToolInstallations.configureMaven3();
		project = rule.createProject(MatrixProject.class, "TestDispatcherMatrix");
		((MatrixProject) project).setAxes(new AxisList(new Axis("osType", "Linux", "Windows")));
		((MatrixProject) project).getBuildersList().add(new Maven(String.format("--settings \"%s\\conf\\settings.xml\" install -Dmaven.repo.local=\"%s\\m2-temp\"",
				System.getenv("MAVEN_HOME"), System.getenv("TEMP")), mavenInstallation.getName(), null, null, "-Dmaven.test.failure.ignore=true"));
		((MatrixProject) project).getPublishersList().add(new JUnitResultArchiver("**/target/surefire-reports/*.xml"));
		project.setScm(new CopyResourceSCM("/helloWorldRoot"));

		testApiPreflightHandler.respondWithError = false;
		testApiPreflightHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.respondWithErrorFailsNumber = 0;
		testApiPushTestsResultHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.testResults.clear();

		MatrixBuild matrixBuild = ((MatrixProject) project).scheduleBuild2(0).get();
		for (MatrixRun run : matrixBuild.getExactRuns()) {
			queue.add("TestDispatcherMatrix/" + run.getParent().getName(), run.getNumber());
		}
		queue.waitForTicks(4);
		for (int i = 0; i < matrixBuild.getExactRuns().size(); i++) {
			MatrixRun run = matrixBuild.getExactRuns().get(i);
			assertEquals(2, testApiPreflightHandler.lastSessionHits);
			assertEquals(testApiPushTestsResultHandler.testResults.get(i), IOUtils.toString(new FileInputStream(new File(run.getRootDir(), "mqmTests.xml"))));
			verifyAudit(false, run, true);
		}

		assertEquals(0, queue.size());

		project = tmp;
	}

	@Test
	@Ignore
	public void testDispatcherTemporarilyUnavailable() throws Exception {
		testApiPreflightHandler.respondWithError = false;
		testApiPreflightHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.respondWithErrorFailsNumber = 0;
		testApiPushTestsResultHandler.lastSessionHits = 0;
		testApiPushTestsResultHandler.testResults.clear();
		queue.waitForTicks(1);

		//  one successful push
		FreeStyleBuild build1 = executeBuild();
		queue.waitForTicks(4);

		//  session of failures
		testApiPushTestsResultHandler.respondWithErrorFailsNumber = 5;
		FreeStyleBuild build2 = executeBuild();
		queue.waitForTicks(4);

		assertEquals(3, testApiPreflightHandler.lastSessionHits);           // actually should be 7
		assertEquals(testApiPushTestsResultHandler.testResults.get(0), IOUtils.toString(new FileInputStream(new File(build1.getRootDir(), "mqmTests.xml"))));
		verifyAudit(false, build1, true);

		assertEquals(7, testApiPushTestsResultHandler.lastSessionHits);
		assertEquals(testApiPushTestsResultHandler.testResults.get(1), IOUtils.toString(new FileInputStream(new File(build2.getRootDir(), "mqmTests.xml"))));
		Thread.sleep(1000);//sleep allows to all audits to be written
		verifyAudit(true, build2, false, false, false, false, false, true);

		assertEquals(0, queue.size());
	}

	private FreeStyleBuild executeBuild() throws ExecutionException, InterruptedException {
		FreeStyleBuild build = ((FreeStyleProject) project).scheduleBuild2(0).get();
		queue.add(build.getProject().getName(), build.getNumber());
		return build;
	}

	private void verifyAudit(boolean unavailableIfFailed, AbstractBuild build, boolean... statuses) throws IOException, InterruptedException {
		FilePath auditFile = new FilePath(new File(build.getRootDir(), TestDispatcher.TEST_AUDIT_FILE));
		JSONArray audits;
		if (statuses.length > 0) {
			assertTrue(auditFile.exists());
			InputStream is = auditFile.read();
			audits = JSONArray.fromObject(IOUtils.toString(is, "UTF-8"));
			IOUtils.closeQuietly(is);
		} else {
			assertFalse(auditFile.exists());
			audits = new JSONArray();
		}
		assertEquals(statuses.length, audits.size());
		for (int i = 0; i < statuses.length; i++) {
			JSONObject audit = audits.getJSONObject(i);
			assertEquals("http://127.0.0.1:" + octaneServerMockPort, audit.getString("location"));
			assertEquals(sharedSpaceId, audit.getString("sharedSpace"));
			assertEquals(statuses[i], audit.getBoolean("pushed"));
			if (statuses[i]) {
				assertEquals(1L, audit.getLong("id"));
			}
			if (!statuses[i] && unavailableIfFailed) {
				assertTrue(audit.getBoolean("temporarilyUnavailable"));
			} else {
				assertFalse(audit.containsKey("temporarilyUnavailable"));
			}
			assertNotNull(audit.getString("date"));
		}
	}

	private static final class TestApiPreflightHandler extends OctaneServerMock.TestSpecificHandler {
		private int lastSessionHits = 0;
		private boolean respondWithError = false;

		@Override
		public boolean ownsUrlToProcess(String url) {
			return url.endsWith("/analytics/ci/servers/tests-result-preflight-base64") ||
					url.endsWith("/jobs/" + Base64.encodeBase64String(project.getName().getBytes()) + "/tests-result-preflight");
		}

		@Override
		public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
			if (baseRequest.getPathInfo().endsWith("/analytics/ci/servers/tests-result-preflight-base64")) {
				response.setStatus(HttpServletResponse.SC_OK);
			} else if (baseRequest.getPathInfo().endsWith("/jobs/" + Base64.encodeBase64String(project.getName().getBytes()) + "/tests-result-preflight")) {
				if (respondWithError) {
					response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				} else {
					response.setStatus(HttpServletResponse.SC_OK);
					response.getWriter().write(String.valueOf(true));
				}
				lastSessionHits++;
			}
		}
	}

	private static final class TestApiPushTestsResultHandler extends OctaneServerMock.TestSpecificHandler {
		private List<String> testResults = new LinkedList<>();
		private int lastSessionHits = 0;
		private int respondWithErrorFailsNumber = 0;

		@Override
		public boolean ownsUrlToProcess(String url) {
			return ("/internal-api/shared_spaces/" + sharedSpaceId + "/analytics/ci/test-results").equals(url);
		}

		@Override
		public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
			if (respondWithErrorFailsNumber == 0) {
				testResults.add(getBodyAsString(baseRequest));
				response.setStatus(HttpServletResponse.SC_OK);
				response.getWriter().write(String.valueOf(1L));
			} else {
				response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				respondWithErrorFailsNumber--;
			}
			lastSessionHits++;
		}
	}
}
