import type { CalendarDay } from '../api/types'
import { isoRange, monthGrid, monthTitle, todayIso } from '../lib/dates'

export interface Selection {
  checkIn: string | null
  checkOut: string | null
}

interface CalendarProps {
  months: string[]
  days: Map<string, CalendarDay>
  selection: Selection
  selectable: boolean
  /** Стрелки навигации рендерятся только если проп передан */
  onShiftMonth?: (delta: 1 | -1) => void
  onPick: (dayIso: string) => void
  /** Клик по чужому занятому дню с известным именем гостя («кто гостит») */
  onPickBusy?: (dayIso: string) => void
  /** Пока выбирается выезд — дни дальше 14 ночей от заезда заблокированы */
  maxCheckout?: string
}

const WEEKDAYS = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']
const MONTH_NAMES_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']

function ariaLabel(iso: string, day: CalendarDay | undefined): string {
  const [, m, d] = iso.split('-')
  const date = `${Number(d)} ${MONTH_NAMES_GEN[Number(m) - 1]}`
  if (day?.status === 'BOOKED') return `${date}, занято${day.guestName ? `, ${day.guestName}` : ''}`
  if (day?.status === 'BLOCKED') return `${date}, закрыто`
  return `${date}, свободно`
}

function Month({
  start, days, selection, selectable, onPick, onPickBusy, maxCheckout, today,
}: {
  start: string
  today: string
} & Pick<CalendarProps,
  'days' | 'selection' | 'selectable' | 'onPick' | 'onPickBusy' | 'maxCheckout'
>) {
  const selected = new Set(
    selection.checkIn && selection.checkOut
      ? [...isoRange(selection.checkIn, selection.checkOut), selection.checkOut]
      : selection.checkIn ? [selection.checkIn] : [],
  )
  return (
    <div className="mb-4">
      <div className="mb-1 text-center font-display text-sm">{monthTitle(start)}</div>
      <div className="grid grid-cols-7 gap-1 text-center text-[10px] text-muted">
        {WEEKDAYS.map((w) => <div key={w}>{w}</div>)}
      </div>
      <div className="grid grid-cols-7 gap-1 text-center text-sm">
        {monthGrid(start).map((iso, i) => {
          if (!iso) return <div key={i} />
          const day = days.get(iso)
          const status = day?.status ?? 'FREE'
          // чужой занятый день с известным именем — кликабелен (открывает «кто гостит»),
          // свой (mine) остаётся disabled — клик по своей брони не имеет действия
          const clickableBusy = status === 'BOOKED' && !day?.mine && day?.guestName != null && onPickBusy != null
          const beyondMax = maxCheckout != null && iso > maxCheckout
          const disabled = !selectable || iso < today || beyondMax ||
            (status !== 'FREE' && !clickableBusy)
          return (
            <button
              key={iso}
              type="button"
              aria-label={ariaLabel(iso, day)}
              data-selected={selected.has(iso) ? 'true' : undefined}
              disabled={disabled}
              onClick={() => (clickableBusy ? onPickBusy!(iso) : onPick(iso))}
              className={[
                'rounded-lg py-1.5',
                day?.mine && 'bg-leaf text-paper',
                !day?.mine && status === 'BOOKED' && 'bg-hanko/80 text-paper',
                status === 'BLOCKED' &&
                  'bg-[repeating-linear-gradient(45deg,var(--color-hatch),var(--color-hatch)_3px,var(--color-hatch-2)_3px,var(--color-hatch-2)_6px)] text-muted',
                selected.has(iso) && 'bg-ink text-paper',
                status === 'FREE' && !selected.has(iso) && 'hover:bg-card',
              ].filter(Boolean).join(' ')}
            >
              {Number(iso.slice(8))}
            </button>
          )
        })}
      </div>
    </div>
  )
}

export function Calendar(props: CalendarProps) {
  const { months, onShiftMonth } = props
  const today = todayIso() // один раз на рендер, не в каждой ячейке
  return (
    <section>
      {onShiftMonth && (
        <div className="mb-2 flex items-center justify-between text-sm text-muted">
          <button type="button" aria-label="Предыдущий месяц" onClick={() => onShiftMonth(-1)}>◀</button>
          <span className="text-xs">выбери даты</span>
          <button type="button" aria-label="Следующий месяц" onClick={() => onShiftMonth(1)}>▶</button>
        </div>
      )}
      <div className="grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
        {months.map((m) => <Month key={m} {...props} start={m} today={today} />)}
      </div>
      <div className="text-xs text-muted">
        <span className="mr-3"><span className="inline-block h-2 w-2 rounded-sm bg-leaf" /> твоя</span>
        <span className="mr-3"><span className="inline-block h-2 w-2 rounded-sm bg-hanko/80" /> занято</span>
        <span><span className="inline-block h-2 w-2 rounded-sm bg-hatch" /> закрыто</span>
      </div>
    </section>
  )
}
