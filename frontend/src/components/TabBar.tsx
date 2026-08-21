import { NavLink } from 'react-router'

const tabClass = ({ isActive }: { isActive: boolean }) =>
  [
    'flex-1 rounded-lg py-1.5 text-center text-sm',
    isActive ? 'bg-paper font-semibold shadow-sm' : 'text-muted',
  ].join(' ')

export function TabBar() {
  return (
    <nav className="mb-3 flex gap-1 rounded-xl bg-card p-1 lg:hidden">
      <NavLink to="/" end className={tabClass}>Календарь</NavLink>
      <NavLink to="/my-bookings" className={tabClass}>Мои брони</NavLink>
    </nav>
  )
}
