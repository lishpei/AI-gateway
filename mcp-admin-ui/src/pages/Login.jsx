import React, { useState } from 'react'
import { Card, Input, Button, Typography } from 'antd'
import { LockOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { setToken } from '../api/client'

export default function Login() {
  const [token, setTokenValue] = useState('')
  const navigate = useNavigate()

  const onLogin = () => {
    if (!token.trim()) return
    setToken(token.trim())
    navigate('/market')
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>MCP 网关管理台</Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          请输入管理员 Token 登录（dev: dev-admin-token-2026）
        </Typography.Paragraph>
        <Input.Password
          size="large"
          prefix={<LockOutlined />}
          placeholder="管理员 Token"
          value={token}
          onChange={(e) => setTokenValue(e.target.value)}
          onPressEnter={onLogin}
        />
        <Button type="primary" size="large" block style={{ marginTop: 16 }} onClick={onLogin}>
          登录
        </Button>
      </Card>
    </div>
  )
}
