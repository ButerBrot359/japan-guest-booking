import { useState } from 'react'
import { useLogout, useMe } from '../api/queries'
import { AdminAccessRequests } from '../components/admin/AdminAccessRequests'
import { AdminLoginCard } from '../components/admin/AdminLoginCard'

type Tab = 'requests' | 'guests'

export function AdminPage() {
  const me = useMe()
  const logout = useLogout()
  const [tab, setTab] = useState<Tab>('requests')

  if (me.isLoading) return null
  if (me.data?.role !== 'ADMIN') return <AdminLoginCard />

  const tabClass = (t: Tab) =>
    `rounded-lg px-3 py-1.5 text-sm ${tab === t ? 'bg-ink text-paper' : 'text-muted'}`

  return (
    <div className="mx-auto min-h-dvh max-w-5xl bg-paper px-6 py-5">
      <header className="mb-5 flex items-center justify-between">
        <h1 className="font-display text-2xl">Админка</h1>
        <button type="button" className="text-sm text-muted" onClick={() => logout.mutate()}>выйти</button>
      </header>
      <nav className="mb-5 flex gap-2">
        <button type="button" className={tabClass('requests')} onClick={() => setTab('requests')}>Заявки</button>
        <button type="button" className={tabClass('guests')} onClick={() => setTab('guests')}>Гости</button>
      </nav>
      {tab === 'requests' && <AdminAccessRequests />}
      {tab === 'guests' && <div>Гости</div>}
    </div>
  )
}
