import { useState } from 'react'
import type { ActiveBooking } from '../api/types'
import { isoToRu, nightsBetween } from '../lib/dates'

const ERROR_TEXTS: Record<string, string> = {
  DATES_TAKEN: 'Эти даты только что заняли — выбери другие.',
  OVERLAPS_OWN_BOOKING: 'Эти даты пересекаются с твоей текущей бронью.',
  TELEGRAM_NOT_LINKED: 'Сначала привяжи Telegram — напиши боту и поделись контактом.',
  VALIDATION_ERROR: 'Даты в прошлом или некорректны — выбери заново.',
}

function nightsWord(n: number): string {
  const mod10 = n % 10, mod100 = n % 100
  if (mod10 === 1 && mod100 !== 11) return `${n} ночь`
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${n} ночи`
  return `${n} ночей`
}

interface BookingSheetProps {
  selection: { checkIn: string; checkOut: string }
  willReplace: ActiveBooking | null
  onSubmit: (comment: string) => void
  onDismiss: () => void
  pending: boolean
  errorCode: string | null
}

export function BookingSheet({ selection, willReplace, onSubmit, onDismiss, pending, errorCode }: BookingSheetProps) {
  const [comment, setComment] = useState('')
  return (
    <div className="fixed inset-x-0 bottom-0 z-10 mx-auto max-w-md rounded-t-3xl bg-card p-4 shadow-[0_-6px_18px_rgba(0,0,0,0.10)]">
      <button type="button" aria-label="Свернуть" onClick={onDismiss}
        className="mx-auto mb-2 block h-1 w-9 rounded bg-muted/50" />
      <p className="mb-2 text-sm">
        Заезд <b>{isoToRu(selection.checkIn)}</b> → выезд <b>{isoToRu(selection.checkOut)}</b>{' '}
        · {nightsWord(nightsBetween(selection.checkIn, selection.checkOut))}
      </p>
      {willReplace && (
        <p className="mb-2 rounded-lg bg-hanko/10 p-2 text-xs text-hanko">
          Подтверждение заменит твою бронь {isoToRu(willReplace.checkIn)} → {isoToRu(willReplace.checkOut)}.
        </p>
      )}
      <input
        className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-sm"
        placeholder="Комментарий (необязательно)"
        maxLength={500}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
      <button
        type="button"
        className="mt-2 w-full rounded-xl bg-ink py-2.5 text-sm text-paper disabled:opacity-50"
        disabled={pending}
        onClick={() => onSubmit(comment.trim())}
      >
        Забронировать
      </button>
      {errorCode && (
        <p className="mt-2 text-xs text-hanko">{ERROR_TEXTS[errorCode] ?? 'Что-то пошло не так — попробуй ещё раз.'}</p>
      )}
      <p className="mt-1.5 text-center text-xs text-muted">код подтверждения придёт в Telegram</p>
    </div>
  )
}
