package com.moin.backend.freeze;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.moin.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/** 획득 원장과 잔액을 함께 저장, 광고는 한국 시간 월요일부터 계정당 주 1회 */
@Service
@RequiredArgsConstructor
public class FreezeShopService {
    private final UserRepository users;
    private final FreezeGrantRepository grants;
    private final AdSessionRepository sessions;
    private final StoreVerifier store;
    private final Clock clock;
    private final ZoneId zone;
    @Value("${moin.admob.android-unit:}") private String androidUnit;
    @Value("${moin.admob.ios-unit:}") private String iosUnit;
    public record Wallet(int balance, boolean adAvailable, LocalDate nextAdDate, String productId,
            String accountToken, boolean appleReady, boolean googleReady, String androidAdUnit, String iosAdUnit) {}
    public record Session(String id) {}
    private LocalDate week() { return LocalDate.now(clock.withZone(zone)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    private String adKey(Long id, LocalDate week) { return "ad-week:" + id + ":" + week; }
    public Wallet wallet(Long userId) {
        var user = users.findById(userId).orElseThrow();
        boolean available = !grants.existsById(adKey(userId, week()));
        return new Wallet(user.getFreezeBalance(), available, week().plusWeeks(1), StoreVerifier.PRODUCT,
                StoreVerifier.account(userId).toString(), store.appleReady(), store.googleReady(), androidUnit, iosUnit);
    }

    @Transactional
    public Wallet purchase(Long userId, String platform, String token) {
        var verified = store.verify(userId, platform, token);
        var user = users.lockById(userId).orElseThrow();
        var existing = grants.findById(verified.id());
        if (existing.isPresent()) {
            if (!existing.get().getUserId().equals(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 계정에 지급된 구매예요");
            return wallet(userId);
        }
        grants.saveAndFlush(new FreezeGrant(verified.id(), userId, verified.quantity(), clock.instant()));
        user.setFreezeBalance(Math.addExact(user.getFreezeBalance(), verified.quantity()));
        return wallet(userId);
    }

    @Transactional
    public Session startAd(Long userId) {
        users.lockById(userId).orElseThrow();
        if (androidUnit.isBlank() && iosUnit.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "광고 보상을 준비 중이에요");
        if (grants.existsById(adKey(userId, week()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "이번 주 광고 보상을 이미 받았어요");
        return new Session(sessions.save(new AdSession(userId, week(), clock.instant().plusSeconds(3600))).getId());
    }

    /** 서명 검증을 통과한 광고 세션만 사용, 클라이언트의 시청 완료 이벤트는 지급 근거가 아님 */
    @Transactional
    public void reward(Map<String, String> data) {
        String unit = data.get("ad_unit");
        if (!matchesUnit(unit, androidUnit) && !matchesUnit(unit, iosUnit)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ad unit");
        if (!"1".equals(data.get("reward_amount")) || !"freeze".equals(data.get("reward_item"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reward");
        String sessionId = data.get("custom_data");
        if (sessionId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing session");
        AdSession session = sessions.findById(sessionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown session"));
        var user = users.lockById(session.getUserId()).orElseThrow();
        String transaction = data.get("transaction_id");
        if (transaction == null || transaction.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing transaction");
        String transactionKey = "ad:" + StoreVerifier.digest(transaction);
        String weekKey = adKey(user.getId(), session.getWeek());
        if (grants.existsById(transactionKey) || grants.existsById(weekKey)) return;
        Instant watched;
        try { watched = Instant.ofEpochMilli(Long.parseLong(data.get("timestamp"))); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp"); }
        if (watched.isAfter(session.getExpiresAt()) || watched.isBefore(session.getExpiresAt().minusSeconds(3600))
                || watched.isAfter(clock.instant().plusSeconds(60)) || !watched.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).equals(session.getWeek()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expired session");
        grants.save(new FreezeGrant(transactionKey, user.getId(), 1, clock.instant()));
        grants.saveAndFlush(new FreezeGrant(weekKey, user.getId(), 0, clock.instant()));
        user.setFreezeBalance(Math.addExact(user.getFreezeBalance(), 1));
    }
    private boolean matchesUnit(String received, String configured) {
        return !configured.isBlank() && (configured.equals(received) || configured.substring(configured.lastIndexOf('/') + 1).equals(received));
    }
}
