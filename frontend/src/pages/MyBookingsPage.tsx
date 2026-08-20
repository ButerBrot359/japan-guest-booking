import { useMe } from '../api/queries'
import { Header } from '../components/Header'

export function MyBookingsPage() {
  const me = useMe()

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5 pb-40">
      <Header me={me.data ?? null} />
      <h2 className="font-display text-lg">Мои брони</h2>
    </div>
  )
}
