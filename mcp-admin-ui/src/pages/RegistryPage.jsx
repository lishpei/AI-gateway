import React, { useEffect, useState } from 'react'
import { Button, Input, message, Modal, Popconfirm, Space, Table, Tag } from 'antd'
import { PlusOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import client from '../api/client'

const STATUS = {
  0: { text: '草稿', color: 'default' },
  1: { text: '待审', color: 'orange' },
  2: { text: '已发布', color: 'green' },
  3: { text: '已废弃', color: 'red' },
}

export default function RegistryPage() {
  const [data, setData] = useState({ list: [], total: 0 })
  const [query, setQuery] = useState({ page: 1, size: 10, keyword: '' })
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const load = async () => {
    setLoading(true)
    try {
      const params = { ...query }
      if (!params.keyword) delete params.keyword
      setData(await client.get('/registry/servers', { params }))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [query.page])

  const publish = async (serverId) => {
    const r = await client.post(`/registry/servers/${serverId}/publish`)
    message.success(`发布成功，快照版本 v${r.snapshotVersion}`)
    load()
  }

  const deprecate = async (serverId) => {
    await client.post(`/registry/servers/${serverId}/deprecate`)
    message.success('已废弃并从网关移除')
    load()
  }

  const remove = async (serverId) => {
    await client.delete(`/registry/servers/${serverId}`)
    message.success('已删除')
    load()
  }

  const columns = [
    { title: 'Server ID', dataIndex: 'serverId' },
    { title: '名称', dataIndex: 'name' },
    { title: '分类', dataIndex: 'category', width: 90, render: (v) => v || '-' },
    { title: '认证模式', dataIndex: 'authMode', width: 130 },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v) => <Tag color={STATUS[v]?.color}>{STATUS[v]?.text || v}</Tag>,
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 160 },
    {
      title: '操作', key: 'op', width: 260,
      render: (_, s) => (
        <Space size="small">
          <Button size="small" onClick={() => navigate(`/registry/${s.serverId}/edit`)}>编辑</Button>
          {s.status !== 2 ? (
            <Popconfirm title="发布后 60s 内全节点生效" onConfirm={() => publish(s.serverId)}>
              <Button size="small" type="primary">发布</Button>
            </Popconfirm>
          ) : (
            <Popconfirm title="废弃后网关将拒绝该 Server 请求" onConfirm={() => deprecate(s.serverId)}>
              <Button size="small" danger>废弃</Button>
            </Popconfirm>
          )}
          <Popconfirm title="确认删除（含全部工具）？" onConfirm={() => remove(s.serverId)}>
            <Button size="small" danger type="text">删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Input
          placeholder="搜索名称 / ID"
          prefix={<SearchOutlined />}
          style={{ width: 260 }}
          allowClear
          onPressEnter={(e) => setQuery({ ...query, page: 1, keyword: e.target.value })}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/registry/new')}>
          注册 MCP Server
        </Button>
      </Space>
      <Table
        rowKey="serverId"
        columns={columns}
        dataSource={data.list}
        loading={loading}
        pagination={{
          current: query.page, pageSize: query.size, total: data.total,
          onChange: (p) => setQuery({ ...query, page: p }),
        }}
      />
    </div>
  )
}
