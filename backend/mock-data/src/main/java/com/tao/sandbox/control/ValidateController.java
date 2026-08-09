package com.tao.sandbox.control;

import com.tao.sandbox.control.view.ValidateRequest;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.validate.MockStates;
import com.tao.sandbox.validate.MockValidator;
import com.tao.sandbox.validate.Validation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Checking a payload against the contract its operation declares. */
@RestController
class ValidateController {

    private final MockValidator validator;
    private final MockStates states;

    ValidateController(MockValidator validator, MockStates states) {
        this.validator = validator;
        this.states = states;
    }

    /**
     * @param mockId optional. When the body being checked is what a given mock holds, saying so
     *     lets the verdict be remembered against that mock, which is the only thing that ever
     *     populates the state the tree shows. Omitted while editing unsaved text, where there is no
     *     mock the answer would still be true of a moment later.
     */
    @PostMapping(
            value = "/__tao/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Validation validate(
            @RequestBody ValidateRequest request, @RequestParam(required = false) String mockId) {

        if (request == null || request.serviceId() == null || request.operationId() == null) {
            throw ControlPanelProblem.badRequest(
                    "missing-operation",
                    "Missing operation",
                    "Validation needs a serviceId and operationId — the contract is what it checks against");
        }

        Validation validation;
        try {
            validation = validator.validate(request.serviceId(), request.operationId(), request.body());
        } catch (IllegalArgumentException e) {
            throw ControlPanelProblem.notFound("operation-not-found", "No such operation", e.getMessage());
        }

        if (mockId != null && !mockId.isBlank()) {
            states.record(MockId.parse(mockId), validation);
        }

        return validation;
    }
}
