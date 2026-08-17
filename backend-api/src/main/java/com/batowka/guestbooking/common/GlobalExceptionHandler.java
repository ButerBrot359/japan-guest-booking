package com.batowka.guestbooking.common;

import com.batowka.guestbooking.calendar.InvalidCalendarRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
}
