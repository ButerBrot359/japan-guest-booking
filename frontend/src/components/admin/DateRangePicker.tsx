import { useState } from 'react'
import type { CalendarDay } from '../../api/types'
import { addDays, addMonths, isoToRu, nightsBetween, nightsWord } from '../../lib/dates'
import { pickDay, type PickOptions } from '../../lib/selection'
import { Calendar, type Selection } from '../Calendar'

interface DateRangePickerProps {
  days: Map<string, CalendarDay>
  value: Selection
  onChange: (next: Selection) => void
  pickOptions?: PickOptions
  minMonth: string
  monthsWindow?: number
}

/**
 * Контролируемый пикер диапазона поверх Calendar: выбором владеет родитель,
 * пикер держит только навигацию по месяцам (мобильные 2 месяца + стрелки,
 * десктоп — окно из monthsWindow месяцев, как в CalendarPage).
 */
export function DateRangePicker({
  days, value, onChange, pickOptions, minMonth, monthsWindow = 12,
}: DateRangePickerProps) {
  const [monthStart, setMonthStart] = useState(minMonth)
  const mobileShiftMax = addMonths(minMonth, monthsWindow - 2)

  const shiftMonth = (delta: 1 | -1) =>
    setMonthStart((m) => {
      const next = addMonths(m, delta)
      if (next < minMonth) return minMonth
      if (next > mobileShiftMax) return mobileShiftMax
      return next
    })

  const desktopMonths = Array.from({ length: monthsWindow }, (_, i) => addMonths(minMonth, i))
  const mobileMonths = [monthStart, addMonths(monthStart, 1)]

  // визуальный хвост-лимит только при конечном maxNights (у гостей); у админа его нет
  const maxNights = pickOptions?.maxNights ?? 14
  const maxCheckout = Number.isFinite(maxNights) && value.checkIn && !value.checkOut
    ? addDays(value.checkIn, maxNights)
    : undefined

  const handlePick = (iso: string) => onChange(pickDay(value, iso, days, pickOptions))

  const { checkIn, checkOut } = value
  return (
    <div>
      <div className="lg:hidden">
        <Calendar months={mobileMonths} days={days} selection={value} selectable
          maxCheckout={maxCheckout} onShiftMonth={shiftMonth} onPick={handlePick} />
      </div>
      <div className="hidden lg:block">
        <Calendar months={desktopMonths} days={days} selection={value} selectable
          maxCheckout={maxCheckout} onPick={handlePick} />
      </div>
      {checkIn && checkOut && (
        <p data-testid="picker-range" className="mt-2 text-sm">
          {checkIn === checkOut
            ? isoToRu(checkIn)
            : `${isoToRu(checkIn)} → ${isoToRu(checkOut)} · ${nightsWord(nightsBetween(checkIn, checkOut))}`}
        </p>
      )}
    </div>
  )
}
