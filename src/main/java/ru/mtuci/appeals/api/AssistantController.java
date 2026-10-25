package ru.mtuci.appeals.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.mtuci.appeals.api.ApiModels.AssistantAdviceResponse;
import ru.mtuci.appeals.api.ApiModels.AssistantFeedbackRequest;
import ru.mtuci.appeals.api.ApiModels.DraftResponse;
import ru.mtuci.appeals.assistant.AssistantService;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.security.CurrentUser;

import java.util.UUID;

/** Помощник сотрудника (решение 35): только сотрудникам отдела, доступ закрыт в SecurityConfig. */
@RestController
@RequestMapping("/api/appeals/{id}/assistant")
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @GetMapping
    AssistantAdviceResponse advice(CurrentUser user, @PathVariable UUID id) {
        return assistant.advice(user, id);
    }

    /** type: итоговый тип из формы ответа, «CATEGORY/TYPE»; пусто, если берётся тип обращения. */
    @GetMapping("/draft")
    DraftResponse draft(CurrentUser user, @PathVariable UUID id, @RequestParam AppealOutcome outcome,
                        @RequestParam(required = false) String type) {
        return assistant.draft(user, id, outcome, type);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void feedback(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody AssistantFeedbackRequest request) {
        assistant.feedback(user, id, request.kind(), request.decision());
    }
}
