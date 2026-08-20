import { useState } from 'react'
import { ApiError } from './api/client'
import {
  useCalendar, useCancelBooking, useCancelPending, useCreateBooking,
  useMe, useRescheduleBooking,
} from './api/queries'
import { BookingSheet } from './components/BookingSheet'
import { Calendar, type Selection } from './components/Calendar'
import { LoginCard } from './components/LoginCard'
import { OtpModal } from './components/OtpModal'
import { ProfileCard } from './components/ProfileCard'
import { addMonths, isoRange, isoToRu, todayIso } from './lib/dates'
import { pickDay } from './lib/selection'

type Flow =
  | { kind: 'idle' }
  | { kind: 'selecting-reschedule' }
  | { kind: 'otp'; bookingId: number; subtitle: string; cancelable: boolean }
  | { kind: 'confirm-cancel' }

export default function App() {
  const [monthStart, setMonthStart] = useState(todayIso().slice(0, 7) + '-01')
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })
  const [flow, setFlow] = useState<Flow>({ kind: 'idle' })

  const me = useMe()
  const calendar = useCalendar(monthStart, addMonths(monthStart, 2))
  const create = useCreateBooking()
  const reschedule = useRescheduleBooking()
  const cancel = useCancelBooking()
  const cancelPending = useCancelPending()

  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))
  const active = me.data?.activeBooking ?? null
  const bothPicked = selection.checkIn != null && selection.checkOut != null

  const checkoutCandidates = (() => {
    if (!selection.checkIn || selection.checkOut) return undefined
    for (const iso of isoRange(selection.checkIn, addMonths(monthStart, 2))) {
      if (iso > selection.checkIn && (days.get(iso)?.status ?? 'FREE') !== 'FREE') return new Set([iso])
    }
    return undefined
  })()

  const resetSelection = () => setSelection({ checkIn: null, checkOut: null })

  // смена режима — прошлые ошибки неактуальны
  const switchFlow = (next: Flow) => {
    create.reset()
    reschedule.reset()
    resetSelection()
    setFlow(next)
  }

  const submitBooking = (comment: string) => {
    if (!selection.checkIn || !selection.checkOut) return
    if (flow.kind === 'selecting-reschedule' && active) {
      reschedule.mutate(
        { bookingId: active.id, checkIn: selection.checkIn, checkOut: selection.checkOut },
        {
          onSuccess: () => {
            resetSelection()
            setFlow({ kind: 'otp', bookingId: active.id, subtitle: `перенос на ${isoToRu(selection.checkIn!)} → ${isoToRu(selection.checkOut!)}`, cancelable: false })
          },
        },
      )
    } else {
      create.mutate(
        { checkIn: selection.checkIn, checkOut: selection.checkOut, comment: comment || undefined },
        {
          onSuccess: (r) => {
            resetSelection()
            setFlow({ kind: 'otp', bookingId: r.bookingId, subtitle: `заезд ${isoToRu(selection.checkIn!)} → выезд ${isoToRu(selection.checkOut!)}`, cancelable: true })
          },
        },
      )
    }
  }

  const sheetError = (flow.kind === 'selecting-reschedule' ? reschedule.error : create.error)
  const sheetErrorCode = sheetError instanceof ApiError ? sheetError.code : null

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5 pb-40">
      <header className="mb-4">
        <h1 className="font-display text-lg">
          Домик в Японии <span className="text-hanko">◉</span>
        </h1>
      </header>

      {me.data == null && !me.isLoading && <LoginCard />}
      {me.data != null && (
        <ProfileCard
          me={me.data}
          onReschedule={() => switchFlow({ kind: 'selecting-reschedule' })}
          onCancel={() => {
            // открываем диалог отмены заново — прошлая ошибка неактуальна
            cancel.reset()
            setFlow({ kind: 'confirm-cancel' })
          }}
          onEnterCode={() => active && setFlow({ kind: 'otp', bookingId: active.id, subtitle: `заезд ${isoToRu(active.checkIn)} → выезд ${isoToRu(active.checkOut)}`, cancelable: true })}
          onCancelPending={() => cancelPending.mutate()}
        />
      )}

      {flow.kind === 'selecting-reschedule' && (
        <p className="mb-2 rounded-lg bg-card p-2 text-xs text-muted">
          Перенос: выбери новые даты в календаре.{' '}
          <button type="button" className="text-hanko" onClick={() => switchFlow({ kind: 'idle' })}>Передумал</button>
        </p>
      )}

      <Calendar
        monthStart={monthStart}
        days={days}
        selection={selection}
        selectable={me.data != null}
        checkoutCandidates={checkoutCandidates}
        onShiftMonth={(d) => setMonthStart((m) => addMonths(m, d))}
        onPick={(iso) => setSelection((s) => pickDay(s, iso, days))}
      />

      {bothPicked && me.data != null && (flow.kind === 'idle' || flow.kind === 'selecting-reschedule') && (
        <BookingSheet
          selection={{ checkIn: selection.checkIn!, checkOut: selection.checkOut! }}
          willReplace={flow.kind === 'idle' && active?.status === 'CONFIRMED' ? active : null}
          onSubmit={submitBooking}
          onDismiss={() => { resetSelection(); create.reset(); reschedule.reset() }}
          pending={create.isPending || reschedule.isPending}
          errorCode={sheetErrorCode}
        />
      )}

      {flow.kind === 'confirm-cancel' && active && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">Отменить бронь {isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}?</p>
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setFlow({ kind: 'idle' })}>Оставить</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper disabled:opacity-50"
                disabled={cancel.isPending}
                onClick={() => cancel.mutate(active.id, {
                  onSuccess: () => setFlow({ kind: 'otp', bookingId: active.id, subtitle: 'отмена брони', cancelable: false }),
                })}>
                Да, отменить
              </button>
            </div>
            {cancel.error instanceof ApiError && (
              <p className="mt-2 text-xs text-hanko">Не получилось — попробуй ещё раз.</p>
            )}
          </div>
        </div>
      )}

      {flow.kind === 'otp' && (
        <OtpModal
          bookingId={flow.bookingId}
          subtitle={flow.subtitle}
          showCancelPending={flow.cancelable}
          onDone={() => setFlow({ kind: 'idle' })}
          onClose={() => setFlow({ kind: 'idle' })}
        />
      )}
    </div>
  )
}
