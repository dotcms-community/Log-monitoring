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
import io.vavr.control.Try;

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
        captureContentEvent(event.getContentlet(), event.getUser() != null ? event.getUser().getUserId() : "system", eventType);
    }

    @Subscriber
    public void onPublish(final ContentletPublishEvent<Contentlet> event) {
        // onModified covers both publish and unpublish states; this subscriber
        // is required by the interface but delegates to onModified to avoid
        // double-buffering the same event.
    }

    @Override
    @Subscriber
    public void onArchive(final ContentletArchiveEvent<Contentlet> event) {
        captureContentEvent(event.getContentlet(), "system",
                event.isArchive() ? "CONTENT_ARCHIVE" : "CONTENT_UNARCHIVE");
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

        final String siteName = Try.of(() -> {
            final Host host = APILocator.getHostAPI()
                    .find(contentlet.getHost(), APILocator.systemUser(), false);
            return host != null ? host.getHostname() : "unknown";
        }).getOrElse("unknown");

        final String title = Try.of(contentlet::getTitle).getOrElse("unknown");
        final String contentType = Try.of(() -> contentlet.getContentType().variable()).getOrElse("unknown");

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
}
