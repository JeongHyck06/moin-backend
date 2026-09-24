package com.moin.backend.user;

import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.moin.backend.auth.SessionRepository;
import com.moin.backend.checkin.*;
import com.moin.backend.freeze.*;
import com.moin.backend.group.*;
import com.moin.backend.period.PeriodRepository;
import com.moin.backend.storage.FileDeletionService;
import lombok.RequiredArgsConstructor;

/** 본인 데이터와 모든 세션 삭제, 남은 모임은 가장 먼저 가입한 멤버에게 방장 이양 */
@Service
@RequiredArgsConstructor
public class AccountDeletionService {
    private final UserRepository users;
    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final CheckInRepository checkIns;
    private final CheckInCommentRepository comments;
    private final PeriodRepository periods;
    private final SessionRepository sessions;
    private final PushDeviceRepository devices;
    private final FreezeGrantRepository grants;
    private final AdSessionRepository adSessions;
    private final FileDeletionService files;
    public record Result(boolean appleManualRevocationRequired) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Result delete(Long userId, boolean confirmed) {
        if (!confirmed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회원 탈퇴 확인이 필요해요");
        // 인증·프리즈와 같은 그룹-사용자 잠금 순서, 동시 모임 가입은 재확인 후 재시도
        var ids = members.findByUserId(userId).stream().map(GroupMember::getGroupId).distinct().sorted().toList();
        var locked = ids.stream().map(id -> groups.lockById(id).orElseThrow()).toList();
        var user = users.lockById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var current = members.findByUserId(userId).stream().map(GroupMember::getGroupId).distinct().sorted().toList();
        if (!ids.equals(current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "모임 정보가 변경됐어요. 탈퇴를 다시 시도해주세요");
        var owned = checkIns.findByUserId(userId);
        owned.forEach(c -> files.enqueue(c.getVideoUrl()));
        files.enqueue(user.getAvatarUrl());
        comments.deleteByUserId(userId);
        if (!owned.isEmpty()) comments.deleteByCheckInIdIn(owned.stream().map(CheckIn::getId).toList());
        checkIns.deleteByUserId(userId);
        members.deleteByUserId(userId);
        for (var group : locked) {
            var remaining = members.findByGroupId(group.getId());
            if (remaining.isEmpty()) {
                periods.deleteByGroupId(group.getId());
                groups.delete(group);
            } else if (group.getOwnerId().equals(userId)) {
                group.setOwnerId(remaining.stream().min(Comparator.comparing(GroupMember::getJoinedAt).thenComparing(GroupMember::getId)).orElseThrow().getUserId());
            }
        }
        sessions.deleteByUserId(userId);
        devices.deleteByUserId(userId);
        adSessions.deleteByUserId(userId);
        grants.deleteByUserId(userId);
        boolean apple = user.getExternalId().startsWith("apple:");
        users.delete(user);
        // 기존 Apple 로그인은 갱신 토큰을 보관하지 않아 TN3194의 수동 연결 해제 안내 사용
        return new Result(apple);
    }
}
