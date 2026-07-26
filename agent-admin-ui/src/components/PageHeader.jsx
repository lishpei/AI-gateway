import React from 'react'

export default function PageHeader({ title, desc }) {
  return (
    <div className="page-head">
      <div className="page-title">{title}</div>
      {desc && <div className="page-desc">{desc}</div>}
    </div>
  )
}
