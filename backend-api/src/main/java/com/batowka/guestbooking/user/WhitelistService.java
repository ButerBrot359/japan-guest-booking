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
    private final UserGreetingRepository greetingRepo;
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
        // >= (не >): с инклюзивной семантикой V8 бронь с check_out = сегодня ещё
        // занимает дом сегодня (см. BookingService.completePastBooking) — гостя
        // с такой бронью удалять нельзя, пока она не завершится завтра
        Integer active = jdbc.queryForObject("""
                select count(*) from bookings
                where user_id = ? and status = 'CONFIRMED' and check_out >= ?
                """, Integer.class, id, LocalDate.now(BookingService.JST));
        if (active != null && active > 0) {
            throw new ActiveBookingExistsException();
        }
        user.setDeletedAt(clock.instant());
        // доступ отозван — отзываем и Telegram-связку; при реактивации человек заново делится контактом с ботом
        user.setTelegramChatId(null);
        users.save(user);
    }

    /** Полная замена набора приветствий гостя; blank-строки отбрасываются. */
    @Transactional
    public void setGreetings(long id, List<String> greetings) {
        UserAccount user = users.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserNotFoundException::new);
        greetingRepo.deleteByUserId(user.getId());
        greetings.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .forEach(text -> {
                    UserGreeting g = new UserGreeting();
                    g.setUserId(user.getId());
                    g.setText(text);
                    greetingRepo.save(g);
                });
    }

    /** Случайное приветствие из набора; выбор в БД — order by random(). */
    public Optional<String> randomGreeting(Long userId) {
        return jdbc.query(
                "select text from user_greetings where user_id = ? order by random() limit 1",
                (rs, i) -> rs.getString(1), userId).stream().findFirst();
    }
}
