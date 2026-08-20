package com.batowka.guestbooking.user;

import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.Phones;
import com.batowka.guestbooking.booking.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WhitelistService {

    private final UserAccountRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record UserRow(long id, String phone, String name, Role role,
                          boolean telegramLinked, Instant deletedAt) {
    }

    @Transactional(readOnly = true)
    public List<UserRow> list() {
        return users.findAll(Sort.by("id")).stream()
                .map(u -> new UserRow(u.getId(), u.getPhone(), u.getName(), u.getRole(),
                        u.getTelegramChatId() != null, u.getDeletedAt()))
                .toList();
    }

    @Transactional
    public UserAccount add(String rawPhone, String name) {
        String phone = Phones.normalize(rawPhone).orElseThrow(InvalidPhoneException::new);
        return addNormalized(phone, name);
    }

    /** Создание или реактивация (телефон уже нормализован). Живой номер → 409. */
    @Transactional
    public UserAccount addNormalized(String phone, String name) {
        Optional<UserAccount> existing = users.findByPhone(phone);
        if (existing.isPresent() && existing.get().getDeletedAt() == null) {
            throw new AlreadyMemberException();
        }
        UserAccount user = existing.orElseGet(UserAccount::new);
        user.setPhone(phone);
        user.setName(name);
        user.setDeletedAt(null); // реактивация: история броней возвращается владельцу номера
        return users.save(user);
    }

    @Transactional
    public void softDelete(long id) {
        UserAccount user = users.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserNotFoundException::new);
        if (user.getRole() == Role.ADMIN) {
            throw new CannotDeleteAdminException();
        }
        Integer active = jdbc.queryForObject("""
                select count(*) from bookings
                where user_id = ? and status in ('PENDING_OTP', 'CONFIRMED') and check_out > ?
                """, Integer.class, id, LocalDate.now(BookingService.JST));
        if (active != null && active > 0) {
            throw new ActiveBookingExistsException();
        }
        user.setDeletedAt(clock.instant());
        // доступ отозван — отзываем и Telegram-связку; при реактивации человек заново делится контактом с ботом
        user.setTelegramChatId(null);
        users.save(user);
    }

    /** null стирает приветствие — фронт вернётся к «Привет, {имя}!». */
    @Transactional
    public void setGreeting(long id, String greeting) {
        UserAccount user = users.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserNotFoundException::new);
        user.setGreeting(greeting);
        users.save(user);
    }
}
