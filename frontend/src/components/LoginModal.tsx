import { LoginCard } from './LoginCard'

export function LoginModal({
  open,
  title,
  onClose,
}: {
  open: boolean
  title: string
  onClose: () => void
}) {
  if (!open) return null
  return (
    // Подложка НЕ закрывает по клику: на мобиле click при сдвиге лейаута
    // (открытие клавиатуры двигает карточку) улетает в подложку, даже когда
    // пользователь целился в кнопку. Закрытие — только явным крестиком.
    // items-start + pt на мобиле: карточка прижата к верху, клавиатура её не двигает.
    <div
      data-testid="login-backdrop"
      className="fixed inset-0 z-20 flex items-start justify-center overflow-y-auto bg-ink/40 p-4 pt-14 sm:items-center sm:pt-4"
    >
      {/* непрозрачная карточка — сквозь модалку не должен просвечивать календарь */}
      <div className="relative w-full max-w-sm rounded-3xl bg-paper p-4 shadow-xl">
        <button
          type="button"
          aria-label="Закрыть"
          className="absolute right-3 top-2.5 p-1 text-muted"
          onClick={onClose}
        >
          ✕
        </button>
        <p className="mb-3 text-center font-display text-lg">{title}</p>
        <LoginCard />
      </div>
    </div>
  )
}
