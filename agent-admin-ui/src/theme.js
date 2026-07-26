// 设计系统主题 token（方案B · 浅色现代企业风）
export const colors = {
  bg: '#F4F6FB',
  panel: '#FFFFFF',
  border: '#E6EAF2',
  text: '#1E293B',
  textDim: '#64748B',
  primary: '#4F46E5',
  primarySoft: '#EEF2FF',
  green: '#059669',
  greenSoft: '#D1FAE5',
  blue: '#2563EB',
  blueSoft: '#DBEAFE',
  orange: '#D97706',
  orangeSoft: '#FEF3C7',
  cyan: '#0891B2',
  cyanSoft: '#CFFAFE',
  red: '#DC2626',
  redSoft: '#FEE2E2',
  shadow: '0 1px 2px rgba(16,24,40,.04), 0 4px 16px rgba(16,24,40,.06)',
}

export const antdTheme = {
  token: {
    colorPrimary: colors.primary,
    colorInfo: colors.primary,
    colorBgLayout: colors.bg,
    colorTextBase: colors.text,
    colorTextSecondary: colors.textDim,
    colorBorder: colors.border,
    colorBorderSecondary: '#F1F4F9',
    borderRadius: 8,
    fontFamily: "'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Layout: {
      headerBg: colors.panel,
      headerHeight: 62,
      headerPadding: '0 28px',
      siderBg: colors.panel,
      bodyBg: colors.bg,
    },
    Menu: {
      itemSelectedBg: colors.primarySoft,
      itemSelectedColor: colors.primary,
      itemColor: colors.textDim,
      itemBorderRadius: 9,
      itemMarginInline: 12,
    },
    Table: {
      headerBg: '#F8FAFC',
      headerColor: colors.textDim,
      rowHoverBg: '#FAFBFF',
      borderColor: '#F1F4F9',
    },
    Card: {
      borderRadiusLG: 14,
      boxShadowTertiary: colors.shadow,
      colorBorderSecondary: colors.border,
    },
    Button: {
      borderRadius: 8,
      primaryShadow: '0 4px 12px rgba(79,70,229,.28)',
      fontWeight: 600,
    },
    Tag: {
      borderRadiusSM: 20,
    },
    Drawer: {
      borderRadiusLG: 14,
    },
    Modal: {
      borderRadiusLG: 14,
    },
  },
}
