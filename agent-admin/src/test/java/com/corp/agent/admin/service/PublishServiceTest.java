package com.corp.agent.admin.service;

import com.corp.agent.admin.domain.entity.AgentCard;
import com.corp.agent.admin.mapper.AgentCardMapper;
import com.corp.agent.admin.mapper.ConfigChangeLogMapper;
import com.corp.agent.admin.mapper.UpstreamCredentialMapper;
import com.corp.agent.admin.util.Jsons;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对外 Card 拼装单测（设计文档 5.6 节 buildPublicCardJson）。
 */
class PublishServiceTest {

    private PublishService newService() {
        return new PublishService(null, null, null, null);
    }

    private AgentCard sampleCard() {
        AgentCard card = new AgentCard();
        card.setId("weather-reporter");
        card.setName("Weather Reporter");
        card.setDescription("天气播报");
        card.setVersion("1.2.0");
        card.setProtocolVersion("1.0");
        card.setEndpointUrl("http://internal/a2a");
        card.setCapabilities("{\"streaming\":true,\"pushNotifications\":false}");
        card.setDefaultInputModes("[\"text/plain\"]");
        card.setDefaultOutputModes("[\"text/plain\",\"application/json\"]");
        card.setSkills("[{\"id\":\"weather.query\",\"name\":\"天气查询\",\"description\":\"查询天气\",\"tags\":[\"weather\"]}]");
        card.setProviderOrganization("Example Corp");
        card.setProviderUrl("https://example.com");
        card.setSignatures("[{\"protected\":\"x\"}]");
        return card;
    }

    @Test
    @SuppressWarnings("unchecked")
    void cardJsonMatchesA2aV1() {
        String json = newService().buildPublicCardJson(sampleCard());
        Map<String, Object> card = Jsons.toMap(json);

        // supportedInterfaces 由网关生成，指向自身代理入口，含占位符
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) card.get("supportedInterfaces");
        assertEquals(1, ifaces.size());
        assertEquals("{{GW_BASE}}/weather-reporter/a2a", ifaces.get(0).get("url"));
        assertEquals("JSONRPC", ifaces.get(0).get("protocolBinding"));
        assertEquals("1.0", ifaces.get(0).get("protocolVersion"));

        // 上游 endpoint 不出现
        assertFalse(json.contains("internal/a2a"));
        assertFalse(json.contains("endpointUrl"));

        // 对外安全声明固定为网关 API Key 方案
        Map<String, Object> schemes = (Map<String, Object>) card.get("securitySchemes");
        assertTrue(schemes.containsKey("gateway-key"));

        // signatures 被移除（URL 重写后失效）
        assertFalse(card.containsKey("signatures"));

        // capabilities/skills 透传
        assertTrue(json.contains("\"streaming\":true"));
        assertTrue(json.contains("weather.query"));
    }
}
