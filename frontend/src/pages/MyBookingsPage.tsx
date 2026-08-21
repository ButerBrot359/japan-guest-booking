import { Navigate, useNavigate } from 'react-router'
import { useMe } from '../api/queries'
import { ActiveBookingSection } from '../components/ActiveBookingSection'
import { Greeting } from '../components/Greeting'
import { Header } from '../components/Header'
import { HistoryList } from '../components/HistoryList'
import { TabBar } from '../components/TabBar'

export function MyBookingsPage() {
  const me = useMe()
  const navigate = useNavigate()

  // прямой заход анонима — на календарь
  if (me.data == null && !me.isLoading) return <Navigate to="/" replace />
  // на десктопе всё видно на главном экране — отдельной страницы нет
  if (typeof window.matchMedia === 'function'
      && window.matchMedia('(min-width: 1024px)').matches) {
    return <Navigate to="/" replace />
  }

  return (
    <div className="mx-auto max-w-md min-h-dvh bg-paper px-4 py-5 pb-8">
      <Header me={me.data ?? null} />
      {me.data != null && (
        <>
          <Greeting me={me.data} />
          <TabBar />
          <ActiveBookingSection
            onReschedule={() => navigate('/', { state: { startReschedule: true } })}
          />
          <HistoryList />
        </>
      )}
    </div>
  )
}
