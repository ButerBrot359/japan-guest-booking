import { LoginCard } from './LoginCard'

export function LoginModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-4"
         onClick={onClose}>
      <div className="w-full max-w-sm" onClick={(e) => e.stopPropagation()}>
        <p className="mb-2 text-center font-display text-lg">Войдите, чтобы выбрать даты</p>
        <LoginCard />
      </div>
    </div>
  )
}
