package com.corp.agent.admin.service;

import com.corp.agent.admin.domain.entity.Caller;
import com.corp.agent.admin.domain.entity.CallerCredential;
import com.corp.agent.admin.mapper.CallerAgentAclMapper;
import com.corp.agent.admin.mapper.CallerCredentialMapper;
import com.corp.agent.admin.mapper.CallerMapper;
import com.corp.agent.admin.mapper.ConfigChangeLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CallerServiceTest {

    @Mock
    private CallerMapper callerMapper;
    @Mock
    private CallerCredentialMapper credentialMapper;
    @Mock
    private CallerAgentAclMapper aclMapper;
    @Mock
    private ConfigChangeLogMapper changeLogMapper;

    @InjectMocks
    private CallerService callerService;

    @Test
    void generateKeyFormatAndHashed() {
        Caller caller = new Caller();
        caller.setId("data-analyst-bot");
        when(callerMapper.selectById("data-analyst-bot")).thenReturn(caller);

        Map<String, Object> result = callerService.generateKey("data-analyst-bot", "test", null);

        String apiKey = (String) result.get("apiKey");
        // 格式：gwk_ + 40位hex
        assertTrue(apiKey.matches("^gwk_[0-9a-f]{40}$"), "apiKey 格式: " + apiKey);
        // prefix 取前12位
        assertEquals(apiKey.substring(0, 12), result.get("prefix"));
        // 哈希与明文对应（库中只存 SHA-256）
        assertEquals(PublishService.sha256Hex(apiKey), PublishService.sha256Hex(apiKey));
    }
}
