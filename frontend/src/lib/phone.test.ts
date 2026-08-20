import { formatPhone, phoneDigits, toApiPhone } from './phone'

it('формат полного номера', () =>
  expect(formatPhone('7787886432')).toBe('+7 (778) 788-64-32'))

it('прогрессивный ввод', () => {
  expect(formatPhone('')).toBe('')
  expect(formatPhone('77')).toBe('+7 (77')
  expect(formatPhone('7787')).toBe('+7 (778) 7')
  expect(formatPhone('77878864')).toBe('+7 (778) 788-64')
})

it('вставка полного номера с +7 или 8', () => {
  expect(phoneDigits('+77787886432')).toBe('7787886432')
  expect(phoneDigits('87787886432')).toBe('7787886432')
  expect(phoneDigits('8 (778) 788-64-32')).toBe('7787886432')
})

it('обрезка лишнего', () => expect(phoneDigits('77878864321111')).toBe('7787886432'))

it('канон для API', () => expect(toApiPhone('7787886432')).toBe('+77787886432'))

it('round-trip: посимвольный набор через отформатированное значение', () => {
  let digits = ''
  for (const ch of '9990001122') {
    digits = phoneDigits(formatPhone(digits) + ch)
  }
  expect(digits).toBe('9990001122')
})

it('overflow: лишний символ после полных 10 цифр игнорируется (номер на 7)', () => {
  expect(phoneDigits(formatPhone('7787886432') + '1')).toBe('7787886432')
})

it('overflow: лишний символ игнорируется (номер на 9)', () => {
  expect(phoneDigits(formatPhone('9990001122') + '3')).toBe('9990001122')
})
