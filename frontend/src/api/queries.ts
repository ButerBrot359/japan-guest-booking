import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './client'
import type {
  AccessRequestRow, AccessRequestStatus, AdminUserRow, CalendarResponse, CreateResult, Me, MyBookings,
} from './types'

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
  // шаг 1: только отправляет код в Telegram, куки ещё нет
  return useMutation({ mutationFn: (phone: string) => api.post<void>('/auth/login', { phone }) })
}

export function useVerifyLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { phone: string; code: string }) => api.post<void>('/auth/verify', body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      // календарь зависит от зрителя (mine/имена гостей) — после входа перезапрашиваем
      qc.invalidateQueries({ queryKey: ['calendar'] })
      qc.invalidateQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      // без этого зелёные дни и имена гостей остаются видны анониму до F5
      qc.invalidateQueries({ queryKey: ['calendar'] })
      // брони прошлого пользователя не должны пережить выход
      qc.removeQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useAccessRequest() {
  return useMutation({
    mutationFn: (body: { phone: string; name: string; message?: string }) =>
      api.post<void>('/access-requests', body),
  })
}

export function useCreateBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { checkIn: string; checkOut: string; comment?: string }) =>
      api.post<CreateResult>('/bookings', body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
      qc.invalidateQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useRescheduleBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ bookingId, checkIn, checkOut }: { bookingId: number; checkIn: string; checkOut: string }) =>
      api.patch<void>(`/bookings/${bookingId}`, { checkIn, checkOut }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
      qc.invalidateQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useCancelBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (bookingId: number) => api.del<void>(`/bookings/${bookingId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
      qc.invalidateQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useMyBookings() {
  return useQuery({ queryKey: ['my-bookings'], queryFn: () => api.get<MyBookings>('/me/bookings') })
}

export function useUpdateComment() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (comment: string | null) => api.patch<void>('/bookings/active', { comment }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['my-bookings'] }),
  })
}

export function useAdminLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { phone: string; password: string }) => api.post<void>('/auth/admin-login', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useAccessRequests(status: AccessRequestStatus) {
  return useQuery({
    queryKey: ['admin', 'access-requests', status],
    queryFn: () => api.get<AccessRequestRow[]>(`/admin/access-requests?status=${status}`),
  })
}

export function useApproveRequest() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.post<void>(`/admin/access-requests/${id}/approve`),
    // onSettled, а не onSuccess: даже при 409 ALREADY_RESOLVED список должен тихо обновиться.
    // Одобрение создаёт гостя — обновляем и вкладку «Гости».
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'access-requests'] })
      qc.invalidateQueries({ queryKey: ['admin', 'users'] })
    },
  })
}

export function useRejectRequest() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.post<void>(`/admin/access-requests/${id}/reject`),
    // onSettled, а не onSuccess: даже при 409 ALREADY_RESOLVED список должен тихо обновиться
    onSettled: () => qc.invalidateQueries({ queryKey: ['admin', 'access-requests'] }),
  })
}

export function useAdminGuests() {
  return useQuery({ queryKey: ['admin', 'users'], queryFn: () => api.get<AdminUserRow[]>('/admin/users') })
}

export function useAddGuest() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { phone: string; name: string }) => api.post<void>('/admin/users', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export function useDeleteGuest() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.del<void>(`/admin/users/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export function useGuestGreetings(id: number) {
  return useQuery({
    queryKey: ['admin', 'greetings', id],
    queryFn: () => api.get<string[]>(`/admin/users/${id}/greetings`),
    enabled: id > 0,
  })
}

export function useSetGreetings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, greetings }: { id: number; greetings: string[] }) =>
      api.put<void>(`/admin/users/${id}/greetings`, { greetings }),
    onSuccess: (_r, { id }) => {
      qc.invalidateQueries({ queryKey: ['admin', 'greetings', id] })
      // колонка приветствий берётся из списка гостей — обновляем и его
      qc.invalidateQueries({ queryKey: ['admin', 'users'] })
    },
  })
}
