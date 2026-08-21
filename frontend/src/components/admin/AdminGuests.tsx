import { useLayoutEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { useAddGuest, useAdminGuests, useDeleteGuest } from '../../api/queries'
import { caretAfterDigits, formatPhone, phoneDigits, toApiPhone } from '../../lib/phone'
import { GreetingsEditor } from './GreetingsEditor'

export function AdminGuests() {
  const guests = useAdminGuests()
  const add = useAddGuest()
  const del = useDeleteGuest()
  const [digits, setDigits] = useState('')
  const [name, setName] = useState('')
  const [confirmId, setConfirmId] = useState<number | null>(null)
  const [greetingsFor, setGreetingsFor] = useState<number | null>(null)
  const phoneRef = useRef<HTMLInputElement | null>(null)
  const caretRef = useRef<number | null>(null)

  useLayoutEffect(() => {
    if (caretRef.current != null && phoneRef.current != null) {
      phoneRef.current.setSelectionRange(caretRef.current, caretRef.current)
      caretRef.current = null
    }
  })

  const already = add.error instanceof ApiError && add.error.code === 'ALREADY_MEMBER'

  return (
    <div>
      <div className="mb-4 flex items-end gap-2 rounded-xl bg-card p-3">
        <input
          ref={phoneRef}
          data-testid="add-phone"
          className="rounded-lg border border-muted/40 bg-paper p-2 text-sm"
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
        <input aria-label="Имя" className="rounded-lg border border-muted/40 bg-paper p-2 text-sm"
          placeholder="Имя" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="button" className="rounded-lg bg-ink px-3 py-2 text-sm text-paper disabled:opacity-50"
          disabled={add.isPending || digits.length !== 10 || name.trim() === ''}
          onClick={() => add.mutate({ phone: toApiPhone(digits), name: name.trim() },
            { onSuccess: () => { setDigits(''); setName('') } })}>
          Добавить гостя
        </button>
        {already && <span className="text-xs text-hanko">Этот номер уже в списке</span>}
      </div>

      <table className="w-full text-left text-sm">
        <thead className="text-xs text-muted">
          <tr><th className="py-1">Имя</th><th>Телефон</th><th>Роль</th><th>Telegram</th><th>Статус</th><th></th></tr>
        </thead>
        <tbody>
          {(guests.data ?? []).map((g) => (
            <tr key={g.id} className="border-t border-muted/20">
              <td className="py-2">{g.name}</td>
              <td>{g.phone}</td>
              <td>{g.role === 'ADMIN' ? 'админ' : 'гость'}</td>
              <td>{g.telegramLinked ? 'да' : '—'}</td>
              <td>{g.deletedAt ? 'удалён' : 'активен'}</td>
              <td className="py-2 text-right text-xs">
                {g.deletedAt == null && g.role !== 'ADMIN' && (
                  <span className="flex justify-end gap-2">
                    <button type="button" className="text-muted" onClick={() => setGreetingsFor(g.id)}>Приветствия</button>
                    <button type="button" className="text-hanko" onClick={() => setConfirmId(g.id)}>Удалить</button>
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {confirmId != null && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">Удалить этого гостя из списка?</p>
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setConfirmId(null)}>Отмена</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper disabled:opacity-50"
                disabled={del.isPending}
                onClick={() => del.mutate(confirmId, { onSuccess: () => setConfirmId(null) })}>Да, удалить</button>
            </div>
          </div>
        </div>
      )}

      {greetingsFor != null && (
        <GreetingsEditor guestId={greetingsFor} onClose={() => setGreetingsFor(null)} />
      )}
    </div>
  )
}
