package com.moin.backend.freeze;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.moin.backend.user.*;

class FreezePurchaseTest {
    @Test
    void 상품별_수량을_서버에서_결정하고_같은_구매를_중복_지급하지_않는다() {
        assertEquals(1, StoreVerifier.units(StoreVerifier.PRODUCT));
        assertEquals(10, StoreVerifier.units(StoreVerifier.TEN_PACK));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> StoreVerifier.units("fake.ten"));
        var user = new User("dev:shop", "테스트", null); user.setId(1L);
        var users = mock(UserRepository.class);
        when(users.lockById(1L)).thenReturn(Optional.of(user)); when(users.findById(1L)).thenReturn(Optional.of(user));
        var store = mock(StoreVerifier.class);
        when(store.verify(1L, "ios", "receipt-ten")).thenReturn(new StoreVerifier.Verified("apple:ten", StoreVerifier.units(StoreVerifier.TEN_PACK)));
        var grants = mock(FreezeGrantRepository.class);
        Map<String, FreezeGrant> saved = new HashMap<>();
        when(grants.findById(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(grants.saveAndFlush(any())).thenAnswer(call -> { FreezeGrant g = call.getArgument(0); saved.put(g.getId(), g); return g; });
        var service = new FreezeShopService(users, grants, mock(AdSessionRepository.class), store, Clock.systemUTC(), ZoneId.of("Asia/Seoul"));
        assertEquals(10, service.purchase(1L, "ios", "receipt-ten").balance());
        assertEquals(10, service.purchase(1L, "ios", "receipt-ten").balance());
        assertEquals(1, saved.size());
    }
}
