package com.moin.backend.freeze;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class FreezeShopController {
    private final FreezeShopService service;
    private final AdmobVerifier verifier;
    public record Purchase(@Pattern(regexp = "ios|android") @NotNull String platform, @NotBlank @Size(max = 30000) String token) {}
    @GetMapping("/me/freezes")
    public FreezeShopService.Wallet wallet(@RequestAttribute("userId") Long id) { return service.wallet(id); }
    @PostMapping("/me/freezes/purchases")
    public FreezeShopService.Wallet purchase(@RequestAttribute("userId") Long id, @Valid @RequestBody Purchase body) { return service.purchase(id, body.platform(), body.token()); }
    @PostMapping("/me/freezes/ad-sessions")
    public FreezeShopService.Session session(@RequestAttribute("userId") Long id) { return service.startAd(id); }
    @GetMapping("/callbacks/admob")
    public void callback(HttpServletRequest request) {
        java.util.Map<String, String> data;
        try { data = verifier.verify(request.getQueryString()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ad signature"); }
        // AdMob 콘솔 연결 검사도 Google 서명 검증 필수, 잔액 지급 없이 응답만 확인
        if ("moin-ssv-check".equals(data.get("custom_data"))) return;
        service.reward(data);
    }
}
