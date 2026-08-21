import type { Me } from '../api/types'

export function Greeting({ me }: { me: Me }) {
  return (
    <div className="mb-3">
      <p className="mb-1 text-xs text-muted">{me.phone}</p>
      <p className="font-display text-base leading-snug">
        {me.greeting ?? `Привет, ${me.name}!`}
      </p>
    </div>
  )
}
