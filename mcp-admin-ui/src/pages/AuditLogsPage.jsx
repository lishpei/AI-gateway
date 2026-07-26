import React, { useEffect, useState } from 'react'
import { Card, Col, DatePicker, Input, Row, Select, Statistic, Table, Tag } from 'antd'
import client from '../api/client'

const { RangePicker } = DatePicker

export default function AuditLogsPage() {
  const [data, setData] = useState({ list: [], total: 0 })
  const [stats, setStats] = useState(null)
  const [query, setQuery] = useState({ page: 1, size: 20 })
  const [filters, setFilters] = useState({})
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const params = { ...query, ...filters }
      Object.keys(params).forEach((k) => (params[k] === '' || params[k] == null) && delete params[k])
      setData(await client.get('/audit/logs', { params }))
    } finally {
      setLoading(false)
    }
  }

  const loadStats = async () => {
    const params = {}
    if (filters.startTime) params.startTime = filters.startTime
    if (filters.endTime) params.endTime = filters.endTime
    setStats(await client.get('/audit/statistics', { params }))
  }

  useEffect(() => { load() }, [query.page, filters])
  useEffect(() => { loadStats() }, [filters.startTime, filters.endTime])

  const columns = [
    { title: '时间', dataIndex: 'timestamp', width: 170 },
    { title: '请求ID', dataIndex: 'requestId', width: 150, ellipsis: true },
    { title: '委托用户', dataIndex: 'delegatorUserId', width: 100, render: (v) => v || '-' },
    { title: '调用Agent', dataIndex: 'callerAgentId', width: 140, render: (v) => v || '-' },
    { title: '方法', dataIndex: 'jsonrpcMethod', width: 110 },
    { title: '工具', dataIndex: 'toolName', width: 150, render: (v) => v || '-' },
    { title: 'Server', dataIndex: 'serverId', width: 140 },
    {
      title: '认证', dataIndex: 'authResult', width: 80,
      render: (v) => <Tag color={v === 'success' ? 'green' : 'red'}>{v}</Tag>,
    },
    {
      title: '决策', dataIndex: 'policyDecision', width: 80,
      render: (v) => <Tag color={v === 'allow' ? 'green' : v === 'deny' ? 'red' : 'default'}>{v}</Tag>,
    },
    { title: '延迟(ms)', dataIndex: 'latencyMs', width: 90 },
    { title: '状态', dataIndex: 'responseStatus', width: 70 },
  ]

  return (
    <div>
      {stats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}><Card><Statistic title="总调用量" value={stats.totalCalls} /></Card></Col>
          <Col span={6}><Card><Statistic title="平均延迟 (ms)" value={stats.avgLatency} /></Card></Col>
          <Col span={6}><Card><Statistic title="拒绝率 (%)" value={stats.denyRate} /></Card></Col>
          <Col span={6}>
            <Card>
              <Statistic title="Top 工具" value={stats.topTools?.[0]?.[0] || '-'} suffix={stats.topTools?.[0] ? `${stats.topTools[0][1]}次` : ''} />
            </Card>
          </Col>
        </Row>
      )}

      <Card style={{ marginBottom: 16 }}>
        <DatePicker.RangePicker
          showTime
          onChange={(v) => setFilters({
            ...filters,
            startTime: v?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
            endTime: v?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
          })}
        />
        <Input placeholder="用户" style={{ width: 140, marginLeft: 12 }} allowClear
          onChange={(e) => setFilters({ ...filters, userId: e.target.value || undefined })} />
        <Input placeholder="Agent" style={{ width: 180, marginLeft: 12 }} allowClear
          onChange={(e) => setFilters({ ...filters, agentId: e.target.value || undefined })} />
        <Input placeholder="工具" style={{ width: 180, marginLeft: 12 }} allowClear
          onChange={(e) => setFilters({ ...filters, toolName: e.target.value || undefined })} />
        <Select placeholder="决策" allowClear style={{ width: 120, marginLeft: 12 }}
          options={[{ value: 'allow', label: 'allow' }, { value: 'deny', label: 'deny' }]}
          onChange={(v) => setFilters({ ...filters, policyDecision: v })} />
      </Card>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={data.list}
        loading={loading}
        size="small"
        scroll={{ x: 1400 }}
        pagination={{
          current: query.page, pageSize: query.size, total: data.total,
          onChange: (p) => setQuery({ ...query, page: p }), showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </div>
  )
}
