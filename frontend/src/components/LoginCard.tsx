import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { useAccessRequest, useLogin, useVerifyLogin } from '../api/queries'
import { caretAfterDigits, formatPhone, phoneDigits, toApiPhone } from '../lib/phone'

const VERIFY_ERROR_TEXTS: Record<string, string> = {
  INVALID_CODE: 'Неверный код',
  CODE_EXPIRED: 'Код сгорел — отправь новый',
  NO_ACTIVE_CODE: 'Кода нет — отправь новый',
  RATE_LIMITED: 'Слишком часто — подожди минуту.',
}

export function LoginCard() {
  const [digits, setDigits] = useState('')
  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [code, setCode] = useState('')
  const [cooldown, setCooldown] = useState(0)
  const [name, setName] = useState('')
  const [message, setMessage] = useState('')
  const login = useLogin()
  const verify = useVerifyLogin()
  const request = useAccessRequest()
  const phoneRef = useRef<HTMLInputElement | null>(null)
  const caretRef = useRef<number | null>(null)
  const botUrl = import.meta.env.VITE_BOT_URL

  // Контролируемый инпут с маской после ререндера кидает курсор в конец —
  // возвращаем его на позицию, вычисленную в onChange
  useLayoutEffect(() => {
    if (caretRef.current != null && phoneRef.current != null) {
      phoneRef.current.setSelectionRange(caretRef.current, caretRef.current)
      caretRef.current = null
    }
  })

  useEffect(() => {
    if (cooldown <= 0) return
    const t = setInterval(() => setCooldown((c) => c - 1), 1000)
    return () => clearInterval(t)
    // boolean-dep намеренно: интервал перезапускается только на границе 0
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cooldown > 0])

  const loginCode = login.error instanceof ApiError ? login.error.code : null
  const verifyCode = verify.error instanceof ApiError ? verify.error.code : null
  const requestCode = request.error instanceof ApiError ? request.error.code : null
  const showRequestForm = loginCode === 'UNKNOWN_PHONE'
  // на шаге кода ошибка может прийти либо от verify (неверный код), либо от
  // повторного login при «Отправить новый» (например RATE_LIMITED) — мержим,
  // как раньше OtpModal мержил confirm.error/resend.error
  const codeStepErrorCode = verifyCode ?? loginCode

  const sendCode = () => {
    request.reset()
    verify.reset()
    setName('')
    setMessage('')
    setCode('')
    login.mutate(toApiPhone(digits), {
      onSuccess: () => { setStep('code'); setCooldown(60) },
    })
  }

  if (step === 'code') {
    return (
      <div className="rounded-2xl bg-card p-4">
        <p className="mb-1 text-center text-sm">Код из Telegram</p>
        <p className="mb-3 text-center text-xs text-muted">отправили на {formatPhone(digits)}</p>
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
          className="w-full rounded-xl bg-ink py-2 text-base text-paper disabled:opacity-50 lg:text-sm"
          disabled={code.length !== 6 || verify.isPending}
          onClick={() => verify.mutate({ phone: toApiPhone(digits), code })}
        >
          Войти
        </button>
        {codeStepErrorCode && (
          <p className="mt-2 text-center text-xs text-hanko">
            {VERIFY_ERROR_TEXTS[codeStepErrorCode] ?? 'Не получилось — попробуй ещё раз'}
          </p>
        )}
        <div className="mt-3 flex items-center justify-between text-xs">
          <button
            type="button"
            className="text-muted disabled:opacity-50"
            disabled={cooldown > 0 || login.isPending}
            onClick={sendCode}
          >
            Отправить новый{cooldown > 0 ? ` (0:${String(cooldown).padStart(2, '0')})` : ''}
          </button>
          <button type="button" className="text-muted"
            onClick={() => { verify.reset(); login.reset(); setStep('phone') }}>
            Изменить номер
          </button>
        </div>
      </div>
    )
  }

  return (
    <div>
      <div className="rounded-2xl bg-card p-4">
        {/* телефонный инпут — как раньше, класс размера: text-base lg:text-sm */}
        <input
          ref={phoneRef}
          data-testid="phone-input"
          className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-base lg:text-sm"
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
          className="mt-2 w-full rounded-xl bg-ink py-2 text-base text-paper disabled:opacity-50 lg:text-sm"
          disabled={login.isPending || digits.length !== 10}
          onClick={sendCode}
        >
          Получить код
        </button>
        {loginCode === 'RATE_LIMITED' && (
          <p className="mt-2 text-xs text-hanko">Слишком часто — подожди минуту.</p>
        )}
        {loginCode === 'VALIDATION_ERROR' && (
          <p className="mt-2 text-xs text-hanko">Это не похоже на номер телефона.</p>
        )}
        {loginCode === 'TELEGRAM_NOT_LINKED' && (
          <p className="mt-2 rounded-lg border border-warn-border bg-warn-bg p-2 text-xs text-warn-text">
            Мы шлём код входа в Telegram.{' '}
            {botUrl ? (
              <a href={botUrl} target="_blank" rel="noreferrer" className="underline">Напиши боту</a>
            ) : (
              'Напиши боту'
            )}
            , поделись контактом — и возвращайся.
          </p>
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
