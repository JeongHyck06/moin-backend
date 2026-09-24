package com.moin.backend.freeze;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 스토어 거래·광고 주차의 중복 지급 방지 원장, 원문 영수증은 저장하지 않음 */
@Entity
@Table(name = "freeze_grants")
@Getter
@NoArgsConstructor
public class FreezeGrant {
    @Id @Column(length = 160) private String id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private int quantity;
    @Column(nullable = false) private Instant createdAt;
    public FreezeGrant(String id, Long userId, int quantity, Instant at) {
        this.id = id; this.userId = userId; this.quantity = quantity; this.createdAt = at;
    }
}
