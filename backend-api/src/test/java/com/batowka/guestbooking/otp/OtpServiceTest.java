package com.batowka.guestbooking.otp;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpServiceTest extends AbstractIntegrationTest {

    @Autowired OtpService otp;
    @Autowired UserAccountRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private UserAccount guest;

    @BeforeEach
    void createGuest() {
        UserAccount u = new UserAccount();
        u.setPhone("+81310000001");
        u.setName("Маша");
        u.setTelegramChatId(777001L);
        guest = users.save(u);
    }

    private void issue(long bookingId) {
        tx.executeWithoutResult(s ->
                otp.issue(guest, "CREATE_BOOKING", Map.of("booking_id", bookingId)));
    }

    @Autowired
    tools.jackson.databind.ObjectMapper objectMapper;

    private String issuedCode() {
        // код не хранится — достаём из события OTP_CODE в outbox.
        // ВАЖНО: парсим JSON, НЕ регулярки/подстроки — jsonb нормализует
        // форматирование (грабля этапа 3).
        String envelope = jdbc.queryForObject("""
                select payload::text from outbox where event_type = 'OTP_CODE'
                order by id desc limit 1
                """, String.class);
        String code = objectMapper.readTree(envelope).get("payload").get("code").asString();
        assertThat(code).hasSize(6);
        return code;
    }

    @Test
    void issueCreatesChallengeAndOtpEvent() {
        issue(101L);

        assertThat(jdbc.queryForObject("""
                select count(*) from otp_challenges
                where user_id = ? and status = 'PENDING' and code_hash is not null
                """, Integer.class, guest.getId())).isEqualTo(1);
        assertThat(issuedCode()).hasSize(6);
        // сам код в БД не лежит
        assertThat(jdbc.queryForObject(
                "select code_hash from otp_challenges order by id desc limit 1", String.class))
                .isNotEqualTo(issuedCode());
    }

    @Test
    void correctCodeVerifiesAndUsesChallenge() {
        issue(102L);
        String code = issuedCode();

        var result = tx.execute(s -> otp.verifyByAction(guest.getId(), "CREATE_BOOKING", code));

        assertThat(result.action()).isEqualTo("CREATE_BOOKING");
        assertThat(result.payload().get("booking_id").asLong()).isEqualTo(102L);
        assertThat(jdbc.queryForObject(
                "select status from otp_challenges order by id desc limit 1", String.class))
                .isEqualTo("USED");
    }

    @Test
    void thirdWrongAttemptExpiresChallenge() {
        issue(103L);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    otp.verifyByAction(guest.getId(), "CREATE_BOOKING", "000000")))
                    .isInstanceOf(InvalidCodeException.class);
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verifyByAction(guest.getId(), "CREATE_BOOKING", "000000")))
                .isInstanceOf(CodeExpiredException.class);
        // после исчерпания даже верный код не работает
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verifyByAction(guest.getId(), "CREATE_BOOKING", issuedCode())))
                .isInstanceOf(NoActiveCodeException.class);
    }

    @Test
    void expiredChallengeRejectsAnyCode() {
        issue(104L);
        String code = issuedCode();
        jdbc.update("update otp_challenges set expires_at = now() - interval '1 second'");

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verifyByAction(guest.getId(), "CREATE_BOOKING", code)))
                .isInstanceOf(InvalidCodeException.class);
    }

    @Test
    void newIssueEvictsOldChallenge() {
        issue(105L);
        issue(105L);

        assertThat(jdbc.queryForObject(
                "select count(*) from otp_challenges where status = 'PENDING'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void codeForAnotherActionIsNotAccepted() {
        issue(107L);
        String code = issuedCode();

        // код выпущен для CREATE_BOOKING — по другому action активного челленджа нет
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verifyByAction(guest.getId(), "RESCHEDULE", code)))
                .isInstanceOf(NoActiveCodeException.class);
    }
}
