import React from 'react'

/** 统计卡：彩色图标瓦片 + 标签 + 数值 + 趋势 */
export default function StatCard({ icon, iconBg, iconColor, label, value, trend, trendDim }) {
  return (
    <div className="stat-card">
      <div className="stat-icon" style={{ background: iconBg, color: iconColor }}>{icon}</div>
      <div>
        <div className="stat-label">{label}</div>
        <div className="stat-value">{value}</div>
        {trend != null && <div className={trendDim ? 'stat-trend dim' : 'stat-trend'}>{trend}</div>}
      </div>
    </div>
  )
}
