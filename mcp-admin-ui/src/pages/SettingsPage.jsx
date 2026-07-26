import React, { useEffect, useState } from 'react'
import { Card, Col, Descriptions, Row, Statistic, Table, Tag, Typography } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons'
import client from '../api/client'

export default function SettingsPage() {
  const [status, setStatus] = useState(null)
  const [servers, setServers] = useState([])

  useEffect(() => {
    client.get('/sync/status').then(setStatus)
    client.get('/registry/servers', { params: { size: 100 } }).then(async (d) => {
      const list = await Promise.all(
        d.list.map(async (s) => {
          try {
            const st = await client.get(`/sync/status/${s.serverId}`)
            return { ...s, sync: st }
          } catch {
            return { ...s, sync: null }
          }
        })
      )
      setServers(list)
    })
  }, [])

  const columns = [
    { title: 'Server ID', dataIndex: 'serverId' },
    {
      title: '快照版本', key: 'ver',
      render: (_, s) => (s.sync?.snapshotVersion != null ? `v${s.sync.snapshotVersion}` : '-'),
    },
    {
      title: '工具数(缓存)', key: 'tools',
      render: (_, s) => s.sync?.toolCount ?? '-',
    },
    {
      title: '配置已同步', key: 'cfg',
      render: (_, s) =>
        s.sync?.cfgExists ? <Tag color="green">是</Tag> : <Tag>否</Tag>,
    },
  ]

  return (
    <div>
      <Typography.Title level={3}>系统设置与同步状态</Typography.Title>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic
              title="Redis（配置同步通道）"
              value={status?.redisOk ? '连接正常' : '连接失败'}
              prefix={status?.redisOk
                ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
                : <CloseCircleOutlined style={{ color: '#f5222d' }} />}
            />
          </Card>
        </Col>
      </Row>
      <Card title="各 Server 快照同步状态">
        <Table rowKey="serverId" columns={columns} dataSource={servers} pagination={false} size="small" />
      </Card>
      <Card title="环境信息" style={{ marginTop: 16 }}>
        <Descriptions column={1} size="small">
          <Descriptions.Item label="管理台 API">http://localhost:8080/api/v1</Descriptions.Item>
          <Descriptions.Item label="IdP（dev 内嵌 Mock）">http://localhost:8080/idp-mock</Descriptions.Item>
          <Descriptions.Item label="MCP 网关">http://localhost:9080</Descriptions.Item>
          <Descriptions.Item label="说明">网关节点 shared_dict 缓存 TTL 60s，快照变更最长 60s 全节点生效</Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  )
}
