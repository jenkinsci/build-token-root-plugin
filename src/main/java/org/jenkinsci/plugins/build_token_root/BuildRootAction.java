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

import hudson.Extension;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.triggers.SCMTrigger;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import jenkins.util.TimeDuration;
import org.acegisecurity.AccessDeniedException;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

@Extension
public class BuildRootAction implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(BuildRootAction.class.getName());

    @Override public String getUrlName() {
        return "buildByToken";
    }

    @Override public String getIconFileName() {
        return null;
    }

    @Override public String getDisplayName() {
        return null;
    }

    public void doBuild(StaplerRequest req, StaplerResponse rsp, @QueryParameter String job, @QueryParameter TimeDuration delay) throws IOException, ServletException {
        LOGGER.log(Level.FINE, "build on {0}", job);
        ParameterizedJobMixIn.ParameterizedJob p = project(job, req, rsp);
        if (delay == null) {
            delay = new TimeDuration(p.getQuietPeriod());
        }
        ParametersDefinitionProperty pp = ((Job<?,?>) p).getProperty(ParametersDefinitionProperty.class);
        if (pp != null) {
            LOGGER.fine("wrong kind");
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "use buildWithParameters for this build");
        }
        Jenkins.getInstance().getQueue().schedule(p, delay.getTime(), getBuildCause(req));
        ok(rsp);
    }

    public void doBuildWithParameters(StaplerRequest req, StaplerResponse rsp, @QueryParameter String job, @QueryParameter TimeDuration delay) throws IOException, ServletException {
        LOGGER.log(Level.FINE, "buildWithParameters on {0}", job);
        ParameterizedJobMixIn.ParameterizedJob p = project(job, req, rsp);
        if (delay == null) {
            delay = new TimeDuration(p.getQuietPeriod());
        }
        ParametersDefinitionProperty pp = ((Job<?,?>) p).getProperty(ParametersDefinitionProperty.class);
        if (pp == null) {
            LOGGER.fine("wrong kind");
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "use build for this build");
        }
        List<ParameterValue> values = new ArrayList<ParameterValue>();
        for (ParameterDefinition d : pp.getParameterDefinitions()) {
        	ParameterValue value = d.createValue(req);
        	if (value != null) {
        		values.add(value);
        	}
        }
        Jenkins.getInstance().getQueue().schedule(p, delay.getTime(), new ParametersAction(values), getBuildCause(req));
        ok(rsp);
    }

    public void doPolling(StaplerRequest req, StaplerResponse rsp, @QueryParameter String job) throws IOException, ServletException {
        LOGGER.log(Level.FINE, "polling on {0}", job);
        ParameterizedJobMixIn.ParameterizedJob p = project(job, req, rsp);
        // AbstractProject.schedulePolling only adds one thing here: check for isDisabled. But in that case, !isBuildable, so we would not have gotten here anyway.
        SCMTriggerItem scmp = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(p);
        if (scmp == null) {
            LOGGER.log(Level.FINE, "{0} is not a SCMTriggerItem", p);
            throw HttpResponses.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, new IOException(job + " is not a SCMTriggerItem"));
        }
        SCMTrigger trigger = scmp.getSCMTrigger();
        if (trigger == null) {
            LOGGER.log(Level.FINE, "{0} is not configured to poll", p);
            throw HttpResponses.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, new IOException(job + " is not configured to poll"));
        }
        trigger.run();
        ok(rsp);
    }

    @SuppressWarnings("deprecation")
    private ParameterizedJobMixIn.ParameterizedJob project(String job, StaplerRequest req, StaplerResponse rsp) throws IOException, HttpResponses.HttpResponseException {
        Job<?,?> j;
        SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
        try {
            j = Jenkins.getInstance().getItemByFullName(job, Job.class);
        } finally {
            SecurityContextHolder.setContext(orig);
        }
        if (j == null) {
            LOGGER.log(Level.FINE, "no such job {0}", j);
            throw HttpResponses.notFound();
        }
        if (!(j instanceof ParameterizedJobMixIn.ParameterizedJob)) {
            LOGGER.log(Level.FINE, "{0} is not a ParameterizedJob", j);
            throw HttpResponses.notFound();
        }
        ParameterizedJobMixIn.ParameterizedJob p = (ParameterizedJobMixIn.ParameterizedJob) j;
        hudson.model.BuildAuthorizationToken authToken = p.getAuthToken();
        if (authToken == null || authToken.getToken() == null) {
            // For jobs without tokens, prefer not to leak information about their existence.
            // (We assume anonymous lacks DISCOVER.)
            LOGGER.log(Level.FINE, "no authToken on {0}", job);
            throw HttpResponses.notFound();
        }
        try {
            hudson.model.BuildAuthorizationToken.checkPermission((Job) p, authToken, req, rsp);
        } catch (AccessDeniedException x) {
            LOGGER.log(Level.FINE, "on {0} was denied: {1}", new Object[] {job, x.getMessage()});
            throw x;
        }
        if (!j.isBuildable()) {
            LOGGER.log(Level.FINE, "{0} is not buildable", job);
            throw HttpResponses.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, new IOException(job + " is not buildable"));
        }
        LOGGER.log(Level.FINE, "found {0}", p);
        return p;
    }

    private CauseAction getBuildCause(StaplerRequest req) {
        return new CauseAction(new Cause.RemoteCause(req.getRemoteAddr(), req.getParameter("cause")));
    }

    private void ok(StaplerResponse rsp) throws IOException {
        rsp.setContentType("text/html");
        PrintWriter w = rsp.getWriter();
        w.write("Scheduled.\n");
        w.close();
    }

}
