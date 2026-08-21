package com.batowka.guestbooking.bot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Служебная аутентификация внутреннего бота: заголовок X-Bot-Token для /api/bot/**. */
@Component
public class BotTokenFilter extends OncePerRequestFilter {

    private final String token;

    public BotTokenFilter(@Value("${app.bot.api-token:}") String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // пустой конфиг-токен закрывает эндпоинт: заголовок никогда с ним не совпадёт
        if (!token.isBlank() && token.equals(request.getHeader("X-Bot-Token"))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "bot", null, List.of(new SimpleGrantedAuthority("ROLE_BOT")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
