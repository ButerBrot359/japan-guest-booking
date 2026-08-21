import { useLayoutEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { useAdminLogin } from '../../api/queries'
import { caretAfterDigits, formatPhone, phoneDigits, toApiPhone } from '../../lib/phone'

export function AdminLoginCard() {
  const [digits, setDigits] = useState('')
  const [password, setPassword] = useState('')
  const login = useAdminLogin()
  const phoneRef = useRef<HTMLInputElement | null>(null)
  const caretRef = useRef<number | null>(null)

  useLayoutEffect(() => {
    if (caretRef.current != null && phoneRef.current != null) {
      phoneRef.current.setSelectionRange(caretRef.current, caretRef.current)
      caretRef.current = null
    }
  })

  const failed = login.error instanceof ApiError

  return (
    <div className="mx-auto mt-16 max-w-sm rounded-3xl bg-paper p-6 shadow-xl">
      <p className="mb-4 text-center font-display text-lg">Вход администратора</p>
      <input
        ref={phoneRef}
        data-testid="admin-phone"
        className="mb-2 w-full rounded-xl border border-muted/40 bg-card p-2 text-base"
        placeholder="+7 (___) ___-__-__"
        inputMode="tel"
        value={formatPhone(digits)}
        onChange={(e) => {
          const raw = e.target.value
          const caret = e.target.selectionStart ?? raw.length
          const before = phoneDigits(raw.slice(0, caret)).length
          const next = phoneDigits(raw)
          caretRef.current = caretAfterDigits(formatPhone(next), Math.min(before, next.length))
          setDigits(next)
        }}
      />
      <input
        aria-label="Пароль"
        type="password"
        className="mb-3 w-full rounded-xl border border-muted/40 bg-card p-2 text-base"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <button
        type="button"
        className="w-full rounded-xl bg-ink py-2 text-base text-paper disabled:opacity-50"
        disabled={login.isPending || digits.length !== 10 || password === ''}
        onClick={() => login.mutate({ phone: toApiPhone(digits), password })}
      >
        Войти
      </button>
      {failed && (
        <p className="mt-2 text-center text-xs text-hanko">Неверный телефон или пароль</p>
      )}
    </div>
  )
}
