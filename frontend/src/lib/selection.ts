import type { CalendarDay } from '../api/types'
import type { Selection } from '../components/Calendar'
import { isoRange, nightsBetween } from './dates'

export interface PickOptions {
  /** лимит ночей; Infinity — без лимита (админ). По умолчанию 14, как у гостей */
  maxNights?: number
  /** повторный клик по заезду выбирает один день (checkOut === checkIn) — для блокировок */
  allowSingleDay?: boolean
}

export function pickDay(
  selection: Selection,
  dayIso: string,
  days: Map<string, CalendarDay>,
  opts: PickOptions = {},
): Selection {
  const { maxNights = 14, allowSingleDay = false } = opts
  const { checkIn, checkOut } = selection
  if (!checkIn || checkOut) return { checkIn: dayIso, checkOut: null }
  if (dayIso === checkIn) {
    return allowSingleDay ? { checkIn, checkOut: checkIn } : { checkIn: dayIso, checkOut: null }
  }
  if (dayIso < checkIn) return { checkIn: dayIso, checkOut: null }
  // вторая линия защиты — зеркалит бэкенд-лимит (RANGE_TOO_LONG)
  if (nightsBetween(checkIn, dayIso) > maxNights) return { checkIn: dayIso, checkOut: null }
  // заняты не только ночи [checkIn, dayIso), но и сам день выезда:
  // с этапа 6.6 бронь занимает диапазон включительно
  const blocked = [...isoRange(checkIn, dayIso), dayIso]
    .some((d) => (days.get(d)?.status ?? 'FREE') !== 'FREE')
  return blocked ? { checkIn: dayIso, checkOut: null } : { checkIn, checkOut: dayIso }
}
