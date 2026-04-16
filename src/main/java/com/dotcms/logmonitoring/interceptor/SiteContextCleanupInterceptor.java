package com.dotcms.logmonitoring.interceptor;

import com.dotcms.filters.interceptor.Result;
import com.dotcms.filters.interceptor.WebInterceptor;
import org.apache.logging.log4j.ThreadContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Clears the Log4j2 ThreadContext (MDC) at the end of every request.
 *
 * This must be registered AFTER SiteContextInterceptor in the interceptor chain
 * (i.e. addLast) so it runs last and prevents site/user context from leaking
 * into the next request handled by the same thread.
 */
public class SiteContextCleanupInterceptor implements WebInterceptor {

    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return "SiteContextCleanupInterceptor";
    }

    @Override
    public String[] getFilters() {
        return new String[]{"/*"};
    }

    @Override
    public Result intercept(final HttpServletRequest request,
                            final HttpServletResponse response) {
        // This interceptor runs at the end of the chain (registered last).
        // By the time it runs, all log statements for this request have fired,
        // so it is safe to clear the context now.
        ThreadContext.clearAll();
        return Result.NEXT;
    }
}
