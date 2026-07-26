import React from 'react'

/** 面板：白色圆角阴影容器，可选头部（标题 + 右侧操作区） */
export default function Panel({ title, subtitle, extra, children, bodyPadding = true }) {
  return (
    <div className="panel">
      {(title || extra) && (
        <div className="panel-head">
          <div className="panel-title">
            {title}
            {subtitle && <span>{subtitle}</span>}
          </div>
          {extra && <div>{extra}</div>}
        </div>
      )}
      {bodyPadding ? <div className="panel-body">{children}</div> : children}
    </div>
  )
}
