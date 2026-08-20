import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { useCancelPending, useConfirmBooking, useResendCode } from '../api/queries'

const ERROR_TEXTS: Record<string, string> = {
  INVALID_CODE: 'Неверный код',
  CODE_EXPIRED: 'Код сгорел — отправь новый',
  NO_ACTIVE_CODE: 'Кода нет — отправь новый',
  BOOKING_EXPIRED: 'Бронь истекла — начни заново',
  RESEND_TOO_SOON: 'Новый код — не чаще раза в минуту',
}

interface OtpModalProps {
  bookingId: number
  subtitle: string
  showCancelPending: boolean
  onDone: () => void
  onClose: () => void
}

export function OtpModal({ bookingId, subtitle, showCancelPending, onDone, onClose }: OtpModalProps) {
  const [code, setCode] = useState('')
  const [cooldown, setCooldown] = useState(60)
  const confirm = useConfirmBooking()
  const resend = useResendCode()
  const cancelPending = useCancelPending()

  useEffect(() => {
    if (cooldown <= 0) return
    const t = setInterval(() => setCooldown((c) => c - 1), 1000)
    return () => clearInterval(t)
  }, [cooldown > 0])

  const errorCode =
    (confirm.error instanceof ApiError && confirm.error.code) ||
    (resend.error instanceof ApiError && resend.error.code) || null

  return (
    <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
      <div className="w-full max-w-xs rounded-2xl bg-paper p-4 shadow-xl">
        <p className="text-center text-sm">Код из Telegram</p>
        <p className="mb-3 text-center text-xs text-muted">{subtitle}</p>
        <input
          aria-label="Код из Telegram"
          inputMode="numeric"
          autoFocus
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          className="mb-3 w-full rounded-xl border border-muted/40 bg-paper p-2 text-center font-mono text-2xl tracking-[0.4em]"
        />
        <button
          type="button"
          className="w-full rounded-xl bg-ink py-2 text-sm text-paper disabled:opacity-50"
          disabled={code.length !== 6 || confirm.isPending}
          onClick={() => confirm.mutate({ bookingId, code }, { onSuccess: onDone })}
        >
          Подтвердить
        </button>
        {errorCode && (
          <p className="mt-2 text-center text-xs text-hanko">
            {ERROR_TEXTS[errorCode] ?? 'Не получилось — попробуй ещё раз'}
          </p>
        )}
        <div className="mt-3 flex items-center justify-between text-xs">
          <button
            type="button"
            className="text-muted disabled:opacity-50"
            disabled={cooldown > 0 || resend.isPending}
            onClick={() =>
              resend.mutate(bookingId, {
                onSettled: () => { setCooldown(60); setCode('') },
              })
            }
          >
            Отправить новый{cooldown > 0 ? ` (0:${String(cooldown).padStart(2, '0')})` : ''}
          </button>
          {showCancelPending ? (
            <button type="button" className="text-hanko"
              onClick={() => cancelPending.mutate(undefined, { onSettled: onClose })}>
              Отменить бронь
            </button>
          ) : (
            <button type="button" className="text-muted" onClick={onClose}>Закрыть</button>
          )}
        </div>
      </div>
    </div>
  )
}
