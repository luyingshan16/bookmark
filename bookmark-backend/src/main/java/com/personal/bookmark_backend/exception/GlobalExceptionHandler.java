package com.personal.bookmark_backend.exception;

import com.personal.bookmark_backend.time.ApplicationTime;

import com.personal.bookmark_backend.dto.response.ErrorResponse;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch() {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "請求參數格式不正確");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter() {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "缺少必填參數");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart() {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "缺少必填的上傳內容");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge() {
        return buildErrorResponse(HttpStatus.CONTENT_TOO_LARGE, "檔案或上傳請求超過大小限制");
    }

    @ExceptionHandler(GoogleLinkRequiredException.class)
    public ResponseEntity<ErrorResponse> handleGoogleLinkRequired(GoogleLinkRequiredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Bookmark-Auth-Action", "link-google")
                .body(new ErrorResponse(
                        HttpStatus.CONFLICT.value(),
                        exception.getMessage(),
                        ApplicationTime.toTaipei(ApplicationTime.now())
                ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        return buildErrorResponse(exception.getStatusCode(), exception.getReason());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException exception) {
        return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    }

    // =====================================================
    // 400 Bad Request
    // Service 參數或商業邏輯錯誤
    // =====================================================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException exception
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
    }


    // =====================================================
    // 400 Bad Request
    // Request DTO 的 @Valid 驗證錯誤
    // =====================================================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception
    ) {

        String message = "請求資料格式不正確";

        FieldError fieldError = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .orElse(null);

        if (fieldError != null) {
            message = fieldError.getDefaultMessage();
        }

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                message
        );
    }


    // =====================================================
    // 400 Bad Request
    // Request Body JSON 格式錯誤
    // =====================================================

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "請求資料格式錯誤，請檢查 JSON 內容"
        );
    }


    // =====================================================
    // 400 Bad Request
    // Path Variable 或 Request Parameter 驗證錯誤
    // =====================================================

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );
    }


    // =====================================================
    // 401 Unauthorized
    // 登入驗證失敗
    // =====================================================

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(
            UnauthorizedException exception
    ) {
        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                exception.getMessage()
        );
    }


    // =====================================================
    // 403 Forbidden
    // 已識別使用者，但不允許執行操作
    // =====================================================

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenException(
            ForbiddenException exception
    ) {
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                exception.getMessage()
        );
    }


    // =====================================================
    // 404 Not Found
    // 找不到指定資源
    // =====================================================

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException exception
    ) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
    }


    // =====================================================
    // 409 Conflict
    // 資料庫唯一值或完整性限制衝突
    // =====================================================

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflictException(
            ConflictException exception
    ) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
    }


    // =====================================================
    // 500 Internal Server Error
    // 未預期的伺服器錯誤
    // =====================================================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception exception
    ) {
        if (isFolderNameConflict(exception)) {
            return buildErrorResponse(HttpStatus.CONFLICT, "收藏夾名稱已經存在");
        }

        log.atError().addKeyValue("exceptionType", exception.getClass().getName())
                .setCause(exception).log("Unhandled API exception");

        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "伺服器發生未預期的錯誤，請稍後再試"
        );
    }

    private boolean isFolderNameConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getKind() == org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE
                    && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().toLowerCase(Locale.ROOT);
                name = name.substring(name.lastIndexOf('.') + 1);
                return name.equals("uq_folder_name") || name.equals("uk_folders_user_name")
                        || name.matches("uk_folders_user_name_index_[a-z0-9]+");
            }
        }
        return false;
    }


    // =====================================================
    // 統一建立錯誤回應
    // =====================================================

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatusCode status,
            String message
    ) {

        ErrorResponse response = new ErrorResponse(
                status.value(),
                message,
                ApplicationTime.toTaipei(ApplicationTime.now())
        );

        return ResponseEntity
                .status(status)
                .body(response);
    }
}


