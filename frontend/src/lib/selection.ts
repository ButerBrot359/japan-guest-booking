import type { CalendarDay } from '../api/types'
import type { Selection } from '../components/Calendar'
import { isoRange } from './dates'

export function pickDay(
  selection: Selection,
  dayIso: string,
  days: Map<string, CalendarDay>,
): Selection {
  const { checkIn, checkOut } = selection
  if (!checkIn || checkOut) return { checkIn: dayIso, checkOut: null }
  if (dayIso <= checkIn) return { checkIn: dayIso, checkOut: null }
  // все НОЧИ [checkIn, dayIso) свободны; сам день выезда может быть занят — полуинтервал
  const blocked = isoRange(checkIn, dayIso)
    .some((d) => (days.get(d)?.status ?? 'FREE') !== 'FREE')
  return blocked ? { checkIn: dayIso, checkOut: null } : { checkIn, checkOut: dayIso }
}
