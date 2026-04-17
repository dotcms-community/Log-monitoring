package com.dotcms.logmonitoring.interceptor;

import com.dotcms.filters.interceptor.Result;
import com.dotcms.filters.interceptor.WebInterceptor;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.web.WebAPILocator;
import com.dotmarketing.util.Logger;
import org.apache.logging.log4j.ThreadContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Injects site context into Log4j2's ThreadContext (MDC) at the start of every
 * HTTP request so that all log statements made during that request automatically
 * carry the site name and requesting user.
 *
 * The ThreadContext values are read by EventBufferAppender when a log event is
 * captured. They are cleared by SiteContextCleanupInterceptor at the end of the
 * request chain.
 */
public class SiteContextInterceptor implements WebInterceptor {

    private static final long serialVersionUID = 1L;

    public static final String MDC_SITE = "site";
    public static final String MDC_USER = "dotUser";

    @Override
    public String getName() {
        return "SiteContextInterceptor";
    }

    @Override
    public String[] getFilters() {
        return new String[]{"/*"};
    }

    @Override
    public Result intercept(final HttpServletRequest request,
                            final HttpServletResponse response) {
        try {
            final Host host = WebAPILocator.getHostWebAPI()
                    .getCurrentHostNoThrow(request);

            final String siteName = (host != null && !host.isDefault() && host.getHostname() != null)
                    ? host.getHostname()
                    : "default";

            ThreadContext.put(MDC_SITE, siteName);
        } catch (final Exception e) {
            Logger.debug(SiteContextInterceptor.class,
                    "SiteContextInterceptor: could not resolve site — " + e.getMessage());
            ThreadContext.put(MDC_SITE, "unknown");
        }

        try {
            String sessionUser = "anonymous";
            final HttpSession session = request.getSession(false);
            if (session != null) {
                final Object userId = session.getAttribute("USER_ID");
                if (userId != null) {
                    sessionUser = userId.toString();
                }
            }
            ThreadContext.put(MDC_USER, sessionUser);
        } catch (final Exception e) {
            ThreadContext.put(MDC_USER, "unknown");
        }

        return Result.NEXT;
    }
}
