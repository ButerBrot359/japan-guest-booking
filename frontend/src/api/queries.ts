import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from './client'
import type { CalendarResponse, Me } from './types'

export function useCalendar(fromIso: string, toIso: string) {
  return useQuery({
    queryKey: ['calendar', fromIso, toIso],
    queryFn: () => api.get<CalendarResponse>(`/calendar?from=${fromIso}&to=${toIso}`),
  })
}

export function useMe() {
  return useQuery({
    queryKey: ['me'],
    // 401 = «не залогинен» — это данные (null), а не ошибка
    queryFn: () =>
      api.get<Me>('/me').catch((e) => {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }),
  })
}
