import React, { useState } from 'react'
import { Card, Input, Button, Typography } from 'antd'
import { LockOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { setToken } from '../api/client'

function Logo() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.4">
      <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" />
    </svg>
  )
}

export default function Login() {
  const [token, setTokenValue] = useState('')
  const navigate = useNavigate()

  const onLogin = () => {
    if (!token.trim()) return
    setToken(token.trim())
    navigate('/agents')
  }

  return (
    <div className="login-wrap">
      <Card className="login-card" variant="borderless">
        <div className="login-brand">
          <div className="brand-logo"><Logo /></div>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>Agent Gateway</Typography.Title>
          <Typography.Paragraph type="secondary">
            A2A 网关管理面 · 请输入管理员 Token 登录
          </Typography.Paragraph>
        </div>
        <Input.Password
          size="large"
          prefix={<LockOutlined />}
          placeholder="管理员 Token（dev: dev-admin-token-2026）"
          value={token}
          onChange={(e) => setTokenValue(e.target.value)}
          onPressEnter={onLogin}
        />
        <Button type="primary" size="large" block style={{ marginTop: 16 }} onClick={onLogin}>
          登 录
        </Button>
      </Card>
    </div>
  )
}
