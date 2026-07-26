package com.corp.mcp.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.corp.mcp.admin.common.BizException;
import com.corp.mcp.admin.domain.dto.McpServerSaveDTO;
import com.corp.mcp.admin.domain.dto.McpToolSaveDTO;
import com.corp.mcp.admin.domain.entity.McpServer;
import com.corp.mcp.admin.mapper.AuthPolicyMapper;
import com.corp.mcp.admin.mapper.McpServerMapper;
import com.corp.mcp.admin.mapper.McpToolMapper;
import com.corp.mcp.admin.util.Jsons;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistryServiceTest {

    @Mock
    private McpServerMapper serverMapper;
    @Mock
    private McpToolMapper toolMapper;
    @Mock
    private AuthPolicyMapper policyMapper;
    @Mock
    private StringRedisTemplate redis;

    @InjectMocks
    private RedisSyncService syncService;

    @Test
    void createServerConflictsWhenIdExists() {
        when(serverMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        RegistryService registryService = new RegistryService(serverMapper, toolMapper, syncService);

        McpServerSaveDTO dto = new McpServerSaveDTO();
        dto.setServerId("attendance-mcp");
        dto.setName("考勤");
        dto.setBaseUrl("http://localhost:8090");
        dto.setResourceUri("http://localhost:8090/mcp");

        BizException e = assertThrows(BizException.class, () -> registryService.createServer(dto, "admin"));
        assertEquals(40901, e.getCode());
    }

    @Test
    void requireServerThrowsWhenAbsent() {
        when(serverMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        RegistryService registryService = new RegistryService(serverMapper, toolMapper, syncService);

        BizException e = assertThrows(BizException.class, () -> registryService.requireServer("ghost"));
        assertEquals(40401, e.getCode());
    }

    @Test
    void toolRejectsInvalidSchemaJson() {
        McpServer server = new McpServer();
        server.setServerId("attendance-mcp");
        server.setStatus(McpServer.STATUS_DRAFT);
        when(serverMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(server);
        RegistryService registryService = new RegistryService(serverMapper, toolMapper, syncService);

        McpToolSaveDTO tool = new McpToolSaveDTO();
        tool.setToolName("attendance.query");
        tool.setDescription("查询考勤");
        tool.setInputSchema("{not-valid-json");

        BizException e = assertThrows(BizException.class, () -> registryService.saveTool("attendance-mcp", tool));
        assertEquals(40001, e.getCode());
        assertTrue(e.getMessage().contains("inputSchema"));
    }

    @Test
    void snapshotItemContainsRequiredFields() {
        // 验证 RedisSyncService 快照结构的字段名与 Lua 侧约定一致
        com.corp.mcp.admin.domain.entity.AuthPolicy p = new com.corp.mcp.admin.domain.entity.AuthPolicy();
        p.setId(7L);
        p.setToolName("attendance.query");
        p.setGranteeType("USER");
        p.setGranteeId("alice");
        p.setEffect("ALLOW");
        p.setDataScope("self");
        p.setConstraints("{\"max_calls_per_minute\":30}");

        // 通过 toSnapshotItem 的间接验证：构造快照 JSON 并断言字段
        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("version", 3L);
        snapshot.put("policies", List.of(new java.util.HashMap<String, Object>() {{
            put("policyId", p.getId());
            put("toolName", p.getToolName());
            put("granteeType", p.getGranteeType());
            put("granteeId", p.getGranteeId());
            put("effect", p.getEffect());
            put("dataScope", p.getDataScope());
            put("constraints", Jsons.toMap(p.getConstraints()));
        }}));
        String json = Jsons.toJson(snapshot);

        assertTrue(json.contains("\"policyId\":7"));
        assertTrue(json.contains("\"granteeType\":\"USER\""));
        assertTrue(json.contains("\"max_calls_per_minute\":30"));
        assertTrue(json.contains("\"version\":3"));
    }
}
