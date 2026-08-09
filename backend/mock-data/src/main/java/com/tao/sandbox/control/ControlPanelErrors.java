package com.tao.sandbox.control;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns control-panel failures into problem+json.
 *
 * <p>Scoped to this package rather than applied globally. The data plane imitates someone else's
 * contract and answers in that contract's own vocabulary — a SOAP fault, or a miss diagnostic
 * carrying the resolution trace. An advice that caught those too would quietly replace them, and
 * the application under test would see a shape its real upstream never returns.
 */
@RestControllerAdvice(basePackages = "com.tao.sandbox.control")
class ControlPanelErrors {

    @ExceptionHandler(ControlPanelProblem.class)
    ProblemDetail handle(ControlPanelProblem problem) {
        return problem.asProblemDetail();
    }

    /**
     * Domain records validate themselves in their constructors — {@link
     * com.tao.sandbox.store.MockId} rejects a path where a segment belongs, {@link
     * com.tao.sandbox.runtime.match.KeySpec} rejects a malformed declaration. Reaching one of those
     * from a request means the request was wrong, so the message they wrote is the answer.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handle(IllegalArgumentException e) {
        return ControlPanelProblem.badRequest("malformed-request", "Malformed request", e.getMessage())
                .asProblemDetail();
    }
}
