package com.corp.mcp.admin.idpmock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * IdP Mock 控制器（dev 环境内嵌，生产由企业认证中心替代）。
 * 端点契约见《MCP网关详细设计文档》6.5 节。
 */
@Slf4j
@RestController
@RequestMapping("/idp-mock")
@Profile("dev")
@RequiredArgsConstructor
public class IdpMockController {

    private final IdpMockTokenService tokenService;
    private final IdpMockKeyService keyService;

    /**
     * 令牌端点：password / client_credentials / token-exchange。
     * client 身份从 Basic 头解析（无 Basic 时取表单 client_id）。
     */
    @PostMapping(value = "/oauth/token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam Map<String, String> form,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        String[] basic = parseBasicAuth(authorization);
        String clientId = basic != null ? basic[0] : form.get("client_id");
        String clientSecret = basic != null ? basic[1] : form.get("client_secret");

        String grantType = form.get("grant_type");
        Map<String, Object> result = switch (grantType == null ? "" : grantType) {
            case "password" -> passwordGrant(form, clientId);
            case "client_credentials" -> tokenService.clientCredentialsGrant(clientId, clientSecret, form.get("scope"));
            case "urn:ietf:params:oauth:grant-type:token-exchange" ->
                    tokenService.tokenExchange(form.get("subject_token"), form.get("actor_token"),
                            clientId, firstNonBlank(form.get("resource"), form.get("audience")), form.get("scope"));
            default -> null;
        };

        if (result == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "invalid_grant", "error_description", "mock idp rejected the request"));
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> passwordGrant(Map<String, String> form, String clientId) {
        // password grant 的 client 校验放宽（公共客户端），仅校验用户口令
        return tokenService.passwordGrant(form.get("username"), form.get("password"), clientId);
    }

    /** Token 验证（RFC 7662） */
    @PostMapping(value = "/oauth/introspect", consumes = "application/x-www-form-urlencoded")
    public Map<String, Object> introspect(@RequestParam Map<String, String> form) {
        return tokenService.introspect(form.get("token"));
    }

    /** JWKS 公钥（RFC 7517） */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keyService.jwks();
    }

    /** 用户信息 */
    @GetMapping("/api/users/{userId}")
    public ResponseEntity<Map<String, Object>> user(@PathVariable String userId) {
        Map<String, Object> info = IdpMockData.publicUserInfo(userId);
        return info == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(info);
    }

    /** Agent 信息 */
    @GetMapping("/api/agents/{agentId}")
    public ResponseEntity<Map<String, Object>> agent(@PathVariable String agentId) {
        Map<String, Object> info = IdpMockData.AGENTS.get(agentId);
        return info == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(info);
    }

    private String[] parseBasicAuth(String header) {
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            return idx < 0 ? null : new String[]{decoded.substring(0, idx), decoded.substring(idx + 1)};
        } catch (Exception e) {
            return null;
        }
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
