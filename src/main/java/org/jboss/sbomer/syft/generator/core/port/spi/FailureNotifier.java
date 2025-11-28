package org.jboss.sbomer.syft.generator.core.port.spi;

import org.jboss.sbomer.events.common.FailureSpec;

public interface FailureNotifier {
    /**
     * Notifies an external system about a failure.
     * @param failure The standardized details of the failure.
     @param correlationId The correlationId associated with failing event
      * @param sourceEvent The original event object that caused the failure.
     */
    void notify(FailureSpec failure, String correlationId, Object sourceEvent);
}
