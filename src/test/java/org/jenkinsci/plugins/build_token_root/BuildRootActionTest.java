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

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.PresetData;
import org.xml.sax.SAXException;

@SuppressWarnings({"deprecation", "unchecked"}) // RunList.size, BuildAuthorizationToken, AbstractItem.getParent snafu
public class BuildRootActionTest {

    private static final Logger logger = Logger.getLogger(BuildRootAction.class.getName());
    @BeforeClass public static void logging() {
        logger.setLevel(Level.ALL);
        Handler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @Rule public JenkinsRule j = new JenkinsRule();

    @PresetData(PresetData.DataSet.NO_ANONYMOUS_READACCESS)
    @Test public void build() throws Exception {
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

    private <JobT extends Job<JobT, RunT> & ParameterizedJobMixIn.ParameterizedJob, RunT extends Run<JobT, RunT>> void setAuthToken(JobT p) throws IOException, ElementNotFoundException, Exception, SAXException {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("alice", "alice");
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        form.getInputByName("pseudoRemoteTrigger").setChecked(true);
        form.getInputByName("authToken").setValueAttribute("secret");
        j.submit(form);
        hudson.model.BuildAuthorizationToken token = p.getAuthToken();
        assertNotNull(token);
        assertEquals("secret", token.getToken());
    }

    private void assertCreated(Page page) throws Exception {
        assertEquals(HttpStatus.SC_CREATED, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getResponseHeaderValue("Location").contains("/queue/item/"));
        j.waitUntilNoActivity();
    }

    @Issue("JENKINS-26693")
    @PresetData(PresetData.DataSet.NO_ANONYMOUS_READACCESS)
    @Test public void buildWorkflow() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        testBuild(p);
    }

    @Issue("JENKINS-28543")
    @PresetData(PresetData.DataSet.NO_ANONYMOUS_READACCESS)
    @Test public void buildWithParameters() throws Exception {
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
    // TODO test projects in folders

}
