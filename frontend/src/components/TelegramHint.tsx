export function TelegramHint() {
  const botUrl = import.meta.env.VITE_BOT_URL
  return (
    <div className="mb-3 rounded-2xl border border-warn-border bg-warn-bg p-3 text-xs text-warn-text">
      ✈ Telegram не привязан —{' '}
      {botUrl ? (
        <a href={botUrl} target="_blank" rel="noreferrer" className="underline">напиши боту</a>
      ) : (
        'напиши боту'
      )}{' '}
      и поделись контактом, иначе код подтверждения не придёт.
    </div>
  )
}
