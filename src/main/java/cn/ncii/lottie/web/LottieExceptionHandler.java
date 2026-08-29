package cn.ncii.lottie.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts import validation failures into stable, user-readable API responses. */
@RestControllerAdvice(assignableTypes = {LottieController.class})
public class LottieExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleValidation(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("message", message(exception)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        return ResponseEntity.internalServerError()
            .body(Map.of("message", unexpectedMessage(exception)));
    }

    private String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? "Invalid animation upload" : exception.getMessage();
    }

    /**
     * Keep the API response useful for policy/storage failures without exposing
     * a server stack trace.  Reactive wrappers often put the actionable error
     * several causes deep, so prefer the deepest non-empty message.
     */
    private String unexpectedMessage(Throwable exception) {
        String deepest = null;
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth++ < 12) {
            String candidate = current.getMessage();
            if (candidate != null && !candidate.isBlank()) {
                deepest = candidate;
            }
            current = current.getCause();
        }
        if (deepest == null || deepest.isBlank()) {
            return "Unable to process the animation upload";
        }
        String sanitized = deepest.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (sanitized.length() > 300) {
            sanitized = sanitized.substring(0, 297) + "...";
        }
        return "Unable to process the animation upload: " + sanitized;
    }
}
