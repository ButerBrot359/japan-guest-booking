// Единственная граница форматов: ISO в API и состоянии, дд/мм/гггг и русские
// названия — только здесь, на выходе в рендер. Работаем строками и UTC —
// никаких Date-с-таймзоной для календарных дат.

const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь']

const DAY_MS = 24 * 60 * 60 * 1000

function toUtc(iso: string): number {
  return Date.parse(iso + 'T00:00:00Z')
}

function fromUtc(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

export function isoToRu(iso: string): string {
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

export function monthTitle(isoFirstDay: string): string {
  const [y, m] = isoFirstDay.split('-')
  return `${MONTHS[Number(m) - 1]} ${y}`
}

export function nightsBetween(checkIn: string, checkOut: string): number {
  return Math.round((toUtc(checkOut) - toUtc(checkIn)) / DAY_MS)
}

export function nightsWord(n: number): string {
  const mod10 = n % 10, mod100 = n % 100
  if (mod10 === 1 && mod100 !== 11) return `${n} ночь`
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${n} ночи`
  return `${n} ночей`
}

export function addMonths(isoFirstDay: string, delta: number): string {
  const [y, m] = isoFirstDay.split('-').map(Number)
  const total = y * 12 + (m - 1) + delta
  const ny = Math.floor(total / 12)
  const nm = (total % 12) + 1
  return `${ny}-${String(nm).padStart(2, '0')}-01`
}

export function todayIso(): string {
  // сегодня по JST (UTC+9, без DST) — как LocalDate.now(JST) на бэкенде
  return new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10)
}

export function addDays(iso: string, n: number): string {
  return fromUtc(toUtc(iso) + n * DAY_MS)
}

export function isoRange(fromInclusive: string, toExclusive: string): string[] {
  const out: string[] = []
  for (let t = toUtc(fromInclusive); t < toUtc(toExclusive); t += DAY_MS) out.push(fromUtc(t))
  return out
}

/** Сетка месяца по неделям, понедельник — первый; null = пустая ячейка. */
export function monthGrid(isoFirstDay: string): (string | null)[] {
  const first = toUtc(isoFirstDay)
  const firstWeekday = (new Date(first).getUTCDay() + 6) % 7 // пн=0
  const nextMonth = toUtc(addMonths(isoFirstDay, 1))
  const cells: (string | null)[] = Array(firstWeekday).fill(null)
  for (let t = first; t < nextMonth; t += DAY_MS) cells.push(fromUtc(t))
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
}
