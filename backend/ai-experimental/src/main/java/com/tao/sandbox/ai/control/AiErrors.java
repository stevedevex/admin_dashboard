package com.tao.sandbox.ai.control;

import com.tao.sandbox.control.ControlPanelProblem;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns this module's failures into the same problem+json the rest of the control panel returns.
 *
 * <p>Its own advice rather than an extension of the mock-data one, for the reason that advice
 * gives for being package-scoped in the first place: it must not reach the data plane, which
 * answers in the imitated contract's own vocabulary. Each module scoping its own keeps that true
 * without the library having to know which feature modules exist above it.
 */
@RestControllerAdvice(basePackages = "com.tao.sandbox.ai")
class AiErrors {

    @ExceptionHandler(ControlPanelProblem.class)
    ProblemDetail handle(ControlPanelProblem problem) {
        return problem.asProblemDetail();
    }
}
