import React, { useEffect, useState } from 'react'
import { Card, Col, Input, Row, Select, Tag, Empty, Pagination, Space, Typography } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import client from '../api/client'

const CATEGORIES = [
  { value: 'hr', label: '人力资源' },
  { value: 'finance', label: '财务' },
  { value: 'office', label: '办公协同' },
  { value: 'dev', label: '开发工具' },
]

export default function MarketPage() {
  const [data, setData] = useState({ list: [], total: 0 })
  const [query, setQuery] = useState({ page: 1, size: 12, keyword: '', category: undefined })
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const load = async () => {
    setLoading(true)
    try {
      const params = { ...query }
      Object.keys(params).forEach((k) => (params[k] === '' || params[k] == null) && delete params[k])
      const result = await client.get('/market/servers', { params })
      setData(result)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [query.page, query.category])

  return (
    <div>
      <Space style={{ marginBottom: 16 }} size="middle">
        <Input
          placeholder="搜索 MCP 名称 / ID"
          prefix={<SearchOutlined />}
          style={{ width: 280 }}
          allowClear
          onPressEnter={(e) => setQuery({ ...query, page: 1, keyword: e.target.value })}
        />
        <Select
          placeholder="分类"
          allowClear
          style={{ width: 160 }}
          options={CATEGORIES}
          onChange={(v) => setQuery({ ...query, page: 1, category: v })}
        />
      </Space>

      {data.list.length === 0 && !loading ? (
        <Empty description="暂无已发布的 MCP" />
      ) : (
        <Row gutter={[16, 16]}>
          {data.list.map((s) => (
            <Col xs={24} sm={12} md={8} lg={6} key={s.serverId}>
              <Card
                hoverable
                loading={loading}
                onClick={() => navigate(`/market/${s.serverId}`)}
                title={s.name}
                extra={<Tag color="green">已发布</Tag>}
              >
                <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ minHeight: 44 }}>
                  {s.description || s.serverId}
                </Typography.Paragraph>
                <Space direction="vertical" size={4} style={{ fontSize: 12, color: '#888' }}>
                  <span>ID: {s.serverId}</span>
                  <span>分类: {s.category || '-'} | 版本: {s.version || '-'}</span>
                  <span>团队: {s.ownerTeam || '-'}</span>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Pagination
        style={{ marginTop: 24, textAlign: 'right' }}
        current={query.page}
        pageSize={query.size}
        total={data.total}
        onChange={(p) => setQuery({ ...query, page: p })}
        showTotal={(t) => `共 ${t} 个 MCP Server`}
      />
    </div>
  )
}
