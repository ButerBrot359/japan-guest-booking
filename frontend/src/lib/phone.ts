/** Оставляет максимум 10 значащих цифр; у 11-значных с ведущей 7/8 (вставка полного номера) её отбрасывает. */
export function phoneDigits(raw: string): string {
  // Отображаемое значение начинается с '+7 (' — срезаем префикс до парсинга,
  // иначе его семёрка дублируется в цифры при каждом нажатии
  const source = raw.startsWith('+7') ? raw.slice(2) : raw
  let d = source.replace(/\D/g, '')
  // === 11, не >= 11: только полные 11-значные номера (+7XXXXXXXXXX или 8XXXXXXXXXX) требуют нормализации;
  // при overflow (14+ цифр) не трогаем ведущую семёрку, берём только первые 10 цифр
  if (d.length === 11 && (d.startsWith('7') || d.startsWith('8'))) d = d.slice(1)
  return d.slice(0, 10)
}

/** '7787886432' → '+7 (778) 788-64-32'; частичный ввод форматируется по мере набора; '' → ''. */
export function formatPhone(digits: string): string {
  if (digits === '') return ''
  const p = [digits.slice(0, 3), digits.slice(3, 6), digits.slice(6, 8), digits.slice(8, 10)]
  let out = '+7 (' + p[0]
  if (digits.length > 3) out += ') ' + p[1]
  if (digits.length > 6) out += '-' + p[2]
  if (digits.length > 8) out += '-' + p[3]
  return out
}

/** Каноничный номер для API: '+7' + digits. */
export function toApiPhone(digits: string): string {
  return '+7' + digits
}
