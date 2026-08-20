import { Link } from 'react-router'
import type { Me } from '../api/types'

export function Header({ me, onLoginClick }: { me: Me | null; onLoginClick?: () => void }) {
  return (
    <header className="mb-4 flex items-center justify-between">
      <h1 className="font-display text-2xl">
        Домик в Японии <span className="text-hanko">◉</span>
      </h1>
      {me != null && (
        <nav className="flex gap-3 text-sm">
          <Link to="/" className="underline-offset-4 hover:underline">Календарь</Link>
          <Link to="/my-bookings" className="underline-offset-4 hover:underline">Мои брони</Link>
        </nav>
      )}
      {me == null && onLoginClick && (
        <button
          type="button"
          className="rounded-xl bg-ink px-4 py-1.5 text-sm text-paper"
          onClick={onLoginClick}
        >
          Войти
        </button>
      )}
    </header>
  )
}
