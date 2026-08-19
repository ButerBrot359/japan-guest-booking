package com.batowka.guestbooking.common;

import com.batowka.guestbooking.auth.InvalidCredentialsException;
import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.RateLimitExceededException;
import com.batowka.guestbooking.auth.UnknownPhoneException;
import com.batowka.guestbooking.calendar.InvalidCalendarRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCalendarRangeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidRange(InvalidCalendarRangeException ex) {
        return new ApiError("VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError typeMismatch(MethodArgumentTypeMismatchException ex) {
        return new ApiError("VALIDATION_ERROR",
                "Неверное значение параметра '" + ex.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError missingParam(MissingServletRequestParameterException ex) {
        return new ApiError("VALIDATION_ERROR",
                "Не хватает параметра '" + ex.getParameterName() + "'");
    }

    @ExceptionHandler(UnknownPhoneException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError unknownPhone(UnknownPhoneException ex) {
        return new ApiError("UNKNOWN_PHONE", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError invalidCredentials(InvalidCredentialsException ex) {
        return new ApiError("INVALID_CREDENTIALS", ex.getMessage());
    }

    @ExceptionHandler(InvalidPhoneException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidPhone(InvalidPhoneException ex) {
        return new ApiError("VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidBody(MethodArgumentNotValidException ex) {
        return new ApiError("VALIDATION_ERROR", "Некорректное тело запроса");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadableBody(HttpMessageNotReadableException ex) {
        return new ApiError("VALIDATION_ERROR", "Тело запроса не читается");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiError rateLimited(RateLimitExceededException ex) {
        return new ApiError("RATE_LIMITED", ex.getMessage());
    }
}
