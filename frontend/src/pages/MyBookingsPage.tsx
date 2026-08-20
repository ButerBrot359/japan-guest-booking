import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { useCancelBooking, useCancelPending, useMe, useMyBookings, useUpdateComment } from '../api/queries'
import { Header } from '../components/Header'
import { OtpModal } from '../components/OtpModal'
import { isoToRu, nightsWord } from '../lib/dates'

type Flow = 'idle' | 'confirm-cancel' | 'otp'

export function MyBookingsPage() {
  const me = useMe()
  const bookings = useMyBookings()
  const update = useUpdateComment()
  const cancel = useCancelBooking()
  const cancelPending = useCancelPending()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const [tab, setTab] = useState<'active' | 'history'>('active')
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [flow, setFlow] = useState<Flow>('idle')

  // прямой заход анонима на /my-bookings — вкладка скрыта в шапке, но URL открыт
  if (me.data == null && !me.isLoading) return <Navigate to="/" replace />

  const active = bookings.data?.active ?? null
  const history = bookings.data?.history ?? []

  const startEditing = () => {
    setDraft(active?.comment ?? '')
    setEditing(true)
  }

  const saveComment = () => {
    update.mutate(draft.trim() === '' ? null : draft.trim(), { onSuccess: () => setEditing(false) })
  }

  const closeOtp = () => {
    setFlow('idle')
    qc.invalidateQueries({ queryKey: ['my-bookings'] })
  }

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5 pb-40">
      <Header me={me.data ?? null} />
      <h2 className="mb-3 font-display text-lg">Мои брони</h2>

      <div className="mb-4 flex gap-4 border-b border-muted/30 text-sm">
        <button
          type="button"
          aria-pressed={tab === 'active'}
          className={`pb-2 ${tab === 'active' ? 'border-b-2 border-leaf font-semibold' : 'text-muted'}`}
          onClick={() => setTab('active')}
        >
          Активная
        </button>
        <button
          type="button"
          aria-pressed={tab === 'history'}
          className={`pb-2 ${tab === 'history' ? 'border-b-2 border-leaf font-semibold' : 'text-muted'}`}
          onClick={() => setTab('history')}
        >
          История
        </button>
      </div>

      {tab === 'active' && (
        <>
          {active == null && (
            <div className="rounded-2xl bg-card p-3 text-xs text-muted">
              Брони пока нет — выбери даты в календаре.{' '}
              <Link to="/" className="text-hanko underline-offset-4 hover:underline">К календарю</Link>
            </div>
          )}

          {active?.status === 'CONFIRMED' && (
            <div className="rounded-2xl border border-leaf/40 bg-leafbg p-3 text-sm">
              <b>{isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}</b>{' '}
              <span className="rounded-md bg-leaf px-1.5 py-0.5 text-[10px] text-paper">подтверждена</span>
              <div className="mt-2 flex gap-2 text-xs">
                <button
                  type="button"
                  className="flex-1 rounded-lg border border-ink py-1.5"
                  onClick={() => navigate('/', { state: { startReschedule: true } })}
                >
                  Перенести
                </button>
                <button
                  type="button"
                  className="flex-1 rounded-lg border border-hanko py-1.5 text-hanko"
                  onClick={() => { cancel.reset(); setFlow('confirm-cancel') }}
                >
                  Отменить
                </button>
              </div>
            </div>
          )}

          {active?.status === 'PENDING_OTP' && (
            <div className="rounded-2xl border border-warn-border bg-warn-bg p-3 text-sm">
              {isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}{' '}
              <span className="rounded-md bg-warn-badge px-1.5 py-0.5 text-[10px] text-paper">ждёт код</span>
              <div className="mt-2 flex gap-2 text-xs">
                <button
                  type="button"
                  className="flex-1 rounded-lg border border-ink py-1.5"
                  onClick={() => setFlow('otp')}
                >
                  Ввести код
                </button>
                <button
                  type="button"
                  className="flex-1 rounded-lg border border-hanko py-1.5 text-hanko"
                  onClick={() => cancelPending.mutate(undefined, {
                    onSuccess: () => qc.invalidateQueries({ queryKey: ['my-bookings'] }),
                  })}
                >
                  Отменить
                </button>
              </div>
            </div>
          )}

          {active != null && (
            <div className="mt-3 rounded-2xl bg-card p-3 text-sm">
              {!editing && (
                <div className="flex items-start justify-between gap-2">
                  <p className="text-muted">{active.comment ?? 'Без комментария'}</p>
                  <button type="button" aria-label="изменить комментарий" onClick={startEditing}>✏️</button>
                </div>
              )}
              {editing && (
                <div>
                  <textarea
                    aria-label="Комментарий"
                    maxLength={500}
                    className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-sm"
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                  />
                  <div className="mt-2 flex gap-2 text-xs">
                    <button
                      type="button"
                      className="flex-1 rounded-lg border border-ink py-1.5 disabled:opacity-50"
                      disabled={update.isPending}
                      onClick={saveComment}
                    >
                      Сохранить
                    </button>
                    <button
                      type="button"
                      className="flex-1 rounded-lg border border-muted/40 py-1.5"
                      onClick={() => setEditing(false)}
                    >
                      Отмена
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {tab === 'history' && (
        <div className="space-y-2">
          {history.length === 0 && <p className="text-xs text-muted">Пока нет завершённых поездок.</p>}
          {history.map((v, i) => (
            <div key={i} className="rounded-xl bg-card p-2.5 text-sm">
              {isoToRu(v.checkIn)} → {isoToRu(v.checkOut)} · {nightsWord(v.nights)}
            </div>
          ))}
        </div>
      )}

      {flow === 'confirm-cancel' && active && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">Отменить бронь {isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}?</p>
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setFlow('idle')}>Оставить</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper disabled:opacity-50"
                disabled={cancel.isPending}
                onClick={() => cancel.mutate(active.id, { onSuccess: () => setFlow('otp') })}>
                Да, отменить
              </button>
            </div>
            {cancel.error instanceof ApiError && (
              <p className="mt-2 text-xs text-hanko">Не получилось — попробуй ещё раз.</p>
            )}
          </div>
        </div>
      )}

      {flow === 'otp' && active && (
        <OtpModal
          bookingId={active.id}
          subtitle={active.status === 'PENDING_OTP'
            ? `заезд ${isoToRu(active.checkIn)} → выезд ${isoToRu(active.checkOut)}`
            : 'отмена брони'}
          showCancelPending={active.status === 'PENDING_OTP'}
          onDone={closeOtp}
          onClose={closeOtp}
        />
      )}
    </div>
  )
}
