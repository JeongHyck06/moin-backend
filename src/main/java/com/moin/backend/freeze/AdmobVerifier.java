package com.moin.backend.freeze;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** 파라미터 원문으로 AdMob ECDSA 서명 검증, 미검증 콜백과 중복 파라미터는 거부 */
@Component
public class AdmobVerifier {
    private Map<String, PublicKey> keys = Map.of();
    private Instant fetched = Instant.EPOCH;
    private final RestClient http;

    public AdmobVerifier() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        http = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, String> verify(String raw) throws Exception {
        if (raw == null || raw.length() > 10000) throw new GeneralSecurityException();
        int index = raw.indexOf("&signature=");
        if (index < 1) throw new GeneralSecurityException();
        String[] tail = raw.substring(index + 1).split("&");
        if (tail.length != 2 || !tail[1].startsWith("key_id=")) throw new GeneralSecurityException();
        var values = new HashMap<String, String>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || values.putIfAbsent(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
                throw new GeneralSecurityException();
        }
        PublicKey key = key(values.get("key_id"));
        if (key == null) throw new GeneralSecurityException();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(raw.substring(0, index).getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.getUrlDecoder().decode(values.get("signature")))) throw new GeneralSecurityException();
        return values;
    }

    @SuppressWarnings("unchecked")
    private synchronized PublicKey key(String id) throws Exception {
        if (fetched.isBefore(Instant.now().minusSeconds(3600)) || (!keys.containsKey(id) && fetched.isBefore(Instant.now().minusSeconds(60)))) {
            var body = http.get().uri("https://www.gstatic.com/admob/reward/verifier-keys.json").retrieve().body(Map.class);
            var fresh = new HashMap<String, PublicKey>();
            for (var item : (List<Map<String, Object>>) body.get("keys")) {
                fresh.put(item.get("keyId").toString(), KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode((String) item.get("base64")))));
            }
            keys = fresh; fetched = Instant.now();
        }
        return keys.get(id);
    }
}
