import { useState } from 'react'
import { ApiError } from './api/client'
import { useCalendar, useCreateBooking, useMe } from './api/queries'
import { BookingSheet } from './components/BookingSheet'
import { Calendar, type Selection } from './components/Calendar'
import { LoginCard } from './components/LoginCard'
import { ProfileCard } from './components/ProfileCard'
import { addMonths, isoRange, todayIso } from './lib/dates'
import { pickDay } from './lib/selection'

export default function App() {
  const [monthStart, setMonthStart] = useState(todayIso().slice(0, 7) + '-01')
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })
  const me = useMe()
  const calendar = useCalendar(monthStart, addMonths(monthStart, 2))
  const createBooking = useCreateBooking()
  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))
  const resetSelection = () => setSelection({ checkIn: null, checkOut: null })

  // при выбранном заезде — ближайший не-FREE день после него разрешён как выезд (полуинтервал)
  const checkoutCandidates = (() => {
    if (!selection.checkIn || selection.checkOut) return undefined
    for (const iso of isoRange(selection.checkIn, addMonths(monthStart, 2))) {
      if (iso > selection.checkIn && (days.get(iso)?.status ?? 'FREE') !== 'FREE')
        return new Set([iso])
    }
    return undefined
  })()

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5 pb-40">
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
        selectable={me.data != null}
        onShiftMonth={(d) => setMonthStart((m) => addMonths(m, d))}
        onPick={(d) => setSelection((s) => pickDay(s, d, days))}
        checkoutCandidates={checkoutCandidates}
      />
      {selection.checkIn && selection.checkOut && me.data != null && (
        <BookingSheet
          selection={{ checkIn: selection.checkIn, checkOut: selection.checkOut }}
          willReplace={me.data.activeBooking?.status === 'CONFIRMED' ? me.data.activeBooking : null}
          pending={createBooking.isPending}
          errorCode={createBooking.error instanceof ApiError ? createBooking.error.code : null}
          onSubmit={(comment) => {
            const { checkIn, checkOut } = selection
            if (!checkIn || !checkOut) return
            createBooking.mutate(
              { checkIn, checkOut, comment: comment || undefined },
              { onSuccess: resetSelection },
            )
          }}
          onDismiss={() => {
            resetSelection()
            createBooking.reset()
          }}
        />
      )}
    </div>
  )
}
