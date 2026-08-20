import { addMonths, isoRange, isoToRu, monthGrid, monthTitle, nightsBetween } from './dates'

test('isoToRu', () => {
  expect(isoToRu('2026-09-10')).toBe('10/09/2026')
  expect(isoToRu('2026-01-05')).toBe('05/01/2026')
})

test('monthTitle по-русски', () => {
  expect(monthTitle('2026-09-01')).toBe('Сентябрь 2026')
  expect(monthTitle('2026-03-01')).toBe('Март 2026')
})

test('nightsBetween — полуинтервал', () => {
  expect(nightsBetween('2026-09-10', '2026-09-13')).toBe(3)
  expect(nightsBetween('2026-09-10', '2026-09-11')).toBe(1)
})

test('addMonths через границу года', () => {
  expect(addMonths('2026-12-01', 1)).toBe('2027-01-01')
  expect(addMonths('2026-01-01', -1)).toBe('2025-12-01')
})

test('monthGrid: сентябрь 2026 начинается со вторника', () => {
  const grid = monthGrid('2026-09-01')
  expect(grid[0]).toBeNull()          // понедельник пуст
  expect(grid[1]).toBe('2026-09-01')  // вторник
  expect(grid).toContain('2026-09-30')
  expect(grid.length % 7).toBe(0)
})

test('isoRange исключает правую границу', () => {
  expect(isoRange('2026-09-10', '2026-09-13')).toEqual(['2026-09-10', '2026-09-11', '2026-09-12'])
})
