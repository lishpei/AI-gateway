import React from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { antdTheme } from './theme'
import './index.css'
import App from './App'

ReactDOM.createRoot(document.getElementById('root')).render(
  <ConfigProvider locale={zhCN} theme={antdTheme}>
    <App />
  </ConfigProvider>
)
