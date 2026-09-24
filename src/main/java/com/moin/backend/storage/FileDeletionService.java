package com.moin.backend.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 외부 프로필 URL은 건드리지 않고 앱이 만든 UUID 파일만 삭제, 실패 시 접근 차단 후 재시도 */
@Service
public class FileDeletionService implements HandlerInterceptor {
    private final PendingFileDeletionRepository pending;
    private final ApplicationEventPublisher events;
    private final Path root;
    public record Requested() {}
    public FileDeletionService(PendingFileDeletionRepository pending, ApplicationEventPublisher events,
            @Value("${moin.upload-dir}") String root) {
        this.pending = pending; this.events = events; this.root = Path.of(root).toAbsolutePath();
    }
    private Path local(String url) {
        if (url == null || !url.matches("/(videos|avatars)/[0-9a-fA-F-]{36}\\.(jpg|mp4|mov)")) return null;
        return url.startsWith("/videos/") ? root.resolve(url.substring(8)) : root.resolve(url.substring(1));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String url) {
        if (local(url) == null) return;
        pending.save(new PendingFileDeletion(url));
        events.publishEvent(new Requested());
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void committed(Requested event) { cleanup(); }

    @Scheduled(fixedDelay = 60000)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanup() {
        for (var file : pending.findAll()) {
            var path = local(file.getPath());
            if (path == null) continue;
            try { Files.deleteIfExists(path); pending.delete(file); }
            catch (IOException ignored) { /* 접근 차단을 유지한 채 다음 주기에 재시도 */ }
        }
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (local(path) == null || pending.existsById(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return true;
    }
}
