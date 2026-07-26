package com.corp.mcp.admin.idpmock;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * IdP Mock 密钥服务：启动时生成 RSA-2048 密钥对（dev 环境重启即轮换，可接受），
 * 提供 JWT 验签与 JWKS 公钥发布。
 */
@Slf4j
@Service
@Profile("dev")
public class IdpMockKeyService {

    public static final String ISSUER = "http://localhost:8080/idp-mock";

    @Getter
    private final KeyPair keyPair;
    @Getter
    private final String keyId = "mock-key-1";

    public IdpMockKeyService() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
            log.info("IdP Mock RSA keypair generated, kid={}", keyId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate IdP mock keypair", e);
        }
    }

    public Claims parseAndVerify(String jwt) {
        return Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }

    /** JWKS 公钥集（RFC 7517） */
    public Map<String, Object> jwks() {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        Map<String, Object> jwk = Map.of(
                "kty", "RSA",
                "kid", keyId,
                "use", "sig",
                "alg", "RS256",
                "n", enc.encodeToString(pub.getModulus().toByteArray()),
                "e", enc.encodeToString(pub.getPublicExponent().toByteArray()));
        return Map.of("keys", List.of(jwk));
    }
}
