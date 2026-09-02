import { useState } from 'react'
import { ApiError } from '../../api/client'
import {
  useAdminBlockedPeriods, useAdminCreateBlockedPeriod, useAdminDeleteBlockedPeriod, useCalendar,
} from '../../api/queries'
import type { BlockedPeriodRow } from '../../api/types'
import { addMonths, isoToRu, todayIso } from '../../lib/dates'
import { type Selection } from '../Calendar'
import { DateRangePicker } from './DateRangePicker'

const noSelection: Selection = { checkIn: null, checkOut: null }

export function AdminBlockedPeriods() {
  const periods = useAdminBlockedPeriods()
  const create = useAdminCreateBlockedPeriod()
  const del = useAdminDeleteBlockedPeriod()
  const yearFrom = todayIso().slice(0, 7) + '-01'
  const yearTo = addMonths(yearFrom, 12)
  const calendar = useCalendar(yearFrom, yearTo)

  const [selection, setSelection] = useState<Selection>(noSelection)
  const [reason, setReason] = useState('')
  const [confirmDelete, setConfirmDelete] = useState<BlockedPeriodRow | null>(null)

  // занятые и уже закрытые дни disabled: поверх брони бэкенд отвергнет, поверх блокировки бессмысленно
  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))

  const submit = () => {
    if (!selection.checkIn || !selection.checkOut) return
    create.mutate(
      { startDate: selection.checkIn, endDate: selection.checkOut, reason: reason.trim() || undefined },
      { onSuccess: () => { setSelection(noSelection); setReason('') } },
    )
  }

  const createErrorText =
    create.error instanceof ApiError && create.error.code === 'OVERLAPS_BOOKING'
      ? 'На эти даты есть бронь — сначала перенесите или отмените её.'
      : create.error ? 'Не удалось закрыть даты — попробуйте ещё раз.' : null

  return (
    <div>
      <div className="mb-5 rounded-xl bg-card p-3">
        <p className="mb-2 text-sm">Закрыть даты для гостей (ремонт, свои поездки):</p>
        <DateRangePicker
          days={days}
          value={selection}
          onChange={setSelection}
          pickOptions={{ maxNights: Infinity, allowSingleDay: true }}
          minMonth={yearFrom}
        />
        <div className="mt-3 flex items-center gap-2">
          <input aria-label="Причина" maxLength={200}
            className="flex-1 rounded-lg border border-muted/40 bg-paper p-2 text-sm"
            placeholder="Причина (не обязательно)"
            value={reason} onChange={(e) => setReason(e.target.value)} />
          <button type="button" className="rounded-lg bg-ink px-3 py-2 text-sm text-paper disabled:opacity-50"
            disabled={!selection.checkIn || !selection.checkOut || create.isPending}
            onClick={submit}>
            Закрыть даты
          </button>
        </div>
        {createErrorText && <p className="mt-2 text-xs text-hanko">{createErrorText}</p>}
      </div>

      {(periods.data ?? []).length === 0 && !periods.isLoading && (
        <p className="text-sm text-muted">Закрытых дат нет</p>
      )}
      {(periods.data ?? []).length > 0 && (
        <table data-testid="admin-blocked" className="w-full text-left text-sm">
          <thead className="text-xs text-muted">
            <tr><th className="py-1">Даты</th><th>Причина</th><th></th></tr>
          </thead>
          <tbody>
            {(periods.data ?? []).map((p) => (
              <tr key={p.id} className="border-t border-muted/20">
                <td className="py-2">
                  {p.startDate === p.endDate
                    ? isoToRu(p.startDate)
                    : `${isoToRu(p.startDate)} → ${isoToRu(p.endDate)}`}
                </td>
                <td className="text-muted">{p.reason ?? '—'}</td>
                <td className="py-2 text-right text-xs">
                  <button type="button" className="text-hanko" onClick={() => setConfirmDelete(p)}>Удалить</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirmDelete && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">
              Снова открыть даты{' '}
              {confirmDelete.startDate === confirmDelete.endDate
                ? isoToRu(confirmDelete.startDate)
                : `${isoToRu(confirmDelete.startDate)} → ${isoToRu(confirmDelete.endDate)}`} для гостей?
            </p>
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setConfirmDelete(null)}>Отмена</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper disabled:opacity-50"
                disabled={del.isPending}
                onClick={() => del.mutate(confirmDelete.id, { onSuccess: () => setConfirmDelete(null) })}>
                Да, удалить
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
