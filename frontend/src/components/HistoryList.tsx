import { useMyBookings } from '../api/queries'
import { isoToRu, nightsWord } from '../lib/dates'

export function HistoryList() {
  const bookings = useMyBookings()
  const history = bookings.data?.history ?? []
  return (
    <div className="mt-4">
      <h3 className="mb-2 font-display text-sm">Прошлые поездки</h3>
      {history.length === 0 && <p className="text-xs text-muted">Пока нет завершённых поездок.</p>}
      <div className="space-y-2">
        {history.map((v, i) => (
          <div key={i} className="rounded-xl bg-card p-2.5 text-sm">
            {isoToRu(v.checkIn)} → {isoToRu(v.checkOut)} · {nightsWord(v.nights)}
          </div>
        ))}
      </div>
    </div>
  )
}
