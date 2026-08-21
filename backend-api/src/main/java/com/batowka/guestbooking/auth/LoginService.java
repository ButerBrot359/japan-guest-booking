package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.booking.TelegramNotLinkedException;
import com.batowka.guestbooking.otp.OtpService;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class LoginService {

    private final UserAccountRepository users;
    private final OtpService otp;

    /** Шаг 1 входа: код в Telegram. Куку не выдаёт. */
    @Transactional
    public void requestCode(String rawPhone) {
        UserAccount user = findFriend(rawPhone);
        // без Telegram код доставить некуда — фронт покажет инструкцию привязки
        if (user.getTelegramChatId() == null) {
            throw new TelegramNotLinkedException();
        }
        otp.issue(user, "LOGIN", Map.of());
    }

    UserAccount findFriend(String rawPhone) {
        String phone = Phones.normalize(rawPhone).orElseThrow(InvalidPhoneException::new);
        // Роль ADMIN сюда не пускаем: беспарольный вход не должен выдавать админский токен
        return users.findByPhoneAndDeletedAtIsNull(phone)
                .filter(u -> u.getRole() == Role.FRIEND)
                .orElseThrow(UnknownPhoneException::new);
    }
}
