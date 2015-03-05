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

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.model.FreeStyleProject;
import java.net.HttpURLConnection;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.PresetData;

public class BuildRootActionTest {

    private static final Logger logger = Logger.getLogger(BuildRootAction.class.getName());
    @BeforeClass public static void logging() {
        logger.setLevel(Level.ALL);
        Handler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @Rule public JenkinsRule j = new JenkinsRule();

    @SuppressWarnings("deprecation") // RunList.size, BuildAuthorizationToken
    @PresetData(PresetData.DataSet.NO_ANONYMOUS_READACCESS)
    @Test public void build() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("stuff");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("alice", "alice");
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        form.getInputByName("pseudoRemoteTrigger").setChecked(true);
        form.getInputByName("authToken").setValueAttribute("secret");
        j.submit(form);
        hudson.model.BuildAuthorizationToken token = p.getAuthToken();
        assertNotNull(token);
        assertEquals("secret", token.getToken());
        wc = j.createWebClient();
        wc.assertFails(p.getUrl() + "build?token=secret", HttpURLConnection.HTTP_FORBIDDEN);
        j.waitUntilNoActivity();
        assertEquals(0, p.getBuilds().size());
        wc.goTo("buildByToken/build?job=stuff&token=secret&delay=0sec");
        j.waitUntilNoActivity();
        assertEquals(1, p.getBuilds().size());
        wc.goTo("buildByToken/build?job=stuff&token=secret&delay=0sec");
        j.waitUntilNoActivity();
        assertEquals(2, p.getBuilds().size());
        wc.assertFails("buildByToken/build?job=stuff&token=socket&delay=0sec", HttpURLConnection.HTTP_FORBIDDEN);
        j.waitUntilNoActivity();
        assertEquals(2, p.getBuilds().size());
    }

    // TODO test buildWithParameters, polling

}
