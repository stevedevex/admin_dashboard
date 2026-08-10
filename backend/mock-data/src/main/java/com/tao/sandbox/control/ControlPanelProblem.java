package com.tao.sandbox.control;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A control-panel failure, carrying everything RFC 9457 needs to describe it.
 *
 * <p>The same {@code application/problem+json} shape the data plane already returns on a
 * resolution miss, so the dashboard has one error renderer rather than two.
 *
 * <p>Public because it is the control plane's shared error vocabulary, not one package's:
 * every module that mounts endpoints under {@code /__tao} answers failures in this shape, so
 * the dashboard keeps one error renderer however many modules it is talking to.
 *
 * <p>{@code detail} is written for whoever is looking at the dashboard, not for a log: it names
 * what was asked for and, where the list is short enough to be useful, what exists instead. A
 * bare "not found" turns a typo into an investigation.
 */
public class ControlPanelProblem extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;

    private ControlPanelProblem(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public static ControlPanelProblem notFound(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.NOT_FOUND, type, title, detail);
    }

    /** The request was understood and is well-formed, but describes something unworkable. */
    public static ControlPanelProblem unprocessable(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.UNPROCESSABLE_ENTITY, type, title, detail);
    }

    public static ControlPanelProblem badRequest(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.BAD_REQUEST, type, title, detail);
    }

    public static ControlPanelProblem conflict(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.CONFLICT, type, title, detail);
    }

    /** The caller holds a version that is no longer current. */
    public static ControlPanelProblem preconditionFailed(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.PRECONDITION_FAILED, type, title, detail);
    }

    /** The caller sent no precondition at all, on a request that must not be made blind. */
    public static ControlPanelProblem preconditionRequired(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.PRECONDITION_REQUIRED, type, title, detail);
    }

    /**
     * The request was fine, but a call this endpoint had to make on the caller's behalf did not
     * come back. Only the playground can reach this: it is the one endpoint that answers by calling
     * the sandbox's own data plane, so it is the one that can fail for a reason the caller did
     * nothing to cause.
     */
    public static ControlPanelProblem badGateway(String type, String title, String detail) {
        return new ControlPanelProblem(HttpStatus.BAD_GATEWAY, type, title, detail);
    }

    public ProblemDetail asProblemDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
        problem.setType(URI.create("urn:tao:sandbox:" + type));
        problem.setTitle(title);
        return problem;
    }
}
