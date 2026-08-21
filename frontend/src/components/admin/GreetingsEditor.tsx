import { useEffect, useState } from 'react'
import { useGuestGreetings, useSetGreetings } from '../../api/queries'

export function GreetingsEditor({ guestId, onClose }: { guestId: number; onClose: () => void }) {
  const current = useGuestGreetings(guestId)
  const save = useSetGreetings()
  const [lines, setLines] = useState<string[]>([])

  // подставляем текущие приветствия, когда загрузились
  useEffect(() => {
    if (current.data) setLines(current.data.length ? current.data : [''])
  }, [current.data])

  return (
    <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
      <div className="w-full max-w-md rounded-2xl bg-paper p-4 text-sm shadow-xl">
        <p className="mb-3 font-display text-base">Приветствия гостя</p>
        <div className="space-y-2">
          {lines.map((line, i) => (
            <div key={i} className="flex gap-2">
              <input
                aria-label={`Приветствие ${i + 1}`}
                className="w-full rounded-lg border border-muted/40 bg-card p-2"
                value={line}
                onChange={(e) => setLines((ls) => ls.map((l, j) => (j === i ? e.target.value : l)))}
              />
              <button type="button" aria-label="удалить строку" className="text-hanko"
                onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>✕</button>
            </div>
          ))}
        </div>
        <button type="button" className="mt-2 text-xs text-muted"
          onClick={() => setLines((ls) => [...ls, ''])}>Добавить строку</button>
        <div className="mt-4 flex gap-2 text-xs">
          <button type="button" className="flex-1 rounded-lg bg-ink py-2 text-paper disabled:opacity-50"
            disabled={save.isPending}
            onClick={() => save.mutate(
              { id: guestId, greetings: lines.map((l) => l.trim()).filter((l) => l !== '') },
              { onSuccess: onClose },
            )}>Сохранить</button>
          <button type="button" className="flex-1 rounded-lg border border-muted/40 py-2"
            onClick={onClose}>Отмена</button>
        </div>
      </div>
    </div>
  )
}
