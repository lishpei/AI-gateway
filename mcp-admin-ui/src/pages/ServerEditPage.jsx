import React, { useEffect, useState } from 'react'
import {
  Button, Card, Form, Input, InputNumber, message, Modal, Radio, Select, Space, Switch, Table, Typography,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import client from '../api/client'

const emptyTool = () => ({
  toolName: '', description: '', rateLimitRpm: 60, validationLevel: 'basic',
  inputSchema: '', subjectBindings: '', requiredScope: '', isActive: 1,
})

export default function ServerEditPage() {
  const { serverId } = useParams()
  const isNew = !serverId
  const [form] = Form.useForm()
  const [tools, setTools] = useState([])
  const [toolModal, setToolModal] = useState({ open: false, index: -1 })
  const [toolForm] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    if (!isNew) {
      client.get(`/registry/servers/${serverId}`).then((d) => {
        const s = d.server
        form.setFieldsValue(s)
        setTools(d.tools || [])
      })
    }
  }, [serverId])

  const saveTool = async () => {
    const values = await toolForm.validateFields()
    const next = [...tools]
    if (toolModal.index >= 0) next[toolModal.index] = values
    else next.push(values)
    setTools(next)
    setToolModal({ open: false, index: -1 })
  }

  const onSubmit = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = { ...values, tools }
      if (isNew) {
        await client.post('/registry/servers', payload)
        message.success('创建成功')
      } else {
        await client.put(`/registry/servers/${serverId}`, payload)
        message.success('已保存')
      }
      navigate('/registry')
    } finally {
      setSaving(false)
    }
  }

  const toolColumns = [
    { title: '工具名', dataIndex: 'toolName' },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: '限流(rpm)', dataIndex: 'rateLimitRpm', width: 100 },
    {
      title: '参数绑定', dataIndex: 'subjectBindings', ellipsis: true,
      render: (v) => v || '-',
    },
    {
      title: '操作', key: 'op', width: 140,
      render: (_, t, i) => (
        <Space size="small">
          <Button size="small" onClick={() => { toolForm.setFieldsValue(t); setToolModal({ open: true, index: i }) }}>编辑</Button>
          <Button size="small" danger type="text" onClick={() => setTools(tools.filter((_, x) => x !== i))}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Typography.Title level={3}>{isNew ? '注册 MCP Server' : `编辑 ${serverId}`}</Typography.Title>
      <Form form={form} layout="vertical" initialValues={{
        protocolType: 'streamable-http', authMode: 'user-delegation',
        healthEndpoint: '/health', dataClassification: 'internal',
      }}>
        <Card title="基本信息" style={{ marginBottom: 16 }}>
          <Form.Item name="serverId" label="Server ID" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9-]{1,62}$/, message: '小写字母/数字/连字符' }]}>
            <Input placeholder="如 attendance-mcp" disabled={!isNew} style={{ maxWidth: 400 }} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input style={{ maxWidth: 400 }} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="category" label="分类">
              <Select style={{ width: 160 }} allowClear options={[
                { value: 'hr', label: '人力资源' }, { value: 'finance', label: '财务' },
                { value: 'office', label: '办公协同' }, { value: 'dev', label: '开发工具' },
              ]} />
            </Form.Item>
            <Form.Item name="version" label="版本"><Input style={{ width: 120 }} placeholder="1.0.0" /></Form.Item>
            <Form.Item name="ownerTeam" label="所属团队"><Input style={{ width: 160 }} /></Form.Item>
            <Form.Item name="ownerEmail" label="负责人邮箱"><Input style={{ width: 220 }} /></Form.Item>
          </Space>
        </Card>

        <Card title="接入信息" style={{ marginBottom: 16 }}>
          <Form.Item name="baseUrl" label="服务地址" rules={[{ required: true, pattern: /^https?:\/\//, message: '须为 http/https URL' }]}>
            <Input placeholder="http://localhost:8090" style={{ maxWidth: 500 }} />
          </Form.Item>
          <Form.Item name="resourceUri" label="资源 URI（RFC 8707 audience）" rules={[{ required: true }]}
            extra="Token Exchange 时绑定到此 URI，MCP Server 必须校验 aud 等于该值">
            <Input placeholder="http://localhost:8090/mcp" style={{ maxWidth: 500 }} />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="protocolType" label="协议类型">
              <Radio.Group options={[
                { value: 'streamable-http', label: 'Streamable HTTP' },
                { value: 'http-sse', label: '旧版 HTTP+SSE' },
              ]} />
            </Form.Item>
            <Form.Item name="authMode" label="认证模式">
              <Radio.Group options={[
                { value: 'user-delegation', label: '用户委托' },
                { value: 'service', label: '服务级' },
                { value: 'none', label: '无认证' },
              ]} />
            </Form.Item>
            <Form.Item name="healthEndpoint" label="健康检查端点"><Input style={{ width: 160 }} /></Form.Item>
            <Form.Item name="dataClassification" label="数据分类">
              <Select style={{ width: 160 }} options={[
                { value: 'public', label: '公开' }, { value: 'internal', label: '内部' },
                { value: 'confidential', label: '机密' }, { value: 'restricted', label: '受限' },
              ]} />
            </Form.Item>
          </Space>
        </Card>

        <Card
          title="工具定义"
          style={{ marginBottom: 16 }}
          extra={<Button icon={<PlusOutlined />} onClick={() => { toolForm.resetFields(); toolForm.setFieldsValue(emptyTool()); setToolModal({ open: true, index: -1 }) }}>添加工具</Button>}
        >
          <Table rowKey="toolName" columns={toolColumns} dataSource={tools} pagination={false} size="small" />
        </Card>

        <Space>
          <Button type="primary" size="large" loading={saving} onClick={onSubmit}>保存</Button>
          <Button size="large" onClick={() => navigate('/registry')}>取消</Button>
        </Space>
      </Form>

      <Modal
        title={toolModal.index >= 0 ? '编辑工具' : '添加工具'}
        open={toolModal.open}
        onOk={saveTool}
        onCancel={() => setToolModal({ open: false, index: -1 })}
        width={720}
        destroyOnClose={false}
      >
        <Form form={toolForm} layout="vertical">
          <Space size="large" wrap>
            <Form.Item name="toolName" label="工具名" rules={[{ required: true, pattern: /^[a-zA-Z][a-zA-Z0-9_.-]{0,127}$/ }]}>
              <Input style={{ width: 220 }} placeholder="attendance.query" disabled={toolModal.index >= 0} />
            </Form.Item>
            <Form.Item name="rateLimitRpm" label="限流(rpm)" rules={[{ required: true }]}>
              <InputNumber min={1} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="validationLevel" label="输入校验">
              <Select style={{ width: 140 }} options={[
                { value: 'none', label: '不校验' }, { value: 'basic', label: '基础校验' },
                { value: 'schema', label: '完整Schema' },
              ]} />
            </Form.Item>
            <Form.Item name="isActive" label="启用" valuePropName="checked" getValueFromEvent={(v) => (v ? 1 : 0)} getValueProps={(v) => ({ checked: v === 1 })}>
              <Switch defaultChecked />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述" rules={[{ required: true }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="inputSchema" label="输入 Schema (JSON)">
            <Input.TextArea rows={4} placeholder='{"type":"object","properties":{...},"required":[...]}' />
          </Form.Item>
          <Form.Item name="subjectBindings" label="参数绑定 (JSON)" extra='如 [{"param":"employee_id","claim":"email","required":true}] — 强制参数等于委托用户身份'>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="requiredScope" label="所需 Scope（留空默认 mcp:{serverId}:{toolName}）">
            <Input />
          </Form.Item>
          <Form.Item name="outputMasking" label="输出脱敏规则 (JSON)">
            <Input.TextArea rows={2} placeholder='[{"pattern":"\\d{11}","replacement":"***"}]' />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
