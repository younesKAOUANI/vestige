import { useState } from 'react'
import { getStoredApiKey, setStoredApiKey } from '../lib/api'

interface TopBarProps {
  projectId: string
  onProjectIdChange: (projectId: string) => void
}

/**
 * Vestige v1 has no login flow and no "list my projects" endpoint (§8) - an organisation is
 * whatever `X-API-Key` resolves to, and a project is identified by its id directly. This bar is
 * the honest reflection of that: paste a key, paste a project id, both remembered in
 * localStorage so a reload does not lose them.
 */
export function TopBar({ projectId, onProjectIdChange }: TopBarProps) {
  const [apiKey, setApiKeyState] = useState(getStoredApiKey())

  function handleApiKeyChange(value: string) {
    setApiKeyState(value)
    setStoredApiKey(value)
  }

  return (
    <header className="top-bar">
      <div className="brand">
        <span className="brand-mark">Vestige</span>
        <span className="brand-tagline">a trace of something that once existed</span>
      </div>
      <div className="top-bar-fields">
        <label>
          <span>X-API-Key</span>
          <input
            type="password"
            placeholder="vst_..."
            value={apiKey}
            onChange={(event) => handleApiKeyChange(event.target.value)}
          />
        </label>
        <label>
          <span>Project ID</span>
          <input
            type="text"
            placeholder="00000000-0000-0000-0000-000000000000"
            value={projectId}
            onChange={(event) => onProjectIdChange(event.target.value)}
          />
        </label>
      </div>
    </header>
  )
}
