import React, { useEffect, useState } from 'react'
import {
  Alert, Button, Drawer, Form, Input, message, Modal, Popconfirm, Space, Table, Transfer, Typography,
} from 'antd'
import { KeyOutlined, PlusOutlined, SafetyOutlined } from '@ant-design/icons'
import client from '../api/client'
import PageHeader from '../components/PageHeader'
import Panel from '../components/Panel'
import SoftBadge from '../components/SoftBadge'
import { colors } from '../theme'

export default function CallersPage() {
  const [data, setData] = useState({ list: [], total: 0 })
  const [query, setQuery] = useState({ page: 1, size: 10 })
  const [createOpen, setCreateOpen] = useState(false)
  const [createForm] = Form.useForm()
  const [keyDrawer, setKeyDrawer] = useState({ open: false, caller: null, keys: [] })
  const [aclDrawer, setAclDrawer] = useState({ open: false, caller: null, targetKeys: [], agents: [] })
  const [genKeyModal, setGenKeyModal] = useState({ open: false, result: null })
  const [genKeyForm] = Form.useForm()

  const load = async () => {
    setData(await client.get('/callers', { params: query }))
  }

  useEffect(() => { load() }, [query.page])

  const onCreate = async () => {
    const values = await createForm.validateFields()
    await client.post('/callers', values)
    message.success('创建成功')
    setCreateOpen(false)
    createForm.resetFields()
    load()
  }

  const toggleStatus = async (c) => {
    await client.put(`/callers/${c.id}`, { ...c, status: c.status === 1 ? 0 : 1 })
    message.success(c.status === 1 ? '已禁用（其全部 Key 失效）' : '已启用')
    load()
  }

  const remove = async (c) => {
    await client.delete(`/callers/${c.id}`)
    message.success('已删除（级联删除 Key 与 ACL）')
    load()
  }

  const openKeys = async (c) => {
    const keys = await client.get(`/callers/${c.id}/credentials`)
    setKeyDrawer({ open: true, caller: c, keys })
  }

  const refreshKeys = async () => {
    const keys = await client.get(`/callers/${keyDrawer.caller.id}/credentials`)
    setKeyDrawer({ ...keyDrawer, keys })
  }

  const onGenerateKey = async () => {
    const values = await genKeyForm.validateFields()
    const r = await client.post(`/callers/${keyDrawer.caller.id}/credentials`, { keyName: values.keyName })
    setGenKeyModal({ open: true, result: r })
    genKeyForm.resetFields()
    refreshKeys()
  }

  const revokeKey = async (credId) => {
    await client.delete(`/callers/${keyDrawer.caller.id}/credentials/${credId}`)
    message.success('已吊销')
    refreshKeys()
  }

  const openAcl = async (c) => {
    const [agentsResp, aclResp] = await Promise.all([
      client.get('/agents', { params: { size: 200 } }),
      client.get(`/callers/${c.id}/acl`),
    ])
    setAclDrawer({
      open: true,
      caller: c,
      targetKeys: aclResp || [],
      agents: agentsResp.list.map((a) => ({ key: a.id, title: `${a.name} (${a.id})` })),
    })
  }

  const saveAcl = async () => {
    await client.put(`/callers/${aclDrawer.caller.id}/acl`, { agentIds: aclDrawer.targetKeys })
    message.success('ACL 已保存（全量替换语义）')
    setAclDrawer({ open: false, caller: null, targetKeys: [], agents: [] })
  }

  const columns = [
    { title: '调用方 ID', dataIndex: 'id', width: 200, render: (v) => <span className="id-cell">{v}</span> },
    {
      title: '名称', key: 'name',
      render: (_, c) => <div className="name-cell"><b>{c.name}</b><span>{c.description || '-'}</span></div>,
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v) => (v === 1 ? <SoftBadge type="green" dot>启用</SoftBadge> : <SoftBadge type="red" dot>禁用</SoftBadge>),
    },
    {
      title: '操作', key: 'op', width: 340,
      render: (_, c) => (
        <Space size="small" wrap>
          <Button size="small" icon={<KeyOutlined />} onClick={() => openKeys(c)}>凭证</Button>
          <Button size="small" icon={<SafetyOutlined />} onClick={() => openAcl(c)}>ACL 授权</Button>
          <Button size="small" onClick={() => toggleStatus(c)}>{c.status === 1 ? '禁用' : '启用'}</Button>
          <Popconfirm title="确认删除（级联删除全部 Key 与 ACL）？" onConfirm={() => remove(c)}>
            <Button size="small" danger type="text">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const keyColumns = [
    { title: 'Key 名', dataIndex: 'keyName', width: 130 },
    { title: '前缀', dataIndex: 'apiKeyPrefix', width: 140, render: (v) => <Typography.Text code>{v}…</Typography.Text> },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v) => (v === 1 ? <SoftBadge type="green" dot>启用</SoftBadge> : <SoftBadge type="red" dot>已吊销</SoftBadge>),
    },
    { title: '过期时间', dataIndex: 'expiresAt', width: 160, render: (v) => v || '永不过期' },
    { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (v) => <span style={{ color: colors.textDim, fontSize: 12 }}>{v}</span> },
    {
      title: '操作', key: 'op', width: 90,
      render: (_, k) => (
        k.status === 1 && (
          <Popconfirm title="吊销后该 Key 立即失效" onConfirm={() => revokeKey(k.id)}>
            <Button size="small" danger>吊销</Button>
          </Popconfirm>
        )
      ),
    },
  ]

  return (
    <div>
      <PageHeader title="调用方管理" desc="Client Agent 身份、API Key 凭证与 Agent 访问白名单（ACL）" />
      <Panel
        title="调用方列表"
        subtitle={`共 ${data.total} 个`}
        bodyPadding={false}
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建调用方</Button>}
      >
        <Table
          rowKey="id"
          columns={columns}
          dataSource={data.list}
          pagination={{
            current: query.page, pageSize: query.size, total: data.total,
            onChange: (p) => setQuery({ ...query, page: p }),
          }}
        />
      </Panel>

      <Modal title="新建调用方" open={createOpen} onOk={onCreate} onCancel={() => setCreateOpen(false)}>
        <Form form={createForm} layout="vertical">
          <Form.Item name="id" label="调用方 ID" rules={[{ required: true, pattern: /^[a-zA-Z][a-zA-Z0-9_-]{1,62}$/ }]}>
            <Input placeholder="data-analyst-bot" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`凭证管理 — ${keyDrawer.caller?.name || ''}`}
        open={keyDrawer.open}
        onClose={() => setKeyDrawer({ open: false, caller: null, keys: [] })}
        width={780}
      >
        <Space style={{ marginBottom: 16 }}>
          <Form form={genKeyForm} layout="inline">
            <Form.Item name="keyName" label="Key 备注名" rules={[{ required: true }]} initialValue="default">
              <Input style={{ width: 200 }} />
            </Form.Item>
            <Button type="primary" onClick={onGenerateKey}>生成新 Key</Button>
          </Form>
        </Space>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="API Key 明文仅在生成时展示一次，请立即复制保存。库中只存 SHA-256 哈希。" />
        <Table rowKey="id" columns={keyColumns} dataSource={keyDrawer.keys} pagination={false} size="small" />
      </Drawer>

      <Modal
        title="API Key 已生成（仅本次展示）"
        open={genKeyModal.open}
        footer={<Button type="primary" onClick={() => setGenKeyModal({ open: false, result: null })}>我已保存</Button>}
        onCancel={() => setGenKeyModal({ open: false, result: null })}
        closable={false}
      >
        <Typography.Paragraph>请立即复制并妥善保存，关闭后无法再次查看：</Typography.Paragraph>
        <Typography.Paragraph copyable code style={{ wordBreak: 'break-all', fontSize: 15 }}>
          {genKeyModal.result?.apiKey}
        </Typography.Paragraph>
        <Typography.Paragraph type="secondary">
          前缀：{genKeyModal.result?.prefix}…（用于界面与日志识别）
        </Typography.Paragraph>
      </Modal>

      <Drawer
        title={`ACL 授权 — ${aclDrawer.caller?.name || ''}`}
        open={aclDrawer.open}
        onClose={() => setAclDrawer({ open: false, caller: null, targetKeys: [], agents: [] })}
        width={640}
        extra={<Button type="primary" onClick={saveAcl}>保存</Button>}
      >
        <Typography.Paragraph type="secondary">可访问的 Agent 白名单（全量替换语义）</Typography.Paragraph>
        <Transfer
          dataSource={aclDrawer.agents}
          targetKeys={aclDrawer.targetKeys}
          onChange={(keys) => setAclDrawer({ ...aclDrawer, targetKeys: keys })}
          render={(item) => item.title}
          titles={['全部 Agent', '已授权']}
          listStyle={{ width: 270, height: 420 }}
        />
      </Drawer>
    </div>
  )
}
