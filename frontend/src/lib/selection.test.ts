import type { CalendarDay } from '../api/types'
import { pickDay } from './selection'

const days = (busy: string[]): Map<string, CalendarDay> =>
  new Map(busy.map((d) => [d, { date: d, status: 'BOOKED' as const, guestName: null, mine: false }]))

const none = { checkIn: null, checkOut: null }

test('первый клик — заезд', () => {
  expect(pickDay(none, '2026-09-10', days([]))).toEqual({ checkIn: '2026-09-10', checkOut: null })
})

test('второй клик позже — выезд', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-13', days([])))
    .toEqual({ checkIn: '2026-09-10', checkOut: '2026-09-13' })
})

test('выезд в день чужого заезда запрещён (день выезда тоже BOOKED)', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-13', days(['2026-09-13'])))
    .toEqual({ checkIn: '2026-09-13', checkOut: null })
})

test('диапазон через занятый день не собирается — новый заезд', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-14', days(['2026-09-12'])))
    .toEqual({ checkIn: '2026-09-14', checkOut: null })
})

test('клик раньше заезда — новый заезд', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-08', days([])))
    .toEqual({ checkIn: '2026-09-08', checkOut: null })
})

test('клик при полном выборе начинает заново', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: '2026-09-13' }, '2026-09-20', days([])))
    .toEqual({ checkIn: '2026-09-20', checkOut: null })
})

test('ровно 14 ночей — можно', () => {
  expect(pickDay({ checkIn: '2026-09-01', checkOut: null }, '2026-09-15', days([])))
    .toEqual({ checkIn: '2026-09-01', checkOut: '2026-09-15' })
})

test('больше 14 ночей — новый заезд с кликнутого дня', () => {
  expect(pickDay({ checkIn: '2026-09-01', checkOut: null }, '2026-09-16', days([])))
    .toEqual({ checkIn: '2026-09-16', checkOut: null })
})

test('занятый день выезда не даёт закрыть диапазон', () => {
  // 12-е занято: выбрать 10 → 12 нельзя, клик начинает новый выбор
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-12', days(['2026-09-12'])))
    .toEqual({ checkIn: '2026-09-12', checkOut: null })
})

test('повторный клик по заезду без allowSingleDay — просто новый заезд', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-10', days([])))
    .toEqual({ checkIn: '2026-09-10', checkOut: null })
})

test('allowSingleDay: повторный клик по заезду даёт один день', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-10', days([]), { allowSingleDay: true }))
    .toEqual({ checkIn: '2026-09-10', checkOut: '2026-09-10' })
})

test('maxNights: Infinity — диапазон длиннее 14 ночей собирается', () => {
  expect(pickDay({ checkIn: '2026-09-01', checkOut: null }, '2026-10-15', days([]), { maxNights: Infinity }))
    .toEqual({ checkIn: '2026-09-01', checkOut: '2026-10-15' })
})

test('maxNights: Infinity не отключает проверку занятости', () => {
  expect(pickDay({ checkIn: '2026-09-01', checkOut: null }, '2026-10-15', days(['2026-09-20']), { maxNights: Infinity }))
    .toEqual({ checkIn: '2026-10-15', checkOut: null })
})
