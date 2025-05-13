/*
 * The MIT License
 *
 * Copyright 2013 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.build_token_root;

import com.cloudbees.hudson.plugins.folder.Folder;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.queue.QueueTaskFuture;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@SuppressWarnings("deprecation") // RunList.size, BuildAuthorizationToken, AbstractItem.getParent snafu
@WithJenkins
class BuildRootActionTest {

    private JenkinsRule j;
    private final LogRecorder logging = new LogRecorder().record(BuildRootAction.class, Level.ALL);

    @BeforeEach
    void noAnonymousReadAccess(JenkinsRule j) {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().toAuthenticated());
    }

    @Test
    void build() throws Exception {
        testBuild(j.createFreeStyleProject("p"));
    }

    private <JobT extends Job<JobT, RunT> & ParameterizedJobMixIn.ParameterizedJob, RunT extends Run<JobT, RunT>> void testBuild(JobT p) throws Exception {
        setAuthToken(p);
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.assertFails(p.getUrl() + "build?token=secret", HttpURLConnection.HTTP_FORBIDDEN);
        j.waitUntilNoActivity();
        assertEquals(0, p.getBuilds().size());
        Page page = wc.goTo("buildByToken/build?job=" + p.getFullName() + "&token=secret&delay=0sec", null);
        assertCreated(page);
        assertEquals(1, p.getBuilds().size());
        page = wc.goTo("buildByToken/build?job=" + p.getFullName() + "&token=secret&delay=0sec", null);
        assertCreated(page);
        assertEquals(2, p.getBuilds().size());
        wc.assertFails("buildByToken/build?job=" + p.getFullName() + "&token=socket&delay=0sec", HttpURLConnection.HTTP_FORBIDDEN);
        j.waitUntilNoActivity();
        assertEquals(2, p.getBuilds().size());
    }

    private <JobT extends Job<JobT, RunT> & ParameterizedJobMixIn.ParameterizedJob, RunT extends Run<JobT, RunT>> void setAuthToken(JobT p) throws Exception {
        // TODO should this be a method in ParameterizedJob?
        Field authTokenF = (p instanceof AbstractProject ? AbstractProject.class : p.getClass()).getDeclaredField("authToken");
        authTokenF.setAccessible(true);
        authTokenF.set(p, new hudson.model.BuildAuthorizationToken("secret"));
        /* Too slow:
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("alice", "alice");
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        form.getInputByName("pseudoRemoteTrigger").setChecked(true);
        form.getInputByName("authToken").setValue("secret");
        j.submit(form);
        */
        hudson.model.BuildAuthorizationToken token = p.getAuthToken();
        assertNotNull(token);
        assertEquals("secret", token.getToken());
    }

    private void assertCreated(Page page) throws Exception {
        assertEquals(HttpURLConnection.HTTP_CREATED, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getResponseHeaderValue("Location").contains("/queue/item/"));
        j.waitUntilNoActivity();
    }

    @Test
    void responseStatus() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("semaphore 'hang'", true));
        p.addProperty(new DisableConcurrentBuildsJobProperty());
        setAuthToken(p);
        // Queue first build.
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hang/1", b1);
        // Queue second build, first is executing.
        QueueTaskFuture<WorkflowRun> b2Future = p.scheduleBuild2(0);
        String buildUrl = "buildByToken/build?job=p&token=secret";
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.setRedirectEnabled(false); // Client does not have permission to see the build.
        wc.setThrowExceptionOnFailingStatusCode(false); // Accept status code above 299.
        WebResponse resp = wc.goTo(buildUrl, "").getWebResponse();
        assertEquals(HttpURLConnection.HTTP_SEE_OTHER, resp.getStatusCode());
        assertTrue(resp.getResponseHeaderValue("Location").contains("/queue/item/"));
        SemaphoreStep.success("hang/1", null);
        WorkflowRun b2 = b2Future.waitForStart();
        SemaphoreStep.waitForStart("hang/2", b2);
        SemaphoreStep.success("hang/2", null);
        j.waitForCompletion(b1);
        j.waitForCompletion(b2);
        assertEquals(2, p.getBuilds().size());
    }

    @Issue("JENKINS-26693")
    @Test
    void buildWorkflow() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        testBuild(p);
    }

    @Issue("JENKINS-28543")
    @Test
    void buildWithParameters() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("p");
        setAuthToken(p);
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("foo", null), new StringParameterDefinition("baz", null)));
        assertCreated(j.createWebClient().goTo("buildByToken/buildWithParameters?job=" + p.getFullName() + "&token=secret&foo=bar&baz=quux", null));
        FreeStyleBuild b = p.getLastBuild();
        assertNotNull(b);
        assertEquals(1, b.getNumber());
        ParametersAction a = b.getAction(ParametersAction.class);
        assertNotNull(a);
        assertEquals("bar", a.getParameter("foo").getValue());
        assertEquals("quux", a.getParameter("baz").getValue());
    }

    // TODO test polling

    @Test
    void folders() throws Exception {
        testBuild(j.createProject(Folder.class, "dir").createProject(FreeStyleProject.class, "prj"));
    }

    @Issue("JENKINS-25637")
    @Test
    void testCrumbBypass() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("p");
        setAuthToken(p);

        List<NameValuePair> parameters = new ArrayList<>();
        parameters.add(new NameValuePair("job", p.getFullName()));
        parameters.add(new NameValuePair("token", "secret"));
        parameters.add(new NameValuePair("delay", "0sec"));

        WebRequest buildTokenRequest = new WebRequest(new URL(j.jenkins.getRootUrl() + "buildByToken/build"));
        buildTokenRequest.setHttpMethod(HttpMethod.POST);
        buildTokenRequest.setRequestParameters(parameters);

        JenkinsRule.WebClient wc = j.createWebClient();
        assertCreated(wc.getPage(buildTokenRequest));

        assertEquals(1, p.getBuilds().size());
    }
}
