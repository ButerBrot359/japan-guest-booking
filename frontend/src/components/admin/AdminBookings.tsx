import { useState } from 'react'
import { ApiError } from '../../api/client'
import {
  useAdminBookings, useAdminCancelBooking, useAdminRescheduleBooking, useCalendar,
} from '../../api/queries'
import type { AdminBookingRow, CalendarDay } from '../../api/types'
import { addMonths, isoRange, isoToRu, nightsBetween, todayIso } from '../../lib/dates'
import { type Selection } from '../Calendar'
import { DateRangePicker } from './DateRangePicker'

/** Дни самой переносимой брони делаем свободными — чтобы можно было сдвинуть её на пересекающийся диапазон */
function daysWithoutBooking(days: Map<string, CalendarDay>, b: AdminBookingRow): Map<string, CalendarDay> {
  const m = new Map(days)
  for (const d of [...isoRange(b.checkIn, b.checkOut), b.checkOut]) m.delete(d)
  return m
}

export function AdminBookings() {
  const bookings = useAdminBookings()
  const cancel = useAdminCancelBooking()
  const reschedule = useAdminRescheduleBooking()
  const yearFrom = todayIso().slice(0, 7) + '-01'
  const yearTo = addMonths(yearFrom, 12)
  const calendar = useCalendar(yearFrom, yearTo)

  const [confirmCancel, setConfirmCancel] = useState<AdminBookingRow | null>(null)
  const [rescheduling, setRescheduling] = useState<AdminBookingRow | null>(null)
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })

  const today = todayIso()
  // ленивый COMPLETED: у прошедших броней статус в БД может остаться CONFIRMED — отсекаем по дате
  const active = (bookings.data ?? []).filter((b) => b.status === 'CONFIRMED' && b.checkOut >= today)
  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))

  const openCancel = (b: AdminBookingRow) => { cancel.reset(); setConfirmCancel(b) }
  const openReschedule = (b: AdminBookingRow) => {
    reschedule.reset()
    setSelection({ checkIn: null, checkOut: null })
    setRescheduling(b)
  }

  const submitCancel = () => {
    if (!confirmCancel) return
    cancel.mutate(confirmCancel.id, {
      onSuccess: () => setConfirmCancel(null),
      onError: (e) => {
        // уже отменена — список обновится через onSettled, просто закрываем
        if (e instanceof ApiError && e.code === 'BOOKING_EXPIRED') setConfirmCancel(null)
      },
    })
  }
  const cancelFailed = cancel.error != null &&
    !(cancel.error instanceof ApiError && cancel.error.code === 'BOOKING_EXPIRED')

  const rescheduleErrorText =
    reschedule.error instanceof ApiError
      ? reschedule.error.code === 'DATES_TAKEN'
        ? 'Эти даты заняты — выберите другие.'
        : reschedule.error.code === 'VALIDATION_ERROR'
          ? 'Даты в прошлом или некорректны.'
          : 'Не удалось перенести — попробуйте ещё раз.'
      : reschedule.error ? 'Не удалось перенести — попробуйте ещё раз.' : null

  return (
    <div>
      {active.length === 0 && !bookings.isLoading && (
        <p className="text-sm text-muted">Активных броней нет</p>
      )}
      {active.length > 0 && (
        <table data-testid="admin-bookings" className="w-full text-left text-sm">
          <thead className="text-xs text-muted">
            <tr><th className="py-1">Гость</th><th>Телефон</th><th>Даты</th><th>Ночей</th><th>Комментарий</th><th></th></tr>
          </thead>
          <tbody>
            {active.map((b) => (
              <tr key={b.id} className="border-t border-muted/20">
                <td className="py-2">{b.guestName}</td>
                <td>{b.guestPhone}</td>
                <td>{isoToRu(b.checkIn)} → {isoToRu(b.checkOut)}</td>
                <td>{nightsBetween(b.checkIn, b.checkOut)}</td>
                <td title={b.comment ?? undefined} className="max-w-[14rem] truncate text-muted">
                  {b.comment ?? '—'}
                </td>
                <td className="py-2 text-right text-xs">
                  <span className="flex justify-end gap-2">
                    <button type="button" className="text-muted" onClick={() => openReschedule(b)}>Перенести</button>
                    <button type="button" className="text-hanko" onClick={() => openCancel(b)}>Отменить</button>
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirmCancel && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">
              Отменить бронь гостя {confirmCancel.guestName}{' '}
              ({isoToRu(confirmCancel.checkIn)} → {isoToRu(confirmCancel.checkOut)})?
              Гость получит уведомление.
            </p>
            {cancelFailed && <p className="mb-2 text-xs text-hanko">Не удалось отменить — попробуйте ещё раз.</p>}
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setConfirmCancel(null)}>Отмена</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper disabled:opacity-50"
                disabled={cancel.isPending} onClick={submitCancel}>Да, отменить</button>
            </div>
          </div>
        </div>
      )}

      {rescheduling && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-1 font-display">Перенос брони — {rescheduling.guestName}</p>
            <p className="mb-3 text-xs text-muted">
              Сейчас: {isoToRu(rescheduling.checkIn)} → {isoToRu(rescheduling.checkOut)}. Выбери новые даты.
            </p>
            <DateRangePicker
              days={daysWithoutBooking(days, rescheduling)}
              value={selection}
              onChange={setSelection}
              pickOptions={{ maxNights: Infinity }}
              minMonth={yearFrom}
            />
            {rescheduleErrorText && <p className="mt-2 text-xs text-hanko">{rescheduleErrorText}</p>}
            <div className="mt-3 flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setRescheduling(null)}>Отмена</button>
              <button type="button" className="flex-1 rounded-lg bg-ink py-2 text-paper disabled:opacity-50"
                disabled={!selection.checkIn || !selection.checkOut || reschedule.isPending}
                onClick={() => reschedule.mutate(
                  { id: rescheduling.id, checkIn: selection.checkIn!, checkOut: selection.checkOut! },
                  { onSuccess: () => setRescheduling(null) },
                )}>
                Перенести бронь
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
