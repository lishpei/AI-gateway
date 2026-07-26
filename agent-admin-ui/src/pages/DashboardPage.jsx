import React, { useEffect, useState } from 'react'
import { Table, Typography } from 'antd'
import { RobotOutlined, CheckCircleOutlined, CloudServerOutlined, SyncOutlined } from '@ant-design/icons'
import client from '../api/client'
import PageHeader from '../components/PageHeader'
import StatCard from '../components/StatCard'
import Panel from '../components/Panel'
import SoftBadge from '../components/SoftBadge'
import { colors } from '../theme'

export default function DashboardPage() {
  const [nodes, setNodes] = useState([])
  const [agents, setAgents] = useState([])

  const load = async () => {
    try {
      setNodes(await client.get('/dashboard/nodes'))
    } catch { /* 无节点时为空 */ }
    setAgents((await client.get('/agents', { params: { size: 200 } })).list)
  }

  useEffect(() => { load() }, [])

  const published = agents.filter((a) => a.publishedSeq != null)

  const nodeColumns = [
    { title: '节点 ID', dataIndex: 'nodeId', render: (v) => <span className="id-cell">{v}</span> },
    { title: '同步水位 (seq)', dataIndex: 'seq', render: (v) => <Typography.Text strong>{v}</Typography.Text> },
    {
      title: 'Redis', dataIndex: 'redisOk',
      render: (v) => (v === true || v === 'true'
        ? <SoftBadge type="green" dot>正常</SoftBadge>
        : <SoftBadge type="red" dot>异常</SoftBadge>),
    },
    { title: '最近心跳', dataIndex: 'ts', render: (v) => <span style={{ color: colors.textDim, fontSize: 12 }}>{v}</span> },
  ]

  const agentColumns = [
    { title: 'Agent ID', dataIndex: 'id', render: (v) => <span className="id-cell">{v}</span> },
    { title: '名称', dataIndex: 'name' },
    {
      title: '发布水位', dataIndex: 'publishedSeq',
      render: (v) => (v != null ? <SoftBadge type="blue">v{v}</SoftBadge> : <SoftBadge type="orange" dot>未发布</SoftBadge>),
    },
    {
      title: '节点已同步', key: 'synced',
      render: (_, a) => {
        if (a.publishedSeq == null) return <SoftBadge type="gray">—</SoftBadge>
        const allSynced = nodes.length > 0 && nodes.every((n) => n.seq >= a.publishedSeq)
        return allSynced
          ? <SoftBadge type="green" dot>已同步</SoftBadge>
          : <SoftBadge type="blue" dot>同步中</SoftBadge>
      },
    },
  ]

  return (
    <div>
      <PageHeader title="运行看板" desc="数据面节点心跳水位与 Agent 发布-同步状态总览" />
      <div className="stat-row">
        <StatCard icon={<RobotOutlined style={{ fontSize: 20 }} />} iconBg={colors.primarySoft} iconColor={colors.primary} label="注册 Agent 总数" value={agents.length} />
        <StatCard icon={<CheckCircleOutlined style={{ fontSize: 20 }} />} iconBg={colors.greenSoft} iconColor={colors.green} label="已发布" value={published.length} />
        <StatCard icon={<CloudServerOutlined style={{ fontSize: 20 }} />} iconBg={colors.cyanSoft} iconColor={colors.cyan} label="数据面节点数" value={nodes.length} />
        <StatCard icon={<SyncOutlined style={{ fontSize: 20 }} />} iconBg={colors.orangeSoft} iconColor={colors.orange} label="最大同步水位" value={nodes.length ? Math.max(...nodes.map((n) => n.seq || 0)) : 0} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
        <Panel title="数据面节点" subtitle="心跳与同步水位" bodyPadding={false}>
          <Table rowKey="nodeId" columns={nodeColumns} dataSource={nodes} pagination={false} size="small" />
        </Panel>
        <Panel title="Agent 发布与同步状态" bodyPadding={false}>
          <Table rowKey="id" columns={agentColumns} dataSource={agents} pagination={false} size="small" />
        </Panel>
      </div>
    </div>
  )
}
