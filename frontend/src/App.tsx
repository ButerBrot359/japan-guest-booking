import { useState } from 'react'
import { useCalendar, useMe } from './api/queries'
import { Calendar, type Selection } from './components/Calendar'
import { LoginCard } from './components/LoginCard'
import { ProfileCard } from './components/ProfileCard'
import { addMonths, todayIso } from './lib/dates'

export default function App() {
  const [monthStart, setMonthStart] = useState(todayIso().slice(0, 7) + '-01')
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })
  const me = useMe()
  const calendar = useCalendar(monthStart, addMonths(monthStart, 2))
  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5">
      <header className="mb-4 flex items-center justify-between">
        <h1 className="font-display text-lg">
          Домик в Японии <span className="text-hanko">◉</span>
        </h1>
      </header>
      {me.data != null && (
        <ProfileCard
          me={me.data}
          onReschedule={() => {}}
          onCancel={() => {}}
          onEnterCode={() => {}}
          onCancelPending={() => {}}
        />
      )}
      {me.data == null && !me.isLoading && <LoginCard />}
      <Calendar
        monthStart={monthStart}
        days={days}
        selection={selection}
        selectable={false}
        onShiftMonth={(d) => setMonthStart((m) => addMonths(m, d))}
        onPick={() => setSelection(selection)}
      />
    </div>
  )
}
