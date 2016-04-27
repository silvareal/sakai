package org.sakaiproject.lti.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.lti.api.LTIService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.util.ResourceLoader;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * This Reports on new instances of an LTI tool in a site.
 */
public class LTIReportingJob implements Job {

    private final Logger log = LoggerFactory.getLogger(LTIReportingJob.class);

    protected final ResourceLoader rb = new ResourceLoader("email");

    private LTIService ltiService;
    private SiteService siteService;
    private EmailService emailService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        // The ID of the LTI tool to report on.
        long toolId = context.getMergedJobDataMap().getLongFromString("toolId");
        // The number of milliseconds in the past to look for tools added in.
        int period = context.getMergedJobDataMap().getIntFromString("period");
        // The emails address to send the reports to
        String to = context.getMergedJobDataMap().getString("to");
        // The from address
        String from = context.getMergedJobDataMap().getString("from");


        Map<String, Object> tool = ltiService.getToolDao(toolId, null);
        if (tool == null) {
            log.warn("Failed to find LTI tool for "+ toolId);
            return;
        }

        String toolTitle = (String) tool.get("title");

        // SELECT * FROM lti_content where tool_id = ? created_at > date_sub(now(),  interval ???? second) ;
        String search = String.format("tool_id = %d AND created_at > date_sub(now(), interval %d second)",
            toolId, period / 1000);
        List<Map<String, Object>> contents = ltiService.getContentsDao(search, null, 0, 0, null);
        DateFormat df = DateFormat.getDateTimeInstance( DateFormat.SHORT, DateFormat.SHORT, rb.getLocale());

        boolean sendEmail = false;
        StringBuilder email = new StringBuilder();
        email.append(rb.getFormattedMessage("new.tools.body.header", new String[]{toolTitle, formatDuration(period)}));
        for(Map<String, Object> content : contents) {
            String siteId = (String)content.get("SITE_ID");
            try {
                Site site = siteService.getSite(siteId);
                // TODO Check the LTI tool is still in the site.
                String title = site.getTitle();
                User user = site.getModifiedBy();
                Date date = site.getModifiedDate();
                String url = site.getUrl();
                email.append(rb.getFormattedMessage("new.tools.body.entry",
                    new Object[]{title, url, user.getDisplayName(), df.format(date)}));
                sendEmail = true;

            } catch (IdUnusedException e) {
                // Most likely the newly added LTI's site has been deleted
                log.debug("Failed to find site with ID of: "+ siteId);
            }
        }
        email.append(rb.getString("new.tools.body.footer"));

        if (!sendEmail) {
            // We check here because it may be the case that the site we were going to notify about
            // doesn't exist any longer.
            log.debug("No contents found, no email will be sent.");
            return;
        }
        String subject = rb.getFormattedMessage("new.tools.subject", new String[]{toolTitle, formatDuration(period)});
        String body = email.toString();

        emailService.send(from,to,subject, body, null, null, null);
        log.debug("Sent mail from "+ from+ " to "+ to+ " with details of "+ contents.size()+ " changes");
    }

    /**
     * Formats a duration sensibly.
     * @param remaining Time remaining in milliseconds.
     * @return a String roughly representing the duration.
     */
    protected String formatDuration(long remaining) {
        if (remaining < 1000) {
            return "< 1 second";
        } else if (remaining < 60000) {
            return remaining / 1000 + " second(s)";
        } else if (remaining < 3600000) {
            return remaining / 60000 + " minute(s)";
        } else if (remaining < 86400000) {
            return remaining / 3600000 + " hour(s)";
        } else {
            return remaining / 86400000 + " day(s)";
        }
    }

    public void setLtiService(LTIService ltiService) {
        this.ltiService = ltiService;
    }

    public void setSiteService(SiteService siteService) {
        this.siteService = siteService;
    }

    public void setEmailService(EmailService emailService) {
        this.emailService = emailService;
    }
}
