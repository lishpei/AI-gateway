import React, { useEffect, useState } from 'react'
import { Button, Drawer, Input, message, Popconfirm, Space, Table, Typography } from 'antd'
import { PlusOutlined, SearchOutlined, EyeOutlined, RobotOutlined, CheckCircleOutlined, ThunderboltOutlined, SyncOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import client from '../api/client'
import PageHeader from '../components/PageHeader'
import StatCard from '../components/StatCard'
import Panel from '../components/Panel'
import SoftBadge from '../components/SoftBadge'
import { colors } from '../theme'

export default function AgentsPage() {
  const [data, setData] = useState({ list: [], total: 0 })
  const [query, setQuery] = useState({ page: 1, size: 10, keyword: '' })
  const [loading, setLoading] = useState(false)
  const [preview, setPreview] = useState({ open: false, id: null, json: '' })
  const navigate = useNavigate()

  const load = async () => {
    setLoading(true)
    try {
      const params = { ...query }
      if (!params.keyword) delete params.keyword
      setData(await client.get('/agents', { params }))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [query.page])

  const publish = async (id) => {
    const r = await client.post(`/agents/${id}/publish`)
    message.success(`发布成功，版本水位 seq=${r.publishedSeq}，节点 5s 内生效`)
    load()
  }

  const unpublish = async (id) => {
    await client.post(`/agents/${id}/unpublish`)
    message.success('已下线，节点将移除运行时配置')
    load()
  }

  const remove = async (id) => {
    await client.delete(`/agents/${id}`)
    message.success('已删除')
    load()
  }

  const showPreview = async (id) => {
    const json = await client.get(`/agents/${id}/card-preview`)
    setPreview({
      open: true, id,
      json: typeof json === 'string' ? JSON.stringify(JSON.parse(json), null, 2) : JSON.stringify(json, null, 2),
    })
  }

  const caps = (a) => { try { return JSON.parse(a.capabilities || '{}') } catch { return {} } }
  const publishedCount = data.list.filter((a) => a.publishedSeq != null).length
  const streamingCount = data.list.filter((a) => caps(a).streaming).length
  const maxSeq = Math.max(0, ...data.list.map((a) => a.publishedSeq || 0))

  const columns = [
    { title: 'AGENT ID', dataIndex: 'id', width: 175, render: (v) => <span className="id-cell">{v}</span> },
    {
      title: '名称', key: 'name',
      render: (_, a) => (
        <div className="name-cell"><b>{a.name}</b><span>{a.description || '-'}</span></div>
      ),
    },
    { title: '版本', dataIndex: 'version', width: 80 },
    { title: '协议', dataIndex: 'protocolVersion', width: 70 },
    {
      title: '流式', key: 'streaming', width: 80,
      render: (_, a) => (caps(a).streaming
        ? <SoftBadge type="cyan" dot>SSE</SoftBadge>
        : <SoftBadge type="gray">否</SoftBadge>),
    },
    {
      title: '状态', key: 'status', width: 85,
      render: (_, a) => (a.status === 1
        ? <SoftBadge type="green" dot>启用</SoftBadge>
        : <SoftBadge type="red" dot>禁用</SoftBadge>),
    },
    {
      title: '发布', key: 'published', width: 110,
      render: (_, a) => (a.publishedSeq
        ? <SoftBadge type="blue">已发布 v{a.publishedSeq}</SoftBadge>
        : <SoftBadge type="orange" dot>未发布</SoftBadge>),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 165, render: (v) => <span style={{ color: colors.textDim, fontSize: 12 }}>{v}</span> },
    {
      title: '操作', key: 'op', width: 300,
      render: (_, a) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EyeOutlined />} onClick={() => showPreview(a.id)}>预览</Button>
          <Button size="small" onClick={() => navigate(`/agents/${a.id}/edit`)}>编辑</Button>
          <Popconfirm title="发布后节点 5s 轮询生效" onConfirm={() => publish(a.id)}>
            <Button size="small" type="primary">发布</Button>
          </Popconfirm>
          {a.publishedSeq != null && (
            <Popconfirm title="下线后网关将拒绝该 Agent 请求" onConfirm={() => unpublish(a.id)}>
              <Button size="small" danger>下线</Button>
            </Popconfirm>
          )}
          <Popconfirm title="确认删除（含上游凭证）？" onConfirm={() => remove(a.id)}>
            <Button size="small" danger type="text">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader title="Agent 管理" desc="注册、发布与管理接入网关的全部 A2A Agent，发布后 5s 内全节点生效" />
      <div className="stat-row">
        <StatCard icon={<RobotOutlined style={{ fontSize: 20 }} />} iconBg={colors.primarySoft} iconColor={colors.primary} label="注册 Agent" value={data.total} />
        <StatCard icon={<CheckCircleOutlined style={{ fontSize: 20 }} />} iconBg={colors.greenSoft} iconColor={colors.green} label="已发布" value={publishedCount} trend={data.total ? `覆盖率 ${Math.round(publishedCount * 100 / Math.max(data.list.length, 1))}%` : null} trendDim />
        <StatCard icon={<ThunderboltOutlined style={{ fontSize: 20 }} />} iconBg={colors.cyanSoft} iconColor={colors.cyan} label="流式 (SSE) 能力" value={streamingCount} />
        <StatCard icon={<SyncOutlined style={{ fontSize: 20 }} />} iconBg={colors.orangeSoft} iconColor={colors.orange} label="最大发布水位 (seq)" value={maxSeq} trend="节点 5s 轮询" trendDim />
      </div>

      <Panel
        title="Agent 列表"
        subtitle={`共 ${data.total} 个，${publishedCount} 个已发布`}
        bodyPadding={false}
        extra={
          <Space>
            <Input
              placeholder="搜索名称 / ID" prefix={<SearchOutlined />} style={{ width: 220 }} allowClear
              onPressEnter={(e) => setQuery({ ...query, page: 1, keyword: e.target.value })}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/agents/new')}>注册 Agent</Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          columns={columns}
          dataSource={data.list}
          loading={loading}
          size="middle"
          pagination={{
            current: query.page, pageSize: query.size, total: data.total,
            onChange: (p) => setQuery({ ...query, page: p }),
          }}
        />
      </Panel>

      <Drawer
        title={`Agent Card 预览 — ${preview.id}`}
        open={preview.open}
        onClose={() => setPreview({ open: false, id: null, json: '' })}
        width={640}
      >
        <Typography.Paragraph type="secondary">
          此即客户端从网关 <Typography.Text code>{'/{id}/.well-known/agent-card.json'}</Typography.Text> 拉取到的内容（url 已重写为网关地址）。
        </Typography.Paragraph>
        <pre style={{ background: '#F6F8FA', padding: 16, borderRadius: 10, overflow: 'auto', fontSize: 12, border: '1px solid #E6EAF2' }}>
          {preview.json}
        </pre>
      </Drawer>
    </div>
  )
}
