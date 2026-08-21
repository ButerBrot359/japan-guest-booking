import type { CalendarDay } from '../api/types'
import type { Selection } from '../components/Calendar'
import { isoRange, nightsBetween } from './dates'

const MAX_NIGHTS = 14

export function pickDay(
  selection: Selection,
  dayIso: string,
  days: Map<string, CalendarDay>,
): Selection {
  const { checkIn, checkOut } = selection
  if (!checkIn || checkOut) return { checkIn: dayIso, checkOut: null }
  if (dayIso <= checkIn) return { checkIn: dayIso, checkOut: null }
  // вторая линия защиты — зеркалит бэкенд-лимит (RANGE_TOO_LONG)
  if (nightsBetween(checkIn, dayIso) > MAX_NIGHTS) return { checkIn: dayIso, checkOut: null }
  // заняты не только ночи [checkIn, dayIso), но и сам день выезда:
  // с этапа 6.6 бронь занимает диапазон включительно
  const blocked = [...isoRange(checkIn, dayIso), dayIso]
    .some((d) => (days.get(d)?.status ?? 'FREE') !== 'FREE')
  return blocked ? { checkIn: dayIso, checkOut: null } : { checkIn, checkOut: dayIso }
}
