import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import {
  useCalendar, useCreateBooking,
  useMe, useRescheduleBooking,
} from '../api/queries'
import { ActiveBookingSection } from '../components/ActiveBookingSection'
import { BookingSheet } from '../components/BookingSheet'
import { Calendar, type Selection } from '../components/Calendar'
import { Greeting } from '../components/Greeting'
import { Header } from '../components/Header'
import { HistoryList } from '../components/HistoryList'
import { LoginModal } from '../components/LoginModal'
import { TabBar } from '../components/TabBar'
import { addDays, addMonths, isoToRu, todayIso } from '../lib/dates'
import { pickDay } from '../lib/selection'

type Flow =
  | { kind: 'idle' }
  | { kind: 'selecting-reschedule' }
  | { kind: 'celebrate'; checkIn: string; checkOut: string }

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

  // вход завершился — закрываем модалку; если вход начался с клика по дате,
  // досрочно выбираем её как заезд
  useEffect(() => {
    if (me.data == null) return
    setLoginOpen(false)
    if (pendingDate != null) {
      setSelection({ checkIn: pendingDate, checkOut: null })
      setPendingDate(null)
    }
  }, [me.data, pendingDate])

  // iOS: коммент-инпут в шторке открывал клавиатуру и уводил скролл вниз —
  // fixed-модалка «Мы очень вас ждём» из-за этого рендерилась далеко внизу и
  // кнопка «Хорошо» была недостижима. Гасим фокус (клавиатуру) и возвращаем скролл.
  useEffect(() => {
    if (flow.kind !== 'celebrate') return
    const active = document.activeElement
    if (active instanceof HTMLElement) active.blur()
    // scrollTop, а не window.scrollTo: и Safari (body), и остальные (documentElement)
    document.documentElement.scrollTop = 0
    document.body.scrollTop = 0
  }, [flow.kind])

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
  // сканируем от кликнутого дня влево/вправо, пока имя совпадает; от этапа 6.6
  // все дни брони (от заезда до выезда включительно) помечены guestName
  const handlePickBusy = (iso: string) => {
    const name = days.get(iso)?.guestName
    if (!name) return
    let from = iso
    while (days.get(addDays(from, -1))?.guestName === name) from = addDays(from, -1)
    let last = iso
    while (days.get(addDays(last, 1))?.guestName === name) last = addDays(last, 1)
    setGuestInfo({ name, from, to: last })
  }

  const submitBooking = (comment: string) => {
    if (!selection.checkIn || !selection.checkOut) return
    if (flow.kind === 'selecting-reschedule' && active) {
      reschedule.mutate(
        { bookingId: active.id, checkIn: selection.checkIn, checkOut: selection.checkOut },
        {
          onSuccess: () => {
            const checkIn = selection.checkIn!
            const checkOut = selection.checkOut!
            resetSelection()
            setFlow({ kind: 'celebrate', checkIn, checkOut })
          },
        },
      )
    } else {
      create.mutate(
        { checkIn: selection.checkIn, checkOut: selection.checkOut, comment: comment || undefined },
        {
          onSuccess: () => {
            const checkIn = selection.checkIn!
            const checkOut = selection.checkOut!
            resetSelection()
            setFlow({ kind: 'celebrate', checkIn, checkOut })
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
      me.data != null
        ? 'lg:max-w-[90rem] lg:grid lg:grid-cols-[minmax(0,1fr)_22rem] lg:gap-0 lg:px-10'
        : 'lg:max-w-[90rem] lg:px-10',
      bothPicked ? 'pb-40' : 'pb-8',
    ].join(' ')}>
      <div className="lg:col-span-2">
        <Header me={me.data ?? null} onLoginClick={loginOpen ? undefined : () => setLoginOpen(true)} />
      </div>

      {me.data != null && (
        <div className="hidden lg:order-2 lg:block lg:self-stretch lg:border-l lg:border-muted/30 lg:pl-8 lg:ml-8">
          <div className="lg:sticky lg:top-6">
            <Greeting me={me.data} />
            <ActiveBookingSection onReschedule={() => switchFlow({ kind: 'selecting-reschedule' })} />
            <HistoryList />
          </div>
        </div>
      )}

      <div>
        {me.data != null && (
          <div className="lg:hidden">
            <Greeting me={me.data} />
            <TabBar />
          </div>
        )}

        {flow.kind === 'selecting-reschedule' && (
          <div className="mb-2 flex items-center justify-between gap-2 rounded-lg bg-card p-2 text-xs text-muted">
            <span>Перенос: выбери новые даты в календаре.</span>
            <button type="button"
              className="shrink-0 rounded-lg border border-hanko px-3 py-1.5 text-hanko"
              onClick={() => switchFlow({ kind: 'idle' })}>
              Передумал
            </button>
          </div>
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
            maxCheckout={maxCheckout}
            onPick={handlePick}
            onPickBusy={handlePickBusy}
          />
        </div>
      </div>

      <LoginModal
        open={loginOpen}
        title={pendingDate != null ? 'Войдите, чтобы выбрать даты' : 'Вход'}
        onClose={() => setLoginOpen(false)}
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

      {flow.kind === 'celebrate' && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-2 text-center font-display text-base">Мы очень вас ждём!</p>
            <p className="mb-3 text-center text-muted">{isoToRu(flow.checkIn)} → {isoToRu(flow.checkOut)}</p>
            <p className="mb-3 text-xs text-muted">
              Ближе к дате мы свяжемся с вами, чтобы уточнить детали поездки.
            </p>
            <button type="button" className="w-full rounded-xl bg-ink py-2 text-sm text-paper"
              onClick={() => setFlow({ kind: 'idle' })}>
              Хорошо
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
