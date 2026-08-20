import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (phone: string) => api.post<void>('/auth/login', { phone }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useAccessRequest() {
  return useMutation({
    mutationFn: (body: { phone: string; name: string; message?: string }) =>
      api.post<void>('/access-requests', body),
  })
}
