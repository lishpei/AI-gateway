package com.corp.mcp.admin.service;

import com.corp.mcp.admin.domain.dto.AuthCheckDTO;
import com.corp.mcp.admin.domain.entity.AuthPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 策略求值语义单测（设计文档 8.1）：
 * 授权对象匹配、DENY 优先、默认拒绝、通配工具、约束合并、dataScope 取最大。
 */
@ExtendWith(MockitoExtension.class)
class PolicyEvaluationServiceTest {

    @Mock
    private RedisSyncService syncService;

    @Mock
    private IdPClientService idPClient;

    @InjectMocks
    private PolicyEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        lenient().when(idPClient.getUserRoles(anyString())).thenReturn(List.of());
        lenient().when(idPClient.getUserGroups(anyString())).thenReturn(List.of());
    }

    private AuthPolicy policy(String tool, String granteeType, String granteeId,
                              String effect, String dataScope, String constraints) {
        AuthPolicy p = new AuthPolicy();
        p.setId(1L);
        p.setToolName(tool);
        p.setGranteeType(granteeType);
        p.setGranteeId(granteeId);
        p.setEffect(effect);
        p.setDataScope(dataScope);
        p.setConstraints(constraints);
        p.setStatus(AuthPolicy.STATUS_EFFECTIVE);
        return p;
    }

    private AuthCheckDTO.Request request(List<String> chain, String userId, String tool) {
        AuthCheckDTO.Request r = new AuthCheckDTO.Request();
        r.setAgentChain(chain);
        r.setUserId(userId);
        r.setServerId("attendance-mcp");
        r.setToolName(tool);
        return r;
    }

    @Test
    void allowWhenAgentInChain() {
        when(syncService.selectEffectivePolicies(eq("attendance-mcp"), any()))
                .thenReturn(List.of(policy("attendance.query", "AGENT", "business-agent", "ALLOW", "self", null)));

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("employee-assistant", "business-agent"), "alice", "attendance.query"));

        assertTrue(r.isAllowed());
        assertEquals("self", r.getDataScope());
    }

    @Test
    void allowWhenUserMatches() {
        when(syncService.selectEffectivePolicies(anyString(), any()))
                .thenReturn(List.of(policy("*", "USER", "alice", "ALLOW", "team", null)));

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("business-agent"), "alice", "any.tool"));

        assertTrue(r.isAllowed());
        assertEquals("team", r.getDataScope());
    }

    @Test
    void denyByDefaultWhenNoPolicy() {
        when(syncService.selectEffectivePolicies(anyString(), any())).thenReturn(List.of());

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("business-agent"), "alice", "attendance.query"));

        assertFalse(r.isAllowed());
        assertEquals("no matching policy", r.getReason());
    }

    @Test
    void denyWinsOverAllow() {
        AuthPolicy allow = policy("*", "USER", "alice", "ALLOW", "self", null);
        AuthPolicy deny = policy("salary.query", "USER", "alice", "DENY", null, null);
        when(syncService.selectEffectivePolicies(anyString(), any())).thenReturn(List.of(allow, deny));

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("business-agent"), "alice", "salary.query"));

        assertFalse(r.isAllowed());
        assertTrue(r.getReason().contains("denied"));
    }

    @Test
    void roleMatchesViaIdP() {
        when(idPClient.getUserRoles("alice")).thenReturn(List.of("hr-viewer"));
        when(syncService.selectEffectivePolicies(anyString(), any()))
                .thenReturn(List.of(policy("attendance.query", "ROLE", "hr-viewer", "ALLOW", "department", null)));

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("business-agent"), "alice", "attendance.query"));

        assertTrue(r.isAllowed());
        assertEquals("department", r.getDataScope());
    }

    @Test
    void constraintsTakeMinimumRpm() {
        AuthPolicy p1 = policy("*", "USER", "alice", "ALLOW", "self", "{\"max_calls_per_minute\":60}");
        AuthPolicy p2 = policy("*", "AGENT", "business-agent", "ALLOW", "team", "{\"max_calls_per_minute\":30}");
        when(syncService.selectEffectivePolicies(anyString(), any())).thenReturn(List.of(p1, p2));

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("business-agent"), "alice", "attendance.query"));

        assertTrue(r.isAllowed());
        assertEquals(30, r.getConstraints().get("max_calls_per_minute"));
        // dataScope 取最大值: team > self
        assertEquals("team", r.getDataScope());
    }

    @Test
    void serviceTokenWithoutUserCannotUseUserPolicy() {
        when(syncService.selectEffectivePolicies(anyString(), any()))
                .thenReturn(List.of(policy("*", "USER", "alice", "ALLOW", "self", null)));

        AuthCheckDTO.Response r = evaluationService.evaluate(
                request(List.of("business-agent"), null, "attendance.query"));

        assertFalse(r.isAllowed());
    }

    @Test
    void timeRangeWrapAround() {
        assertTrue(PolicyEvaluationService.inTimeRange("09:00-18:00", java.time.LocalTime.of(10, 0)));
        assertFalse(PolicyEvaluationService.inTimeRange("09:00-18:00", java.time.LocalTime.of(20, 0)));
        assertTrue(PolicyEvaluationService.inTimeRange("22:00-06:00", java.time.LocalTime.of(23, 0)));
        assertTrue(PolicyEvaluationService.inTimeRange("22:00-06:00", java.time.LocalTime.of(3, 0)));
        assertFalse(PolicyEvaluationService.inTimeRange("22:00-06:00", java.time.LocalTime.of(12, 0)));
    }
}
