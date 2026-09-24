package com.moin.backend.freeze;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 서버가 발급한 광고 시청 세션, 외부 콜백은 사용자 ID 대신 임의 토큰만 수신 */
@Entity
@Table(name = "freeze_ad_sessions")
@Getter
@NoArgsConstructor
public class AdSession {
    @Id private String id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private LocalDate week;
    @Column(nullable = false) private Instant expiresAt;
    public AdSession(Long userId, LocalDate week, Instant expiresAt) {
        id = UUID.randomUUID().toString(); this.userId = userId; this.week = week; this.expiresAt = expiresAt;
    }
}
