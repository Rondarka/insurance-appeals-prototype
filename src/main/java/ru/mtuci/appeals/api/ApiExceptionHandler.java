package ru.mtuci.appeals.api;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.integration.AntivirusUnavailableException;
import ru.mtuci.appeals.integration.ContractSourceUnavailableException;
import ru.mtuci.appeals.service.AppealNotFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message, Instant.now()));
    }

    /** Сбой внешней системы — не ошибка клиента: 503, а не 400. */
    @ExceptionHandler(ContractSourceUnavailableException.class)
    ResponseEntity<ErrorResponse> handleContractSourceUnavailable(ContractSourceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Учётная система договоров недоступна, попробуйте позже", Instant.now()));
    }

    /** Непроверенный файл не принимаем, но это сбой проверки, а не ошибка клиента. */
    @ExceptionHandler(AntivirusUnavailableException.class)
    ResponseEntity<ErrorResponse> handleAntivirusUnavailable(AntivirusUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Проверка файлов временно недоступна, попробуйте позже", Instant.now()));
    }

    /** И отсутствующее, и чужое обращение — 404: не раскрываем, что оно существует. */
    @ExceptionHandler(AppealNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(AppealNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(exception.getMessage(), Instant.now()));
    }

    /** Обращение видно, но действие не по роли или не своего подразделения. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(exception.getMessage(), Instant.now()));
    }

    /** Обращение изменили одновременно — сотрудник или планировщик эскалации. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleConcurrentChange(OptimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Обращение только что изменилось. Обновите карточку и повторите", Instant.now()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ErrorResponse> handleBusinessException(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(exception.getMessage(), Instant.now()));
    }
}
