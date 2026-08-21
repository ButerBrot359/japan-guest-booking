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
    <div
      className="fixed inset-0 z-20 flex items-center justify-center bg-ink/40 p-4"
      onClick={onClose}
    >
      {/* непрозрачная карточка — сквозь модалку не должен просвечивать календарь */}
      <div
        className="w-full max-w-sm rounded-3xl bg-paper p-4 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="mb-3 text-center font-display text-lg">{title}</p>
        <LoginCard />
      </div>
    </div>
  )
}
