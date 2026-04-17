package com.dotcms.logmonitoring.listener;

import com.dotcms.content.elasticsearch.business.event.ContentletArchiveEvent;
import com.dotcms.content.elasticsearch.business.event.ContentletDeletedEvent;
import com.dotcms.content.elasticsearch.business.event.ContentletPublishEvent;
import com.dotcms.logmonitoring.buffer.EventBuffer;
import com.dotcms.logmonitoring.model.LogEvent;
import com.dotcms.system.event.local.model.Subscriber;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.contentlet.model.ContentletListener;
import com.dotmarketing.util.Logger;

/**
 * Listens to dotCMS content lifecycle events (publish, unpublish, archive,
 * delete) and writes structured LogEvents into the EventBuffer.
 *
 * Site context is resolved directly from the Contentlet's host identifier,
 * so these events are accurately site-scoped regardless of which thread
 * triggers them (HTTP request, workflow, scheduled job, etc.).
 *
 * Registered via LocalSystemEventsAPI in the OSGi Activator.
 */
public class ContentEventListener implements ContentletListener<Contentlet> {

    @Override
    public String getId() {
        return getClass().getCanonicalName();
    }

    @Override
    @Subscriber
    public void onModified(final ContentletPublishEvent<Contentlet> event) {
        final String eventType = event.isPublish() ? "CONTENT_PUBLISH" : "CONTENT_UNPUBLISH";
        final String userId = resolveUserId(event);
        captureContentEvent(event.getContentlet(), userId, eventType);
    }

    public void onPublish(final ContentletPublishEvent<Contentlet> event) {
        // onModified covers both publish and unpublish; this method is required
        // by the interface but intentionally left empty to avoid double-capture.
    }

    @Override
    @Subscriber
    public void onArchive(final ContentletArchiveEvent<Contentlet> event) {
        final String eventType = event.isArchive() ? "CONTENT_ARCHIVE" : "CONTENT_UNARCHIVE";
        captureContentEvent(event.getContentlet(), "system", eventType);
    }

    @Override
    @Subscriber
    public void onDeleted(final ContentletDeletedEvent<Contentlet> event) {
        captureContentEvent(event.getContentlet(), "system", "CONTENT_DELETE");
    }

    private void captureContentEvent(final Contentlet contentlet,
                                     final String userId,
                                     final String eventType) {
        if (contentlet == null) {
            return;
        }

        final String siteName   = resolveSiteName(contentlet);
        final String title      = resolveTitle(contentlet);
        final String contentType = resolveContentType(contentlet);

        final String message = String.format("%s — title: '%s', contentType: %s, identifier: %s",
                eventType, title, contentType, contentlet.getIdentifier());

        final LogEvent logEvent = LogEvent.builder()
                .timestampNow()
                .site(siteName)
                .level("INFO")
                .eventType(eventType)
                .user(userId)
                .message(message)
                .logger(getClass().getName())
                .build();

        EventBuffer.getInstance().add(logEvent);
        Logger.debug(getClass(), "ContentEventListener captured: " + message);
    }

    private String resolveSiteName(final Contentlet contentlet) {
        try {
            final Host host = APILocator.getHostAPI()
                    .find(contentlet.getHost(), APILocator.systemUser(), false);
            return (host != null && host.getHostname() != null) ? host.getHostname() : "unknown";
        } catch (final Exception e) {
            Logger.debug(getClass(), "Could not resolve site for contentlet: " + e.getMessage());
            return "unknown";
        }
    }

    private String resolveTitle(final Contentlet contentlet) {
        try {
            return contentlet.getTitle();
        } catch (final Exception e) {
            return "unknown";
        }
    }

    private String resolveContentType(final Contentlet contentlet) {
        try {
            return contentlet.getContentType().variable();
        } catch (final Exception e) {
            return "unknown";
        }
    }

    private String resolveUserId(final ContentletPublishEvent<Contentlet> event) {
        try {
            return (event.getUser() != null) ? event.getUser().getUserId() : "system";
        } catch (final Exception e) {
            return "system";
        }
    }
}
