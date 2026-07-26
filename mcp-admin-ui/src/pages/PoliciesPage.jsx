import React, { useEffect, useState } from 'react'
import {
  Button, Card, Form, Input, InputNumber, message, Modal, Popconfirm, Radio, Select, Space, Table, Tag,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import client from '../api/client'

const STATUS = {
  0: { text: '待审', color: 'orange' },
  1: { text: '生效', color: 'green' },
  2: { text: '过期', color: 'default' },
  3: { text: '撤销', color: 'red' },
}

export default function PoliciesPage() {
  const [data, setData] = useState({ list: [], total: 0 })
  const [query, setQuery] = useState({ page: 1, size: 10 })
  const [servers, setServers] = useState([])
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      setData(await client.get('/auth/policies', { params: query }))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [query.page])
  useEffect(() => {
    client.get('/registry/servers', { params: { size: 100 } }).then((d) => setServers(d.list))
  }, [])

  const approve = async (id, approved) => {
    await client.post(`/auth/policies/${id}/approve`, { approved })
    message.success(approved ? '已批准生效' : '已驳回')
    load()
  }

  const revoke = async (id) => {
    await client.delete(`/auth/policies/${id}`)
    message.success('已撤销')
    load()
  }

  const onCreate = async () => {
    const values = await form.validateFields()
    const constraints = {}
    if (values.maxRpm) constraints.max_calls_per_minute = values.maxRpm
    if (values.timeRange) constraints.time_range = values.timeRange
    await client.post('/auth/policies', {
      policyName: values.policyName,
      serverId: values.serverId,
      toolName: values.toolName || '*',
      granteeType: values.granteeType,
      granteeId: values.granteeId,
      dataScope: values.dataScope,
      effect: values.effect,
      constraints: Object.keys(constraints).length ? JSON.stringify(constraints) : undefined,
    })
    message.success('策略已创建，待审批')
    setModalOpen(false)
    form.resetFields()
    load()
  }

  const columns = [
    { title: '策略名', dataIndex: 'policyName', ellipsis: true },
    { title: 'Server', dataIndex: 'serverId', width: 150 },
    { title: '工具', dataIndex: 'toolName', width: 140 },
    {
      title: '被授权对象', key: 'grantee', width: 200,
      render: (_, p) => (
        <span>
          <Tag color={{ AGENT: 'blue', USER: 'green', ROLE: 'purple', GROUP: 'cyan' }[p.granteeType]}>
            {p.granteeType}
          </Tag>
          {p.granteeId}
        </span>
      ),
    },
    {
      title: '效果', dataIndex: 'effect', width: 80,
      render: (v) => <Tag color={v === 'ALLOW' ? 'green' : 'red'}>{v}</Tag>,
    },
    { title: '数据范围', dataIndex: 'dataScope', width: 100 },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v) => <Tag color={STATUS[v]?.color}>{STATUS[v]?.text || v}</Tag>,
    },
    {
      title: '操作', key: 'op', width: 220,
      render: (_, p) => (
        <Space size="small">
          {p.status === 0 && (
            <>
              <Button size="small" type="primary" onClick={() => approve(p.id, true)}>批准</Button>
              <Button size="small" onClick={() => approve(p.id, false)}>驳回</Button>
            </>
          )}
          {p.status === 1 && (
            <Popconfirm title="确认撤销该策略？" onConfirm={() => revoke(p.id)}>
              <Button size="small" danger>撤销</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <span />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>新建授权策略</Button>
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data.list}
        loading={loading}
        pagination={{
          current: query.page, pageSize: query.size, total: data.total,
          onChange: (p) => setQuery({ ...query, page: p }),
        }}
      />

      <Modal title="新建授权策略" open={modalOpen} onOk={onCreate} onCancel={() => setModalOpen(false)} width={640}>
        <Form form={form} layout="vertical" initialValues={{ granteeType: 'USER', dataScope: 'self', effect: 'ALLOW' }}>
          <Form.Item name="serverId" label="MCP Server" rules={[{ required: true }]}>
            <Select
              showSearch
              options={servers.map((s) => ({ value: s.serverId, label: `${s.name} (${s.serverId})` }))}
            />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="toolName" label="工具名（* = 全部）">
              <Input style={{ width: 200 }} placeholder="*" />
            </Form.Item>
            <Form.Item name="granteeType" label="对象类型" rules={[{ required: true }]}>
              <Radio.Group options={['AGENT', 'USER', 'ROLE', 'GROUP'].map((v) => ({ value: v, label: v }))} />
            </Form.Item>
            <Form.Item name="granteeId" label="对象 ID" rules={[{ required: true }]}>
              <Input style={{ width: 220 }} placeholder="如 alice / business-agent / hr-viewer" />
            </Form.Item>
          </Space>
          <Form.Item name="policyName" label="策略名（可选）">
            <Input />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="effect" label="效果" rules={[{ required: true }]}>
              <Radio.Group options={[{ value: 'ALLOW', label: '允许' }, { value: 'DENY', label: '拒绝' }]} />
            </Form.Item>
            <Form.Item name="dataScope" label="数据权限范围">
              <Select style={{ width: 160 }} options={[
                { value: 'self', label: '本人' }, { value: 'team', label: '团队' },
                { value: 'department', label: '部门' }, { value: 'organization', label: '全组织' },
              ]} />
            </Form.Item>
            <Form.Item name="maxRpm" label="限流（次/分钟，可选）">
              <InputNumber min={1} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="timeRange" label="时间窗口（可选，如 09:00-18:00）">
              <Input style={{ width: 200 }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  )
}
