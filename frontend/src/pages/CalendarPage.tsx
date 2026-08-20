import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import {
  useCalendar, useCancelBooking, useCancelPending, useCreateBooking,
  useMe, useRescheduleBooking,
} from '../api/queries'
import { BookingSheet } from '../components/BookingSheet'
import { Calendar, type Selection } from '../components/Calendar'
import { Header } from '../components/Header'
import { LoginModal } from '../components/LoginModal'
import { OtpModal } from '../components/OtpModal'
import { ProfileCard } from '../components/ProfileCard'
import { addDays, addMonths, isoRange, isoToRu, todayIso } from '../lib/dates'
import { pickDay } from '../lib/selection'

type Flow =
  | { kind: 'idle' }
  | { kind: 'selecting-reschedule' }
  | { kind: 'otp'; bookingId: number; subtitle: string; cancelable: boolean }
  | { kind: 'confirm-cancel' }

export function CalendarPage() {
  const yearFrom = todayIso().slice(0, 7) + '-01'
  const yearTo = addMonths(yearFrom, 12)
  const mobileShiftMax = addMonths(yearFrom, 10)
  const [monthStart, setMonthStart] = useState(yearFrom)
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })
  const [flow, setFlow] = useState<Flow>({ kind: 'idle' })
  const [pendingDate, setPendingDate] = useState<string | null>(null)
  const [loginOpen, setLoginOpen] = useState(false)
  const [guestInfo, setGuestInfo] = useState<{ name: string; from: string; to: string } | null>(null)
  const location = useLocation()
  const navigate = useNavigate()

  const me = useMe()
  const calendar = useCalendar(yearFrom, yearTo)
  const create = useCreateBooking()
  const reschedule = useRescheduleBooking()
  const cancel = useCancelBooking()
  const cancelPending = useCancelPending()

  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))
  const active = me.data?.activeBooking ?? null
  const bothPicked = selection.checkIn != null && selection.checkOut != null

  const desktopMonths = Array.from({ length: 12 }, (_, i) => addMonths(yearFrom, i))
  const mobileMonths = [monthStart, addMonths(monthStart, 1)]

  const shiftMonth = (delta: 1 | -1) =>
    setMonthStart((m) => {
      const next = addMonths(m, delta)
      if (next < yearFrom) return yearFrom
      if (next > mobileShiftMax) return mobileShiftMax
      return next
    })

  const checkoutCandidates = (() => {
    if (!selection.checkIn || selection.checkOut) return undefined
    for (const iso of isoRange(selection.checkIn, yearTo)) {
      if (iso > selection.checkIn && (days.get(iso)?.status ?? 'FREE') !== 'FREE') return new Set([iso])
    }
    return undefined
  })()
  // зеркалит бэкенд-лимит 14 ночей (RANGE_TOO_LONG) — пока выбирается выезд
  const maxCheckout = selection.checkIn && !selection.checkOut ? addDays(selection.checkIn, 14) : undefined

  const resetSelection = () => setSelection({ checkIn: null, checkOut: null })

  // смена режима — прошлые ошибки неактуальны
  const switchFlow = (next: Flow) => {
    create.reset()
    reschedule.reset()
    resetSelection()
    setFlow(next)
  }

  useEffect(() => {
    if ((location.state as { startReschedule?: boolean } | null)?.startReschedule) {
      switchFlow({ kind: 'selecting-reschedule' })
      navigate('.', { replace: true, state: null })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // вход завершился, пока была кликнута дата — досрочно выбираем её как заезд
  useEffect(() => {
    if (me.data != null && pendingDate != null) {
      setSelection({ checkIn: pendingDate, checkOut: null })
      setPendingDate(null)
      setLoginOpen(false)
    }
  }, [me.data, pendingDate])

  const handlePick = (iso: string) => {
    // /api/me ещё грузится — не мигаем модалкой уже залогиненному, просто игнорируем клик
    if (me.isLoading) return
    if (me.data == null) {
      setPendingDate(iso)
      setLoginOpen(true)
      return
    }
    setGuestInfo(null)
    setSelection((s) => pickDay(s, iso, days))
  }

  // клик по чужому занятому дню с известным именем — «кто гостит»:
  // сканируем от кликнутого дня влево/вправо, пока имя совпадает; выезд — день
  // после последнего занятого дня (полуинтервал)
  const handlePickBusy = (iso: string) => {
    const name = days.get(iso)?.guestName
    if (!name) return
    let from = iso
    while (days.get(addDays(from, -1))?.guestName === name) from = addDays(from, -1)
    let last = iso
    while (days.get(addDays(last, 1))?.guestName === name) last = addDays(last, 1)
    setGuestInfo({ name, from, to: addDays(last, 1) })
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
    <div className={[
      'mx-auto max-w-md min-h-dvh bg-paper px-4 py-5',
      'lg:max-w-[90rem] lg:grid lg:grid-cols-[minmax(0,1fr)_22rem] lg:gap-10 lg:px-10',
      bothPicked ? 'pb-40' : 'pb-8',
    ].join(' ')}>
      <div className="lg:col-span-2">
        <Header me={me.data ?? null} onLoginClick={loginOpen ? undefined : () => setLoginOpen(true)} />
      </div>

      <div className="lg:order-2 lg:sticky lg:top-6 lg:self-start">
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
      </div>

      <div>
        {flow.kind === 'selecting-reschedule' && (
          <p className="mb-2 rounded-lg bg-card p-2 text-xs text-muted">
            Перенос: выбери новые даты в календаре.{' '}
            <button type="button" className="text-hanko" onClick={() => switchFlow({ kind: 'idle' })}>Передумал</button>
          </p>
        )}

        {guestInfo && (
          <p className="mb-2 rounded-lg bg-card p-2 text-xs text-muted">
            Гостит {guestInfo.name} · {isoToRu(guestInfo.from)} → {isoToRu(guestInfo.to)}{' '}
            <button type="button" className="text-hanko" onClick={() => setGuestInfo(null)}>Закрыть</button>
          </p>
        )}

        <div className="lg:hidden">
          <Calendar
            months={mobileMonths}
            days={days}
            selection={selection}
            selectable
            checkoutCandidates={checkoutCandidates}
            maxCheckout={maxCheckout}
            onShiftMonth={shiftMonth}
            onPick={handlePick}
            onPickBusy={handlePickBusy}
          />
        </div>
        <div className="hidden lg:block">
          <Calendar
            months={desktopMonths}
            days={days}
            selection={selection}
            selectable
            checkoutCandidates={checkoutCandidates}
            maxCheckout={maxCheckout}
            onPick={handlePick}
            onPickBusy={handlePickBusy}
          />
        </div>
      </div>

      <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />

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
