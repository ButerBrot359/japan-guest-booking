import type { Me } from '../api/types'
import { useLogout } from '../api/queries'
import { isoToRu } from '../lib/dates'
import { TelegramHint } from './TelegramHint'

interface ProfileCardProps {
  me: Me
  onReschedule: () => void
  onCancel: () => void
}

export function ProfileCard({ me, onReschedule, onCancel }: ProfileCardProps) {
  const logout = useLogout()
  const b = me.activeBooking
  return (
    <div className="mb-4">
      <div className="mb-1 flex items-center justify-between text-xs text-muted">
        <span>{me.phone}</span>
        <button type="button" onClick={() => logout.mutate()}>выйти</button>
      </div>
      <p className="mb-2 font-display text-base leading-snug">
        {me.greeting ?? `Привет, ${me.name}!`}
      </p>
      {!me.telegramLinked && <TelegramHint />}
      {b == null && (
        <div className="rounded-2xl bg-card p-3 text-xs text-muted">
          Брони пока нет — выбери даты в календаре ниже.
        </div>
      )}
      {b?.status === 'CONFIRMED' && (
        <div className="rounded-2xl border border-leaf/40 bg-leafbg p-3 text-sm">
          Твоя бронь: <b>{isoToRu(b.checkIn)} → {isoToRu(b.checkOut)}</b>{' '}
          <span className="rounded-md bg-leaf px-1.5 py-0.5 text-[10px] text-paper">подтверждена</span>
          <div className="mt-2 flex gap-2 text-xs">
            <button type="button" onClick={onReschedule}
              className="flex-1 rounded-lg border border-ink py-1.5">Перенести</button>
            <button type="button" onClick={onCancel}
              className="flex-1 rounded-lg border border-hanko py-1.5 text-hanko">Отменить</button>
          </div>
        </div>
      )}
    </div>
  )
}
