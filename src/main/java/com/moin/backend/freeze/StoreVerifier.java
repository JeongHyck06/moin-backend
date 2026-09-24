package com.moin.backend.freeze;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.server.ResponseStatusException;
import com.apple.itunes.storekit.client.AppStoreServerAPIClient;
import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.google.auth.oauth2.GoogleCredentials;

/** 스토어의 현재 구매 상태·상품·앱 계정 귀속을 모두 검증한 거래만 지급 */
@Component
public class StoreVerifier {
    public static final String PRODUCT = "com.moin.freeze.one";
    public static final String TEN_PACK = "com.moin.freeze.ten";
    public record Product(String productId, int quantity) {}
    public static final List<Product> PRODUCTS = List.of(new Product(PRODUCT, 1), new Product(TEN_PACK, 10));

    /** 영수증의 상품 ID만 수량으로 변환, 클라이언트가 보낸 수량은 신뢰하지 않음 */
    public static int units(String productId) {
        return PRODUCTS.stream().filter(p -> p.productId().equals(productId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 프리즈 상품이에요")).quantity();
    }
    @Value("${moin.store.apple-key:}") private String appleKey;
    @Value("${moin.store.apple-key-id:}") private String appleKeyId;
    @Value("${moin.store.apple-issuer:}") private String appleIssuer;
    @Value("${moin.store.google-credentials:}") private String googleCredentials;
    @Value("${moin.store.allow-sandbox:false}") private boolean sandbox;
    public record Verified(String id, int quantity) {}
    private final RestClient http;

    public StoreVerifier() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean appleReady() { return !appleKey.isBlank() && Files.isReadable(Path.of(appleKey)) && !appleKeyId.isBlank() && !appleIssuer.isBlank(); }
    public boolean googleReady() { return !googleCredentials.isBlank() && Files.isReadable(Path.of(googleCredentials)); }
    public static UUID account(Long userId) { return UUID.nameUUIDFromBytes(("moin-store:" + userId).getBytes(StandardCharsets.UTF_8)); }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Verified verify(Long userId, String platform, String token) {
        if (!(platform.equals("ios") ? appleReady() : platform.equals("android") && googleReady()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "스토어 구매를 준비 중이에요");
        try {
            return platform.equals("ios") ? apple(userId, token) : google(userId, token);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "구매 확인을 완료하지 못했어요. 잠시 후 다시 확인해주세요"); }
    }

    private SignedDataVerifier appleVerifier(Environment env) throws Exception {
        try (var root = getClass().getResourceAsStream("/certificates/AppleRootCA-G3.cer")) {
            return new SignedDataVerifier(Set.of(root), "com.moin", 6815096987L, env, true);
        }
    }

    private Verified apple(Long userId, String token) throws Exception {
        Environment env = Environment.PRODUCTION;
        var verifier = appleVerifier(env);
        com.apple.itunes.storekit.model.JWSTransactionDecodedPayload transaction;
        try { transaction = verifier.verifyAndDecodeTransaction(token); }
        catch (com.apple.itunes.storekit.verification.VerificationException e) {
            if (!sandbox) throw e;
            env = Environment.SANDBOX; verifier = appleVerifier(env);
            transaction = verifier.verifyAndDecodeTransaction(token);
        }
        var client = new AppStoreServerAPIClient(Files.readString(Path.of(appleKey)), appleKeyId, appleIssuer, "com.moin", env);
        transaction = verifier.verifyAndDecodeTransaction(client.getTransactionInfo(transaction.getTransactionId()).getSignedTransactionInfo());
        int units = units(transaction.getProductId());
        if (!account(userId).equals(transaction.getAppAccountToken())
                || transaction.getRevocationDate() != null || transaction.getQuantity() == null || transaction.getQuantity() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 계정의 유효한 프리즈 구매가 아니에요");
        return new Verified("apple:" + transaction.getTransactionId(), units);
    }

    @SuppressWarnings("unchecked")
    private Verified google(Long userId, String token) throws Exception {
        GoogleCredentials credentials;
        try (var stream = Files.newInputStream(Path.of(googleCredentials))) {
            credentials = GoogleCredentials.fromStream(stream).createScoped("https://www.googleapis.com/auth/androidpublisher");
        }
        credentials.refreshIfExpired();
        var response = http.get()
                .uri("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.moin/purchases/productsv2/tokens/{token}", token)
                .headers(h -> h.setBearerAuth(credentials.getAccessToken().getTokenValue())).retrieve().body(Map.class);
        var state = (Map<String, Object>) response.get("purchaseStateContext");
        var lines = (List<Map<String, Object>>) response.get("productLineItem");
        if (!"PURCHASED".equals(state.get("purchaseState")) || !account(userId).toString().equals(response.get("obfuscatedExternalAccountId"))
                || (!sandbox && response.get("testPurchaseContext") != null) || lines == null || lines.size() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 계정의 유효한 프리즈 구매가 아니에요");
        int units = units((String) lines.get(0).get("productId"));
        var offer = (Map<String, Object>) lines.get(0).get("productOfferDetails");
        int quantity = ((Number) offer.getOrDefault("quantity", 1)).intValue();
        int refundable = ((Number) offer.getOrDefault("refundableQuantity", quantity)).intValue();
        if (quantity != 1 || refundable != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "취소되었거나 지원하지 않는 구매예요");
        return new Verified("google:" + digest(token), units);
    }
}
