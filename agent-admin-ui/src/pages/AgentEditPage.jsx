import React, { useEffect, useState } from 'react'
import {
  Alert, Button, Card, Form, Input, message, Popconfirm, Radio, Select, Space, Switch, Table, Tabs, Typography,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import client from '../api/client'
import PageHeader from '../components/PageHeader'

const AUTH_TYPES = [
  { value: 'NONE', label: '无认证 (NONE)' },
  { value: 'API_KEY', label: 'API Key' },
  { value: 'HTTP_BEARER', label: 'HTTP Bearer' },
  { value: 'HTTP_BASIC', label: 'HTTP Basic' },
  { value: 'OAUTH2_CLIENT_CREDENTIALS', label: 'OAuth2 Client Credentials' },
  { value: 'MTLS', label: 'mTLS（节点证书）' },
]

const MODE_SUGGESTIONS = ['text/plain', 'application/json', 'text/html', 'image/png', 'application/octet-stream']

const emptySkill = () => ({ id: '', name: '', description: '', tags: [], examples: [], inputModes: [], outputModes: [] })

export default function AgentEditPage() {
  const { id } = useParams()
  const isNew = !id
  const [form] = Form.useForm()
  const [credForm] = Form.useForm()
  const [skills, setSkills] = useState([])
  const [skillMode, setSkillMode] = useState('table')
  const [skillJson, setSkillJson] = useState('[]')
  const [security, setSecurity] = useState({ schemes: '', requirements: '' })
  const [credInfo, setCredInfo] = useState(null)
  const [saving, setSaving] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    if (!isNew) {
      client.get(`/agents/${id}`).then((a) => {
        const caps = safeParse(a.capabilities, {})
        form.setFieldsValue({
          id: a.id, name: a.name, description: a.description, version: a.version,
          protocolVersion: a.protocolVersion || '1.0',
          providerOrganization: a.providerOrganization, providerUrl: a.providerUrl,
          documentationUrl: a.documentationUrl, iconUrl: a.iconUrl,
          endpointUrl: a.endpointUrl,
          streaming: !!caps.streaming,
          pushNotifications: !!caps.pushNotifications,
          extendedAgentCard: !!caps.extendedAgentCard,
          defaultInputModes: safeParse(a.defaultInputModes, []),
          defaultOutputModes: safeParse(a.defaultOutputModes, []),
          status: a.status === 1,
        })
        setSkills(safeParse(a.skills, []))
        setSkillJson(JSON.stringify(safeParse(a.skills, []), null, 2))
        setSecurity({
          schemes: a.securitySchemes ? JSON.stringify(safeParse(a.securitySchemes, {}), null, 2) : '',
          requirements: a.securityRequirements ? JSON.stringify(safeParse(a.securityRequirements, []), null, 2) : '',
        })
      })
      client.get(`/agents/${id}/upstream-credential`).then((c) => {
        setCredInfo(c)
        credForm.setFieldsValue({ authType: c.authType || 'NONE' })
      })
    }
  }, [id])

  const safeParse = (s, dft) => {
    try { return typeof s === 'string' ? JSON.parse(s) : (s ?? dft) } catch { return dft }
  }

  const syncSkillsToJson = (list) => setSkillJson(JSON.stringify(list, null, 2))

  const onSkillModeChange = (mode) => {
    if (mode === 'json') {
      syncSkillsToJson(skills)
    } else {
      try {
        setSkills(JSON.parse(skillJson || '[]'))
      } catch (e) {
        message.warning('JSON 解析失败，已保留表格数据')
      }
    }
    setSkillMode(mode)
  }

  const updateSkill = (index, field, value) => {
    const next = skills.map((s, i) => (i === index ? { ...s, [field]: value } : s))
    setSkills(next)
    if (skillMode === 'json') syncSkillsToJson(next)
  }

  const onSubmit = async () => {
    const values = await form.validateFields()
    // skills 以当前模式为准
    let finalSkills = skills
    if (skillMode === 'json') {
      try {
        finalSkills = JSON.parse(skillJson || '[]')
      } catch {
        return message.error('skills JSON 格式错误')
      }
    }
    // 校验 skill 必填
    for (const s of finalSkills) {
      if (!s.id || !s.name || !s.description || !Array.isArray(s.tags)) {
        return message.error('每个 skill 必填 id/name/description/tags')
      }
    }
    // security JSON
    let schemes, requirements
    try {
      schemes = security.schemes.trim() ? JSON.parse(security.schemes) : undefined
      requirements = security.requirements.trim() ? JSON.parse(security.requirements) : undefined
    } catch {
      return message.error('安全声明 JSON 格式错误')
    }

    setSaving(true)
    try {
      const payload = {
        id: values.id,
        name: values.name,
        description: values.description,
        version: values.version,
        protocolVersion: values.protocolVersion,
        providerOrganization: values.providerOrganization,
        providerUrl: values.providerUrl,
        documentationUrl: values.documentationUrl,
        iconUrl: values.iconUrl,
        endpointUrl: values.endpointUrl,
        capabilities: {
          streaming: !!values.streaming,
          pushNotifications: !!values.pushNotifications,
          extendedAgentCard: !!values.extendedAgentCard,
        },
        defaultInputModes: values.defaultInputModes || [],
        defaultOutputModes: values.defaultOutputModes || [],
        skills: finalSkills,
        securitySchemes: schemes,
        securityRequirements: requirements,
        status: values.status ? 1 : 0,
      }
      let agentId = id
      if (isNew) {
        await client.post('/agents', payload)
        agentId = values.id
        message.success('创建成功')
      } else {
        await client.put(`/agents/${id}`, payload)
        message.success('已保存')
      }

      // 上游凭证（若选择了类型）
      const cred = await credForm.validateFields().catch(() => null)
      if (cred && cred.authType && cred.authType !== 'NONE') {
        const config = buildCredConfig(cred)
        await client.put(`/agents/${agentId}/upstream-credential?merge=true`, {
          authType: cred.authType,
          config,
        })
        message.success('上游凭证已保存（秘密字段留空保持原值）')
      } else if (cred && cred.authType === 'NONE' && credInfo?.authType && credInfo.authType !== 'NONE') {
        await client.delete(`/agents/${agentId}/upstream-credential`)
      }
      navigate('/agents')
    } finally {
      setSaving(false)
    }
  }

  const buildCredConfig = (cred) => {
    switch (cred.authType) {
      case 'API_KEY':
        return { location: cred.location, name: cred.name, value: cred.value || '' }
      case 'HTTP_BEARER':
        return { token: cred.token || '' }
      case 'HTTP_BASIC':
        return { username: cred.username, password: cred.password || '' }
      case 'OAUTH2_CLIENT_CREDENTIALS':
        return {
          tokenUrl: cred.tokenUrl, clientId: cred.clientId,
          clientSecret: cred.clientSecret || '',
          scopes: cred.scopes ? cred.scopes.split(/\s+/).filter(Boolean) : undefined,
        }
      default:
        return {}
    }
  }

  const authType = Form.useWatch('authType', credForm)

  const skillColumns = [
    {
      title: 'id *', dataIndex: 'id', width: 180,
      render: (v, _, i) => <Input size="small" value={v} onChange={(e) => updateSkill(i, 'id', e.target.value)} />,
    },
    {
      title: 'name *', dataIndex: 'name', width: 160,
      render: (v, _, i) => <Input size="small" value={v} onChange={(e) => updateSkill(i, 'name', e.target.value)} />,
    },
    {
      title: 'description *', dataIndex: 'description',
      render: (v, _, i) => <Input size="small" value={v} onChange={(e) => updateSkill(i, 'description', e.target.value)} />,
    },
    {
      title: 'tags *（逗号分隔）', dataIndex: 'tags', width: 200,
      render: (v, _, i) => (
        <Input size="small" value={(v || []).join(',')}
               onChange={(e) => updateSkill(i, 'tags', e.target.value.split(',').map((x) => x.trim()).filter(Boolean))} />
      ),
    },
    {
      title: '操作', key: 'op', width: 70,
      render: (_, __, i) => (
        <Button size="small" danger type="text" onClick={() => {
          const next = skills.filter((_, x) => x !== i)
          setSkills(next)
          syncSkillsToJson(next)
        }}>删除</Button>
      ),
    },
  ]

  return (
    <div>
      <PageHeader title={isNew ? '注册 Agent' : `编辑 ${id}`} desc="A2A Agent Card 全量定义，保存后需「发布」方可生效" />
      <Form form={form} layout="vertical" initialValues={{ protocolVersion: '1.0', streaming: true, status: true }}>
        <Card title="基本信息" style={{ marginBottom: 16 }}>
          <Space size="large" wrap>
            <Form.Item name="id" label="Agent ID" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9-]{1,62}$/, message: '小写字母/数字/连字符' }]}>
              <Input placeholder="weather-reporter" disabled={!isNew} style={{ width: 240 }} />
            </Form.Item>
            <Form.Item name="name" label="名称" rules={[{ required: true }]}>
              <Input style={{ width: 240 }} />
            </Form.Item>
            <Form.Item name="version" label="版本" rules={[{ required: true }]}>
              <Input placeholder="1.0.0" style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="protocolVersion" label="A2A 协议版本" rules={[{ required: true }]}>
              <Select style={{ width: 110 }} options={[{ value: '1.0', label: '1.0' }, { value: '0.3', label: '0.3' }]} />
            </Form.Item>
            <Form.Item name="status" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="providerOrganization" label="提供方组织"><Input style={{ width: 200 }} /></Form.Item>
            <Form.Item name="providerUrl" label="提供方网址"><Input style={{ width: 260 }} /></Form.Item>
            <Form.Item name="documentationUrl" label="文档地址"><Input style={{ width: 260 }} /></Form.Item>
            <Form.Item name="iconUrl" label="图标地址"><Input style={{ width: 260 }} /></Form.Item>
          </Space>
        </Card>

        <Card title="接入配置" style={{ marginBottom: 16 }}>
          <Form.Item name="endpointUrl" label="上游真实 A2A 端点（内部，不出现在对外 Card 中）"
            rules={[{ required: true, pattern: /^https?:\/\//, message: '须为 http/https URL' }]}>
            <Input placeholder="http://host:8091/a2a" style={{ maxWidth: 560 }} />
          </Form.Item>
          <Card type="inner" title="上游认证（凭证代换；秘密字段留空 = 保持原值）" size="small">
            {credInfo?.authType && credInfo.authType !== 'NONE' && (
              <Alert style={{ marginBottom: 12 }} type="info" showIcon
                message={`当前已配置：${credInfo.authType}（更新于 ${credInfo.updatedAt || '-'}）`} />
            )}
            <Form form={credForm} layout="vertical" initialValues={{ authType: 'NONE' }}>
              <Form.Item name="authType" label="认证类型">
                <Select style={{ width: 280 }} options={AUTH_TYPES} />
              </Form.Item>
              {authType === 'API_KEY' && (
                <Space size="large" wrap>
                  <Form.Item name="location" label="位置" rules={[{ required: true }]} initialValue="header">
                    <Radio.Group options={[{ value: 'header', label: 'Header' }, { value: 'query', label: 'Query' }]} />
                  </Form.Item>
                  <Form.Item name="name" label="参数名" rules={[{ required: true }]} initialValue="X-Api-Key">
                    <Input style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item name="value" label="Key 值（秘密）">
                    <Input.Password style={{ width: 260 }} placeholder="留空保持原值" />
                  </Form.Item>
                </Space>
              )}
              {authType === 'HTTP_BEARER' && (
                <Form.Item name="token" label="Token（秘密）">
                  <Input.Password style={{ width: 360 }} placeholder="留空保持原值" />
                </Form.Item>
              )}
              {authType === 'HTTP_BASIC' && (
                <Space size="large" wrap>
                  <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
                    <Input style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item name="password" label="密码（秘密）">
                    <Input.Password style={{ width: 240 }} placeholder="留空保持原值" />
                  </Form.Item>
                </Space>
              )}
              {authType === 'OAUTH2_CLIENT_CREDENTIALS' && (
                <>
                  <Form.Item name="tokenUrl" label="Token 端点" rules={[{ required: true }]}>
                    <Input style={{ width: 420 }} placeholder="http://idp/oauth/token" />
                  </Form.Item>
                  <Space size="large" wrap>
                    <Form.Item name="clientId" label="Client ID" rules={[{ required: true }]}>
                      <Input style={{ width: 220 }} />
                    </Form.Item>
                    <Form.Item name="clientSecret" label="Client Secret（秘密）">
                      <Input.Password style={{ width: 260 }} placeholder="留空保持原值" />
                    </Form.Item>
                    <Form.Item name="scopes" label="Scopes（空格分隔）">
                      <Input style={{ width: 220 }} />
                    </Form.Item>
                  </Space>
                </>
              )}
              {authType === 'MTLS' && (
                <Alert type="info" message="mTLS 由网关节点在 TLS 层配置客户端证书，无需在此填写参数" />
              )}
            </Form>
          </Card>
        </Card>

        <Card title="能力声明" style={{ marginBottom: 16 }}>
          <Space size="large">
            <Form.Item name="streaming" label="流式 (SSE)" valuePropName="checked"><Switch /></Form.Item>
            <Form.Item name="pushNotifications" label="推送通知" valuePropName="checked"><Switch /></Form.Item>
            <Form.Item name="extendedAgentCard" label="扩展 Card" valuePropName="checked"><Switch /></Form.Item>
          </Space>
        </Card>

        <Card title="交互模式" style={{ marginBottom: 16 }}>
          <Space size="large" wrap>
            <Form.Item name="defaultInputModes" label="默认输入模式" rules={[{ required: true }]}>
              <Select mode="tags" style={{ width: 360 }} placeholder="选择或输入媒体类型"
                options={MODE_SUGGESTIONS.map((m) => ({ value: m, label: m }))} />
            </Form.Item>
            <Form.Item name="defaultOutputModes" label="默认输出模式" rules={[{ required: true }]}>
              <Select mode="tags" style={{ width: 360 }} placeholder="选择或输入媒体类型"
                options={MODE_SUGGESTIONS.map((m) => ({ value: m, label: m }))} />
            </Form.Item>
          </Space>
        </Card>

        <Card
          title="技能 (skills)"
          style={{ marginBottom: 16 }}
          extra={
            <Space>
              <Radio.Group size="small" value={skillMode} onChange={(e) => onSkillModeChange(e.target.value)}
                options={[{ value: 'table', label: '表格' }, { value: 'json', label: 'JSON' }]} optionType="button" />
              {skillMode === 'table' && (
                <Button size="small" icon={<PlusOutlined />}
                  onClick={() => setSkills([...skills, emptySkill()])}>添加</Button>
              )}
            </Space>
          }
        >
          {skillMode === 'table' ? (
            <Table rowKey={(_, i) => i} columns={skillColumns} dataSource={skills} pagination={false} size="small" />
          ) : (
            <Input.TextArea rows={12} value={skillJson} onChange={(e) => setSkillJson(e.target.value)}
              style={{ fontFamily: 'monospace' }} />
          )}
        </Card>

        <Card title="安全声明（可选，对外 Card 将固定注入网关 API Key 方案替代）" style={{ marginBottom: 16 }}>
          <Tabs
            size="small"
            items={[
              {
                key: 'schemes',
                label: 'securitySchemes',
                children: (
                  <Input.TextArea rows={5} value={security.schemes}
                    onChange={(e) => setSecurity({ ...security, schemes: e.target.value })}
                    placeholder='{"my-oauth": {"oauth2SecurityScheme": {"flows": {...}}}}'
                    style={{ fontFamily: 'monospace' }} />
                ),
              },
              {
                key: 'requirements',
                label: 'securityRequirements',
                children: (
                  <Input.TextArea rows={5} value={security.requirements}
                    onChange={(e) => setSecurity({ ...security, requirements: e.target.value })}
                    placeholder='[{"schemes": {"my-oauth": {"list": ["openid"]}}}]'
                    style={{ fontFamily: 'monospace' }} />
                ),
              },
            ]}
          />
        </Card>

        <Space>
          <Button type="primary" size="large" loading={saving} onClick={onSubmit}>保存</Button>
          <Button size="large" onClick={() => navigate('/agents')}>取消</Button>
        </Space>
      </Form>
    </div>
  )
}
