import React from 'react'
import { BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, theme } from 'antd'
import {
  ShopOutlined, AppstoreAddOutlined, SafetyOutlined,
  FileSearchOutlined, SettingOutlined, LogoutOutlined,
} from '@ant-design/icons'
import { getToken, clearToken } from './api/client'
import Login from './pages/Login'
import MarketPage from './pages/MarketPage'
import MarketDetailPage from './pages/MarketDetailPage'
import RegistryPage from './pages/RegistryPage'
import ServerEditPage from './pages/ServerEditPage'
import PoliciesPage from './pages/PoliciesPage'
import AuditLogsPage from './pages/AuditLogsPage'
import SettingsPage from './pages/SettingsPage'

const { Header, Sider, Content } = Layout

const MENU = [
  { key: '/market', icon: <ShopOutlined />, label: 'MCP 市场' },
  { key: '/registry', icon: <AppstoreAddOutlined />, label: '注册管理' },
  { key: '/policies', icon: <SafetyOutlined />, label: '授权管理' },
  { key: '/audit', icon: <FileSearchOutlined />, label: '审计日志' },
  { key: '/settings', icon: <SettingOutlined />, label: '系统设置' },
]

function Shell({ children }) {
  const navigate = useNavigate()
  const location = useLocation()
  const { token } = theme.useToken()
  const selected = '/' + location.pathname.split('/')[1]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ color: '#fff', fontSize: 18, fontWeight: 600 }}>MCP 网关管理台</div>
        <div
          style={{ color: 'rgba(255,255,255,.75)', cursor: 'pointer' }}
          onClick={() => { clearToken(); navigate('/login') }}
        >
          <LogoutOutlined /> 退出
        </div>
      </Header>
      <Layout>
        <Sider theme="light" width={200}>
          <Menu
            mode="inline"
            selectedKeys={[selected]}
            items={MENU}
            onClick={({ key }) => navigate(key)}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: token.colorBgLayout }}>{children}</Content>
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
        <Route path="/market" element={<Guard><MarketPage /></Guard>} />
        <Route path="/market/:serverId" element={<Guard><MarketDetailPage /></Guard>} />
        <Route path="/registry" element={<Guard><RegistryPage /></Guard>} />
        <Route path="/registry/new" element={<Guard><ServerEditPage /></Guard>} />
        <Route path="/registry/:serverId/edit" element={<Guard><ServerEditPage /></Guard>} />
        <Route path="/policies" element={<Guard><PoliciesPage /></Guard>} />
        <Route path="/audit" element={<Guard><AuditLogsPage /></Guard>} />
        <Route path="/settings" element={<Guard><SettingsPage /></Guard>} />
        <Route path="*" element={<Navigate to="/market" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
