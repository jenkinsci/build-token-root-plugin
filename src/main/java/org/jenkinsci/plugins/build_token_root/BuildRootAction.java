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
import hudson.model.Queue;
import hudson.model.queue.ScheduleResult;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.csrf.CrumbExclusion;
import hudson.triggers.SCMTrigger;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_SEE_OTHER;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import jenkins.util.TimeDuration;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

@Extension
public class BuildRootAction implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(BuildRootAction.class.getName());
    public static final String URLNAME = "buildByToken";

    @Override public String getUrlName() {
        return URLNAME;
    }

    @Override public String getIconFileName() {
        return null;
    }

    @Override public String getDisplayName() {
        return null;
    }

    public void doBuild(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String job, @QueryParameter TimeDuration delay) throws IOException, ServletException {
        LOGGER.log(Level.FINE, "build on {0}", job);
        ParameterizedJobMixIn.ParameterizedJob<?, ?> p = project(job, req, rsp);
        if (delay == null) {
            delay = new TimeDuration(p.getQuietPeriod());
        }
        ParametersDefinitionProperty pp = ((Job<?,?>) p).getProperty(ParametersDefinitionProperty.class);
        if (pp != null) {
            LOGGER.fine("wrong kind");
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "Use /buildByToken/buildWithParameters for this job since it takes parameters");
        }
        ScheduleResult result = Jenkins.get().getQueue().schedule2(p, delay.getTimeInSeconds(), getBuildCause(req));
        handleScheduleResult(result, job, req, rsp);
    }

    public void doBuildWithParameters(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String job, @QueryParameter TimeDuration delay) throws IOException, ServletException {
        LOGGER.log(Level.FINE, "buildWithParameters on {0}", job);
        ParameterizedJobMixIn.ParameterizedJob<?, ?> p = project(job, req, rsp);
        if (delay == null) {
            delay = new TimeDuration(p.getQuietPeriod());
        }
        ParametersDefinitionProperty pp = ((Job<?,?>) p).getProperty(ParametersDefinitionProperty.class);
        if (pp == null) {
            LOGGER.fine("wrong kind");
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "Use /buildByToken/build for this job since it takes no parameters");
        }
        List<ParameterValue> values = new ArrayList<>();
        for (ParameterDefinition d : pp.getParameterDefinitions()) {
            ParameterValue value = d.createValue(req);
            if (value != null) {
                values.add(value);
            }
        }
        ScheduleResult result = Jenkins.get().getQueue().schedule2(p, delay.getTimeInSeconds(), new ParametersAction(values), getBuildCause(req));
        handleScheduleResult(result, job, req, rsp);
    }

    public void doPolling(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String job) throws IOException, ServletException {
        LOGGER.log(Level.FINE, "polling on {0}", job);
        ParameterizedJobMixIn.ParameterizedJob<?, ?> p = project(job, req, rsp);
        // AbstractProject.schedulePolling only adds one thing here: check for isDisabled. But in that case, !isBuildable, so we would not have gotten here anyway.
        SCMTriggerItem scmp = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(p);
        if (scmp == null) {
            LOGGER.log(Level.FINE, "{0} is not a SCMTriggerItem", p);
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, new IOException(job + " is not a SCMTriggerItem"));
        }
        SCMTrigger trigger = scmp.getSCMTrigger();
        if (trigger == null) {
            LOGGER.log(Level.FINE, "{0} is not configured to poll", p);
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, new IOException(job + " is not configured to poll"));
        }
        trigger.run();
        ok(rsp);
    }

    @SuppressWarnings("deprecation")
    private ParameterizedJobMixIn.ParameterizedJob<?, ?> project(String job, StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, HttpResponses.HttpResponseException {
        Job<?,?> j;
        try (ACLContext c = ACL.as(ACL.SYSTEM)) {
            j = Jenkins.get().getItemByFullName(job, Job.class);
        }
        if (j == null) {
            LOGGER.log(Level.FINE, "no such job {0}", job);
            throw HttpResponses.notFound();
        }
        if (!(j instanceof ParameterizedJobMixIn.ParameterizedJob)) {
            LOGGER.log(Level.FINE, "{0} is not a ParameterizedJob", j);
            throw HttpResponses.notFound();
        }
        ParameterizedJobMixIn.ParameterizedJob<?, ?> p = (ParameterizedJobMixIn.ParameterizedJob) j;
        hudson.model.BuildAuthorizationToken authToken = p.getAuthToken();
        if (authToken == null || authToken.getToken() == null) {
            // For jobs without tokens, prefer not to leak information about their existence.
            // (We assume anonymous lacks DISCOVER.)
            LOGGER.log(Level.FINE, "no authToken on {0}", job);
            throw HttpResponses.notFound();
        }
        try {
            hudson.model.BuildAuthorizationToken.checkPermission((Job) p, authToken, req, rsp);
        } catch (RuntimeException x) { // e.g., AccessDeniedException
            LOGGER.log(Level.FINE, "on {0} was denied: {1}", new Object[] {job, x.getMessage()});
            throw x;
        }
        if (!j.isBuildable()) {
            LOGGER.log(Level.FINE, "{0} is not buildable", job);
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, new IOException(job + " is not buildable"));
        }
        LOGGER.log(Level.FINE, "found {0}", p);
        return p;
    }

    private CauseAction getBuildCause(StaplerRequest2 req) {
        return new CauseAction(new Cause.RemoteCause(req.getRemoteAddr(), req.getParameter("cause")));
    }

    private void ok(StaplerResponse2 rsp) throws IOException {
        rsp.setContentType("text/html");
        try (PrintWriter w = rsp.getWriter()) {
            w.write("Scheduled.\n");
        }
    }

    private void handleScheduleResult(ScheduleResult result, String job, StaplerRequest2 req, StaplerResponse2 rsp) throws HttpResponses.HttpResponseException, IOException {
        if (result.isAccepted()) {
            Queue.Item item = result.getItem();
            assert item != null;
            String itemUrl = req.getContextPath() + '/' + item.getUrl();
            if (result.isCreated()) {
                rsp.setStatus(SC_CREATED);
                rsp.addHeader("Location", itemUrl);
            } else {
                // Clients without the READ permission won’t be allowed to see
                // the build, they should not follow the redirect.
                rsp.sendRedirect(SC_SEE_OTHER, itemUrl);
            }
        } else {
            LOGGER.log(Level.FINE, "Jenkins refused to queue job “{0}”", job);
            throw HttpResponses.forbidden();
        }
    }

    @Extension
    public static class BuildRootActionCrumbExclusion extends CrumbExclusion {

        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws IOException, ServletException {
            String pathInfo = req.getPathInfo();
            if (pathInfo != null && pathInfo.startsWith(getExclusionPath())) {
                chain.doFilter(req, resp);
                return true;
            }
            return false;
        }

        public String getExclusionPath() {
            return "/" + URLNAME + "/";
        }
    }
}
