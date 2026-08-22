package com.batowka.guestbooking.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Скользящее окно по произвольному ключу (обычно «бакет:IP»). In-memory, на один процесс. */
@Component
public class SlidingWindowRateLimiter {

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public SlidingWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** true — попытка разрешена и учтена; false — лимит исчерпан. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        while (true) {
            Deque<Instant> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                if (attempts.get(key) != q) {
                    continue; // эвикция выдернула deque между computeIfAbsent и локом — берём заново
                }
                while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(window))) {
                    q.pollFirst();
                }
                if (q.size() >= limit) {
                    return false;
                }
                q.addLast(now);
                return true;
            }
        }
    }

    /** Выселяем ключи с опустевшими окнами — иначе мапа растёт на каждый новый IP вечно. */
    @Scheduled(fixedDelay = 600_000)
    public void evictEmpty() {
        // срез по самому длинному используемому окну (1 минута); окна длиннее — увеличить срез
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(1));
        for (Map.Entry<String, Deque<Instant>> e : attempts.entrySet()) {
            Deque<Instant> q = e.getValue();
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                    q.pollFirst();
                }
                if (q.isEmpty()) {
                    attempts.remove(e.getKey(), q);
                }
            }
        }
    }

    public void reset() {
        attempts.clear();
    }
}
