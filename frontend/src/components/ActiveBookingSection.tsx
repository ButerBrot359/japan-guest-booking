import { useState } from 'react'
import { ApiError } from '../api/client'
import { useCancelBooking, useMyBookings, useUpdateComment } from '../api/queries'
import { isoToRu } from '../lib/dates'

export function ActiveBookingSection({ onReschedule }: { onReschedule: () => void }) {
  const bookings = useMyBookings()
  const update = useUpdateComment()
  const cancel = useCancelBooking()
  const [confirming, setConfirming] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')

  const active = bookings.data?.active ?? null
  if (active == null) {
    return (
      <div className="rounded-2xl bg-card p-3 text-xs text-muted">
        Брони пока нет — выбери даты в календаре.
      </div>
    )
  }

  return (
    <div>
      <div className="rounded-2xl border border-leaf/40 bg-leafbg p-3 text-sm">
        <b>{isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}</b>{' '}
        <span className="rounded-md bg-leaf px-1.5 py-0.5 text-[10px] text-paper">подтверждена</span>
        <div className="mt-2 flex gap-2 text-xs">
          <button type="button" className="flex-1 rounded-lg border border-ink py-1.5"
            onClick={onReschedule}>Перенести</button>
          <button type="button" className="flex-1 rounded-lg border border-hanko py-1.5 text-hanko"
            onClick={() => { cancel.reset(); setConfirming(true) }}>Отменить</button>
        </div>
      </div>

      <div className="mt-3 rounded-2xl bg-card p-3 text-sm">
        {!editing && (
          <div className="flex items-start justify-between gap-2">
            <p className="text-muted">{active.comment ?? 'Без комментария'}</p>
            <button type="button" aria-label="изменить комментарий"
              onClick={() => { setDraft(active.comment ?? ''); setEditing(true) }}>✏️</button>
          </div>
        )}
        {editing && (
          <div>
            <textarea aria-label="Комментарий" maxLength={500}
              className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-base lg:text-sm"
              value={draft} onChange={(e) => setDraft(e.target.value)} />
            <div className="mt-2 flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-1.5 disabled:opacity-50"
                disabled={update.isPending}
                onClick={() => update.mutate(draft.trim() === '' ? null : draft.trim(),
                  { onSuccess: () => setEditing(false) })}>
                Сохранить
              </button>
              <button type="button" className="flex-1 rounded-lg border border-muted/40 py-1.5"
                onClick={() => setEditing(false)}>Отмена</button>
            </div>
          </div>
        )}
      </div>

      {confirming && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">Отменить бронь {isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}?</p>
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setConfirming(false)}>Оставить</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper disabled:opacity-50"
                disabled={cancel.isPending}
                onClick={() => cancel.mutate(active.id, { onSuccess: () => setConfirming(false) })}>
                Да, отменить
              </button>
            </div>
            {cancel.error instanceof ApiError && (
              <p className="mt-2 text-xs text-hanko">Не получилось — попробуй ещё раз.</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
