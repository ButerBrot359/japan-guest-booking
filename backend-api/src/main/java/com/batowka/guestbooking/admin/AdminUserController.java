package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.user.WhitelistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final WhitelistService whitelist;

    public record AddRequest(@NotBlank String phone, @NotBlank @Size(max = 100) String name) {
    }

    @GetMapping
    public List<WhitelistService.UserRow> list() {
        return whitelist.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void add(@Valid @RequestBody AddRequest body) {
        whitelist.add(body.phone(), body.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        whitelist.softDelete(id);
    }
}
