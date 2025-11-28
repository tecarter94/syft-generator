package org.jboss.sbomer.syft.generator.core.utility;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.jboss.sbomer.events.common.FailureSpec;

public class FailureUtility {

    private FailureUtility() {}

    /**
     * Utility method to build a FailureSpec object from a Java Exception.
     *
     * @param e The exception that was caught.
     * @return A populated FailureSpec object.
     */
    public static FailureSpec buildFailureSpecFromException(Exception e) {

        FailureSpec failure = new FailureSpec();
        failure.setReason(e.getMessage());
        failure.setErrorCode(e.getClass().getSimpleName());

        // Capture the full stack trace
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();

        // Add the stack trace to the details map.
        Map<String, String> details = new HashMap<>();
        details.put("stackTrace", stackTrace);
        failure.setDetails(details);

        return failure;
    }

    // TODO buildFailureSpecFromGenerationFailure


    // TODO buildFailureSpecFromEnhancementFailure


}
