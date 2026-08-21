package com.batowka.guestbooking.bot;

import com.batowka.guestbooking.accessrequest.AccessRequestService;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bot/access-requests")
@RequiredArgsConstructor
public class BotAdminController {

    private final UserAccountRepository users;
    private final AccessRequestService accessRequests;

    public record DecisionRequest(@NotNull Long adminChatId) {
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable long id, @Valid @RequestBody DecisionRequest body) {
        requireAdmin(body.adminChatId());
        accessRequests.approve(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable long id, @Valid @RequestBody DecisionRequest body) {
        requireAdmin(body.adminChatId());
        accessRequests.reject(id);
        return ResponseEntity.noContent().build();
    }

    // защита в глубину: кнопки видны только в админском чате, но бэкенд не доверяет
    // этому вслепую — chat_id обязан принадлежать живому ADMIN
    private void requireAdmin(Long chatId) {
        boolean isAdmin = users.findByTelegramChatIdAndDeletedAtIsNull(chatId)
                .map(u -> u.getRole() == Role.ADMIN)
                .orElse(false);
        if (!isAdmin) {
            throw new AccessDeniedException("Недостаточно прав");
        }
    }
}
