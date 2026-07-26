package com.corp.mcp.admin.idpmock;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IdP Mock 令牌服务：
 * <ul>
 *   <li>password 授权 → 用户 Token（会话层使用）</li>
 *   <li>client_credentials → Agent/网关服务 Token</li>
 *   <li>token-exchange（RFC 8693）→ Delegation Token / MCP Access Token，
 *       支持 act 委托链续接与 resource（audience）绑定</li>
 *   <li>introspect（RFC 7662）→ Token 验证</li>
 * </ul>
 *
 * act 链约定（与《MCP网关详细设计文档》4.2 一致）：外层 = 首跳 Actor，最内层 = 直接调用方。
 * 形如 {"sub":"employee-assistant","act":{"sub":"business-agent"}}。
 * 网关 client（mcp-gateway）执行 exchange 时不加入 act 链（基础设施身份，非委托方）。
 */
@Slf4j
@Service
@Profile("dev")
@RequiredArgsConstructor
public class IdpMockTokenService {

    /** 网关 client_id：exchange 换发 MCP Access Token 时不追加 act */
    public static final String GATEWAY_CLIENT_ID = "mcp-gateway";

    private final IdpMockKeyService keyService;

    // ---------- password 授权（用户登录） ----------

    public Map<String, Object> passwordGrant(String username, String password, String clientId) {
        Map<String, Object> user = IdpMockData.USERS.get(username);
        if (user == null || !user.get("password").equals(password)) {
            return null;
        }
        String holder = clientId != null ? clientId : "employee-assistant";
        String token = issueToken(username, "agent-session", "openid profile",
                null, holder, 3600);
        return tokenResponse(token, 3600, "openid profile");
    }

    // ---------- client_credentials（服务身份） ----------

    public Map<String, Object> clientCredentialsGrant(String clientId, String clientSecret, String scope) {
        if (!clientId.equals(clientSecret == null ? null : IdpMockData.CLIENTS.get(clientId))
                || !IdpMockData.CLIENTS.getOrDefault(clientId, "").equals(clientSecret)) {
            return null;
        }
        String token = issueToken(clientId, "mcp-gateway",
                scope == null || scope.isBlank() ? "mcp" : scope, null, clientId, 3600);
        return tokenResponse(token, 3600, scope);
    }

    // ---------- token-exchange（RFC 8693） ----------

    /**
     * @param subjectToken 原始 token（用户 token 或 delegation token）
     * @param actorToken   actor token（可选；为空时使用 clientId 作为 actor）
     * @param clientId     发起 exchange 的 client（Basic 认证身份）
     * @param resource     RFC 8707 资源 URI（MCP Access Token 的 audience）
     * @param scope        请求的 scope
     */
    public Map<String, Object> tokenExchange(String subjectToken, String actorToken,
                                             String clientId, String resource, String scope) {
        Claims subject = tryParse(subjectToken);
        if (subject == null) {
            return null;
        }
        // actor：优先 actor_token 的 sub，否则用 clientId
        String actor = clientId;
        if (actorToken != null && !actorToken.isBlank()) {
            Claims actorClaims = tryParse(actorToken);
            if (actorClaims == null) {
                return null;
            }
            actor = actorClaims.getSubject();
        }

        // act 链续接：网关 client 不追加；actor 已是内层末端时不重复追加
        Object priorAct = subject.get("act");
        Map<String, Object> newAct = appendActor(priorAct, actor);

        // audience：resource 优先（MCP Access Token）；否则 Delegation Token 固定为 mcp-gateway
        String audience = (resource != null && !resource.isBlank()) ? resource : "mcp-gateway";

        // 有效期：resource 绑定（MCP Access Token）→ 300s；否则 3600s
        long ttl = (resource != null && !resource.isBlank()) ? 300 : 3600;

        // 持有者 = actor（网关验证"持证者=委托链末端"的依据）
        String newScope = (scope != null && !scope.isBlank())
                ? scope : subject.get("scope", String.class);

        String token = issueToken(subject.getSubject(), audience, newScope, newAct, actor, ttl);
        Map<String, Object> response = tokenResponse(token, ttl, newScope);
        response.put("issued_token_type", "urn:ietf:params:oauth:token-type:access_token");
        return response;
    }

    // ---------- introspect（RFC 7662） ----------

    public Map<String, Object> introspect(String token) {
        Claims claims = tryParse(token);
        Map<String, Object> result = new LinkedHashMap<>();
        if (claims == null) {
            result.put("active", false);
            return result;
        }
        result.put("active", true);
        result.put("sub", claims.getSubject());
        result.put("aud", claims.getAudience());
        result.put("scope", claims.get("scope"));
        result.put("exp", claims.getExpiration().toInstant().getEpochSecond());
        result.put("iat", claims.getIssuedAt().toInstant().getEpochSecond());
        result.put("jti", claims.getId());
        result.put("iss", claims.getIssuer());
        result.put("client_id", claims.get("client_id"));
        if (claims.get("act") != null) {
            result.put("act", claims.get("act"));
        }
        return result;
    }

    // ---------- 内部 ----------

    @SuppressWarnings("deprecation")
    private String issueToken(String sub, String audience, String scope,
                              Map<String, Object> act, String holder, long ttlSeconds) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .header().keyId(keyService.getKeyId()).and()
                .issuer(IdpMockKeyService.ISSUER)
                .subject(sub)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("scope", scope)
                .claim("client_id", holder);
        if (act != null) {
            builder.claim("act", act);
        }
        return builder.signWith(keyService.getKeyPair().getPrivate(), Jwts.SIG.RS256).compact();
    }

    /**
     * act 链追加：priorAct 为 null → {"sub": actor}；
     * 否则将 actor 追加为最内层（外层保持原有顺序）。
     * 网关 client 与"已是末端"的 actor 不重复追加。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> appendActor(Object priorAct, String actor) {
        if (GATEWAY_CLIENT_ID.equals(actor)) {
            return priorAct == null ? null : deepCopy(priorAct);
        }
        if (priorAct == null) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("sub", actor);
            return act;
        }
        // 查找最内层
        Map<String, Object> prior = (Map<String, Object>) priorAct;
        String innermost = innermostSub(prior);
        if (actor.equals(innermost)) {
            return deepCopy(prior);
        }
        Map<String, Object> copy = deepCopy(prior);
        Map<String, Object> cursor = copy;
        while (cursor.get("act") instanceof Map) {
            cursor = (Map<String, Object>) cursor.get("act");
        }
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("sub", actor);
        cursor.put("act", leaf);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private String innermostSub(Map<String, Object> act) {
        Object nested = act.get("act");
        if (nested instanceof Map) {
            return innermostSub((Map<String, Object>) nested);
        }
        return String.valueOf(act.get("sub"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Object actObj) {
        Map<String, Object> src = (Map<String, Object>) actObj;
        Map<String, Object> copy = new LinkedHashMap<>(src);
        if (src.get("act") instanceof Map) {
            copy.put("act", deepCopy(src.get("act")));
        }
        return copy;
    }

    private Claims tryParse(String token) {
        try {
            return keyService.parseAndVerify(token);
        } catch (Exception e) {
            log.debug("token parse failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> tokenResponse(String token, long expiresIn, String scope) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", token);
        response.put("token_type", "Bearer");
        response.put("expires_in", expiresIn);
        if (scope != null) {
            response.put("scope", scope);
        }
        return response;
    }
}
