import { useState } from 'react'
import { ApiError } from '../api/client'
import { useAccessRequest, useLogin } from '../api/queries'

export function LoginCard() {
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [message, setMessage] = useState('')
  const login = useLogin()
  const request = useAccessRequest()

  const loginCode = login.error instanceof ApiError ? login.error.code : null
  const requestCode = request.error instanceof ApiError ? request.error.code : null
  const showRequestForm = loginCode === 'UNKNOWN_PHONE'

  return (
    <div className="mb-4">
      <div className="rounded-2xl bg-card p-4">
        <div className="mb-2 text-sm">Вход для своих</div>
        <input
          className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-sm"
          placeholder="+7 ___ ___ __ __"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
        />
        <button
          type="button"
          className="mt-2 w-full rounded-xl bg-ink py-2 text-sm text-paper disabled:opacity-50"
          disabled={login.isPending || !phone.trim()}
          onClick={() => login.mutate(phone.trim())}
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
        <div className="mt-2 rounded-2xl border border-hanko/40 bg-hanko/5 p-4">
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
                onClick={() => request.mutate({ phone: phone.trim(), name: name.trim(), message: message.trim() || undefined })}
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
