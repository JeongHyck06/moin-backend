package com.moin.backend.freeze;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.moin.backend.checkin.*;
import com.moin.backend.group.*;
import com.moin.backend.period.PeriodService;
import com.moin.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/** 모임별 월 무료분 우선 사용, 구매·광고 보관함은 계정 공용 */
@Service
@RequiredArgsConstructor
public class FreezeService {
    private final GroupService groups;
    private final CheckInRepository checkIns;
    private final PeriodService periods;
    private final UserRepository users;

    public record Inventory(boolean enabled, int monthlyRemaining, int balance, int available,
            LocalDate today, LocalDate earliestDate, List<LocalDate> checkedDates, List<LocalDate> frozenDates) {}

    public Inventory inventory(Long groupId, Long userId, YearMonth month) {
        Group g = groups.get(groupId);
        GroupMember member = groups.membership(groupId, userId);
        LocalDate today = periods.today(g);
        int free = g.isStreakFreeze() && !checkIns.existsByGroupIdAndUserIdAndFreezeMonth(groupId, userId, today.withDayOfMonth(1)) ? 1 : 0;
        int balance = users.findById(userId).orElseThrow().getFreezeBalance();
        List<CheckIn> mine = checkIns.findByGroupIdAndLogicalDateBetweenOrderByCreatedAtAsc(groupId, month.atDay(1), month.atEndOfMonth())
                .stream().filter(c -> c.getUserId().equals(userId)).toList();
        LocalDate joined = periods.logicalDate(member.getJoinedAt(), g.getResetTime());
        LocalDate earliest = joined.isAfter(member.getActiveFrom()) ? joined : member.getActiveFrom();
        return new Inventory(g.isStreakFreeze(), free, balance, g.isStreakFreeze() ? free + Math.max(0, balance) : 0,
                today, earliest, mine.stream().map(CheckIn::getLogicalDate).toList(), mine.stream().filter(CheckIn::isFrozen).map(CheckIn::getLogicalDate).toList());
    }

    /** 날짜 중복과 잔액 차감을 한 트랜잭션으로, 업로드·마감과 동일한 그룹 잠금 순서 */
    @Transactional
    public Inventory use(Long groupId, Long userId, LocalDate date) {
        Group g = groups.getForUpdate(groupId);
        GroupMember member = groups.membership(groupId, userId);
        var user = users.lockById(userId).orElseThrow();
        LocalDate today = periods.today(g);
        if (!g.isStreakFreeze()) throw new ResponseStatusException(HttpStatus.CONFLICT, "프리즈가 꺼진 모임이에요");
        if (date.isAfter(today) || date.isBefore(member.getActiveFrom()) || date.isBefore(periods.logicalDate(member.getJoinedAt(), g.getResetTime())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "참여한 날부터 오늘까지 선택해주세요");
        if (checkIns.existsByGroupIdAndUserIdAndLogicalDate(groupId, userId, date))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 인증한 날짜예요");
        LocalDate month = today.withDayOfMonth(1);
        boolean free = !checkIns.existsByGroupIdAndUserIdAndFreezeMonth(groupId, userId, month);
        if (!free && user.getFreezeBalance() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "보유한 프리즈가 없어요");
        if (!free) user.setFreezeBalance(user.getFreezeBalance() - 1);
        periods.closeDuePeriods(g);
        checkIns.saveAndFlush(CheckIn.freeze(groupId, userId, date, free ? month : null, periods.now()));
        periods.refreshClosedPeriod(g, date);
        return inventory(groupId, userId, YearMonth.from(date));
    }
}
