import type { Me } from '../api/types'
import { useLogout } from '../api/queries'

export function Header({ me, onLoginClick }: { me: Me | null; onLoginClick?: () => void }) {
  const logout = useLogout()
  return (
    <header className="mb-4 flex items-center justify-between">
      <h1 className="font-display text-2xl">
        Домик в Японии <span className="text-hanko">◉</span>
      </h1>
      {me != null && (
        <button type="button" className="text-sm text-muted" onClick={() => logout.mutate()}>
          выйти
        </button>
      )}
      {me == null && onLoginClick && (
        <button type="button" className="rounded-xl bg-ink px-4 py-1.5 text-sm text-paper"
          onClick={onLoginClick}>
          Войти
        </button>
      )}
    </header>
  )
}
