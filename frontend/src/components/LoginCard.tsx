import { useLayoutEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { useAccessRequest, useLogin } from '../api/queries'
import { caretAfterDigits, formatPhone, phoneDigits, toApiPhone } from '../lib/phone'

export function LoginCard() {
  const [digits, setDigits] = useState('')
  const [name, setName] = useState('')
  const [message, setMessage] = useState('')
  const login = useLogin()
  const request = useAccessRequest()
  const phoneRef = useRef<HTMLInputElement | null>(null)
  const caretRef = useRef<number | null>(null)

  // Контролируемый инпут с маской после ререндера кидает курсор в конец —
  // возвращаем его на позицию, вычисленную в onChange
  useLayoutEffect(() => {
    if (caretRef.current != null && phoneRef.current != null) {
      phoneRef.current.setSelectionRange(caretRef.current, caretRef.current)
      caretRef.current = null
    }
  })

  const loginCode = login.error instanceof ApiError ? login.error.code : null
  const requestCode = request.error instanceof ApiError ? request.error.code : null
  const showRequestForm = loginCode === 'UNKNOWN_PHONE'

  return (
    <div>
      <div className="rounded-2xl bg-card p-4">
        <input
          ref={phoneRef}
          data-testid="phone-input"
          className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-sm"
          placeholder="+7 (___) ___-__-__"
          inputMode="tel"
          value={formatPhone(digits)}
          onChange={(e) => {
            const raw = e.target.value
            const caret = e.target.selectionStart ?? raw.length
            const digitsBeforeCaret = phoneDigits(raw.slice(0, caret)).length
            const next = phoneDigits(raw)
            caretRef.current = caretAfterDigits(
              formatPhone(next),
              Math.min(digitsBeforeCaret, next.length),
            )
            setDigits(next)
          }}
        />
        <button
          type="button"
          className="mt-2 w-full rounded-xl bg-ink py-2 text-sm text-paper disabled:opacity-50"
          disabled={login.isPending || digits.length !== 10}
          onClick={() => {
            // новый вход — прошлая заявка неактуальна
            request.reset()
            setName('')
            setMessage('')
            login.mutate(toApiPhone(digits))
          }}
        >
          Войти
        </button>
        {loginCode === 'RATE_LIMITED' && (
          <p className="mt-2 text-xs text-hanko">Слишком часто — подожди минуту.</p>
        )}
        {loginCode === 'VALIDATION_ERROR' && (
          <p className="mt-2 text-xs text-hanko">Это не похоже на номер телефона.</p>
        )}
      </div>

      {showRequestForm && (
        <div className="mt-2 rounded-2xl border border-hanko/40 bg-hankobg p-4">
          {request.isSuccess ? (
            <p className="text-sm">Заявка отправлена — владелец свяжется с тобой.</p>
          ) : (
            <>
              <p className="mb-1 text-sm text-hanko">Этого номера нет в списке гостей</p>
              <p className="mb-2 text-xs text-muted">Оставь заявку — владелец добавит тебя.</p>
              <input
                className="mb-1.5 w-full rounded-lg border border-muted/40 bg-paper p-2 text-xs"
                placeholder="Как тебя зовут"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
              <input
                className="w-full rounded-lg border border-muted/40 bg-paper p-2 text-xs"
                placeholder="Откуда ты меня знаешь :)"
                value={message}
                onChange={(e) => setMessage(e.target.value)}
              />
              <button
                type="button"
                className="mt-2 w-full rounded-lg bg-hanko py-2 text-xs text-paper disabled:opacity-50"
                disabled={request.isPending || !name.trim()}
                onClick={() => request.mutate({ phone: toApiPhone(digits), name: name.trim(), message: message.trim() || undefined })}
              >
                Отправить заявку
              </button>
              {requestCode === 'ALREADY_MEMBER' && (
                <p className="mt-2 text-xs">Этот номер уже в списке — просто войди.</p>
              )}
              {requestCode === 'RATE_LIMITED' && (
                <p className="mt-2 text-xs text-hanko">Слишком часто — подожди минуту.</p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
