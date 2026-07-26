import React, { useEffect, useState } from 'react'
import { Card, Descriptions, Table, Tabs, Tag, Typography } from 'antd'
import { useParams } from 'react-router-dom'
import client from '../api/client'

export default function MarketDetailPage() {
  const { serverId } = useParams()
  const [detail, setDetail] = useState(null)

  useEffect(() => {
    client.get(`/market/servers/${serverId}`).then(setDetail)
  }, [serverId])

  if (!detail) return <Card loading />

  const { server, tools } = detail

  const toolColumns = [
    { title: '工具名', dataIndex: 'toolName' },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: '所需Scope', dataIndex: 'requiredScope', render: (v) => v || '-' },
    { title: '限流(rpm)', dataIndex: 'rateLimitRpm', width: 100 },
    { title: '数据分类', dataIndex: 'dataClassification', render: (v) => v || '-' },
    {
      title: '状态', dataIndex: 'isActive', width: 80,
      render: (v) => (v === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
  ]

  return (
    <div>
      <Typography.Title level={3}>{server.name} <Tag>{server.serverId}</Tag></Typography.Title>
      <Tabs
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <Card>
                <Descriptions column={2} bordered size="small">
                  <Descriptions.Item label="描述" span={2}>{server.description || '-'}</Descriptions.Item>
                  <Descriptions.Item label="接入地址">{server.baseUrl}</Descriptions.Item>
                  <Descriptions.Item label="资源URI">{server.resourceUri}</Descriptions.Item>
                  <Descriptions.Item label="协议">{server.protocolType}</Descriptions.Item>
                  <Descriptions.Item label="认证模式">{server.authMode}</Descriptions.Item>
                  <Descriptions.Item label="所属团队">{server.ownerTeam || '-'}</Descriptions.Item>
                  <Descriptions.Item label="负责人">{server.ownerEmail || '-'}</Descriptions.Item>
                  <Descriptions.Item label="数据分类">{server.dataClassification}</Descriptions.Item>
                  <Descriptions.Item label="版本">{server.version || '-'}</Descriptions.Item>
                  <Descriptions.Item label="健康状态">
                    <Tag color={server.healthStatus === 'healthy' ? 'green' : 'orange'}>{server.healthStatus}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="累计调用">{server.totalCalls}</Descriptions.Item>
                </Descriptions>
              </Card>
            ),
          },
          {
            key: 'tools',
            label: `工具列表 (${tools.length})`,
            children: (
              <Card>
                <Table rowKey="toolName" columns={toolColumns} dataSource={tools} pagination={false} size="small" />
              </Card>
            ),
          },
        ]}
      />
    </div>
  )
}
