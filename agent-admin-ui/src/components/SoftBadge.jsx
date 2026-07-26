import React from 'react'
import { colors } from '../theme'

const PRESETS = {
  green: { bg: colors.greenSoft, color: colors.green },
  blue: { bg: colors.blueSoft, color: colors.blue },
  orange: { bg: colors.orangeSoft, color: colors.orange },
  cyan: { bg: colors.cyanSoft, color: colors.cyan },
  gray: { bg: '#F1F5F9', color: colors.textDim },
  red: { bg: colors.redSoft, color: colors.red },
}

/** 软徽标：柔和底色 + 彩色文字（可选圆点） */
export default function SoftBadge({ type = 'gray', dot = false, children }) {
  const p = PRESETS[type] || PRESETS.gray
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      fontSize: 11, padding: '3px 10px', borderRadius: 20, fontWeight: 600,
      background: p.bg, color: p.color, whiteSpace: 'nowrap',
    }}>
      {dot && <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'currentColor' }} />}
      {children}
    </span>
  )
}
