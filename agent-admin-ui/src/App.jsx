import React from 'react'
import { BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu } from 'antd'
import {
  RobotOutlined, TeamOutlined, DashboardOutlined, LogoutOutlined,
} from '@ant-design/icons'
import { getToken, clearToken } from './api/client'
import Login from './pages/Login'
import AgentsPage from './pages/AgentsPage'
import AgentEditPage from './pages/AgentEditPage'
import CallersPage from './pages/CallersPage'
import DashboardPage from './pages/DashboardPage'

const { Header, Sider, Content } = Layout

const MENU = [
  { key: '/agents', icon: <RobotOutlined />, label: 'Agent 管理' },
  { key: '/callers', icon: <TeamOutlined />, label: '调用方管理' },
  { key: '/dashboard', icon: <DashboardOutlined />, label: '运行看板' },
]

const TITLES = {
  '/agents': '网关管理 / Agent 管理',
  '/callers': '网关管理 / 调用方管理',
  '/dashboard': '观测 / 运行看板',
}

function Logo() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.4">
      <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" />
    </svg>
  )
}

function Shell({ children }) {
  const navigate = useNavigate()
  const location = useLocation()
  const selected = '/' + location.pathname.split('/')[1]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid #E6EAF2' }}>
        <div className="topbar-crumb">{TITLES[selected] || 'A2A 网关管理面'}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <span className="env-tag">DEV</span>
          <div className="topbar-avatar" title="退出登录" onClick={() => { clearToken(); navigate('/login') }}>A</div>
        </div>
      </Header>
      <Layout>
        <Sider theme="light" width={236} style={{ borderRight: '1px solid #E6EAF2', display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
            <div className="brand">
              <div className="brand-logo"><Logo /></div>
              <div>
                <div className="brand-name">Agent Gateway</div>
                <div className="brand-sub">A2A 网关管理面</div>
              </div>
            </div>
            <Menu
              mode="inline"
              selectedKeys={[selected]}
              items={MENU}
              onClick={({ key }) => navigate(key)}
              style={{ borderRight: 0, flex: 1 }}
            />
            <div className="sidebar-foot">
              <span className="dot-ok" />节点 <b>node-local-1</b> · Redis 正常<br />
              同步水位 <b>轮询模式</b> · 5s 生效
            </div>
          </div>
        </Sider>
        <Content style={{ padding: 26, background: '#F4F6FB', overflow: 'auto' }}>{children}</Content>
      </Layout>
    </Layout>
  )
}

function Guard({ children }) {
  if (!getToken()) return <Navigate to="/login" replace />
  return <Shell>{children}</Shell>
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/agents" element={<Guard><AgentsPage /></Guard>} />
        <Route path="/agents/new" element={<Guard><AgentEditPage /></Guard>} />
        <Route path="/agents/:id/edit" element={<Guard><AgentEditPage /></Guard>} />
        <Route path="/callers" element={<Guard><CallersPage /></Guard>} />
        <Route path="/dashboard" element={<Guard><DashboardPage /></Guard>} />
        <Route path="*" element={<Navigate to="/agents" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
