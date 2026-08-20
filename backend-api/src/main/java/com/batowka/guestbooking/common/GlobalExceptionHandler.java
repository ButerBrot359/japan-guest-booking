package com.batowka.guestbooking.common;

import com.batowka.guestbooking.accessrequest.AccessRequestNotFoundException;
import com.batowka.guestbooking.accessrequest.AlreadyResolvedException;
import com.batowka.guestbooking.auth.InvalidCredentialsException;
import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.RateLimitExceededException;
import com.batowka.guestbooking.auth.UnknownPhoneException;
import com.batowka.guestbooking.booking.BookingExpiredException;
import com.batowka.guestbooking.booking.BookingNotFoundException;
import com.batowka.guestbooking.booking.DatesTakenException;
import com.batowka.guestbooking.booking.InvalidBookingDatesException;
import com.batowka.guestbooking.booking.NotYourBookingException;
import com.batowka.guestbooking.booking.OverlapsOwnBookingException;
import com.batowka.guestbooking.booking.RangeTooLongException;
import com.batowka.guestbooking.booking.TelegramNotLinkedException;
import com.batowka.guestbooking.calendar.BlockedPeriodNotFoundException;
import com.batowka.guestbooking.calendar.InvalidCalendarRangeException;
import com.batowka.guestbooking.calendar.OverlapsBookingException;
import com.batowka.guestbooking.otp.CodeExpiredException;
import com.batowka.guestbooking.otp.InvalidCodeException;
import com.batowka.guestbooking.otp.NoActiveCodeException;
import com.batowka.guestbooking.otp.ResendTooSoonException;
import com.batowka.guestbooking.user.ActiveBookingExistsException;
import com.batowka.guestbooking.user.AlreadyMemberException;
import com.batowka.guestbooking.user.CannotDeleteAdminException;
import com.batowka.guestbooking.user.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
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

    @ExceptionHandler(com.batowka.guestbooking.user.UserGoneException.class)
    public org.springframework.http.ResponseEntity<ApiError> userGone(
            com.batowka.guestbooking.user.UserGoneException ex) {
        return org.springframework.http.ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .header(org.springframework.http.HttpHeaders.SET_COOKIE,
                        com.batowka.guestbooking.auth.AuthController
                                .authCookie("", java.time.Duration.ZERO).toString())
                .body(new ApiError("UNAUTHORIZED", ex.getMessage()));
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

    @ExceptionHandler(InvalidCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidCode(InvalidCodeException ex) {
        return new ApiError("INVALID_CODE", ex.getMessage());
    }

    @ExceptionHandler(CodeExpiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError codeExpired(CodeExpiredException ex) {
        return new ApiError("CODE_EXPIRED", ex.getMessage());
    }

    @ExceptionHandler(NoActiveCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError noActiveCode(NoActiveCodeException ex) {
        return new ApiError("NO_ACTIVE_CODE", ex.getMessage());
    }

    @ExceptionHandler(ResendTooSoonException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiError resendTooSoon(ResendTooSoonException ex) {
        return new ApiError("RESEND_TOO_SOON", ex.getMessage());
    }

    @ExceptionHandler(DatesTakenException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError datesTaken(DatesTakenException ex) {
        return new ApiError("DATES_TAKEN", ex.getMessage());
    }

    @ExceptionHandler(OverlapsOwnBookingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError overlapsOwnBooking(OverlapsOwnBookingException ex) {
        return new ApiError("OVERLAPS_OWN_BOOKING", ex.getMessage());
    }

    @ExceptionHandler(TelegramNotLinkedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError telegramNotLinked(TelegramNotLinkedException ex) {
        return new ApiError("TELEGRAM_NOT_LINKED", ex.getMessage());
    }

    @ExceptionHandler(NotYourBookingException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError notYourBooking(NotYourBookingException ex) {
        return new ApiError("FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(BookingExpiredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError bookingExpired(BookingExpiredException ex) {
        return new ApiError("BOOKING_EXPIRED", ex.getMessage());
    }

    @ExceptionHandler(InvalidBookingDatesException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidBookingDates(InvalidBookingDatesException ex) {
        return new ApiError("VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(RangeTooLongException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError rangeTooLong(RangeTooLongException ex) {
        return new ApiError("RANGE_TOO_LONG", ex.getMessage());
    }

    @ExceptionHandler(BookingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError bookingNotFound(BookingNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError accessDenied(org.springframework.security.access.AccessDeniedException ex) {
        // бросается @PreAuthorize внутри MVC — без этого хендлера catch-all дал бы 500
        return new ApiError("FORBIDDEN", "Недостаточно прав");
    }

    @ExceptionHandler(BlockedPeriodNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError blockedPeriodNotFound(BlockedPeriodNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(OverlapsBookingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public OverlapsBookingError overlapsBooking(OverlapsBookingException ex) {
        return new OverlapsBookingError("OVERLAPS_BOOKING", ex.getMessage(), ex.getConflicts());
    }

    public record OverlapsBookingError(String code, String message,
                                       java.util.List<OverlapsBookingException.Conflict> conflicts) {
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError userNotFound(UserNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AlreadyMemberException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError alreadyMember(AlreadyMemberException ex) {
        return new ApiError("ALREADY_MEMBER", ex.getMessage());
    }

    @ExceptionHandler(ActiveBookingExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError activeBookingExists(ActiveBookingExistsException ex) {
        return new ApiError("ACTIVE_BOOKING_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(CannotDeleteAdminException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError cannotDeleteAdmin(CannotDeleteAdminException ex) {
        return new ApiError("CANNOT_DELETE_ADMIN", ex.getMessage());
    }

    @ExceptionHandler(AccessRequestNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError accessRequestNotFound(AccessRequestNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AlreadyResolvedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError alreadyResolved(AlreadyResolvedException ex) {
        return new ApiError("ALREADY_RESOLVED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError unexpected(Exception ex) {
        log.error("Необработанное исключение", ex);
        return new ApiError("INTERNAL_ERROR", "Внутренняя ошибка сервера");
    }
}
