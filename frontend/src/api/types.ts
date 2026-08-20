// Зеркало DTO бэкенда, дословно
export type DayStatus = 'FREE' | 'BOOKED' | 'BLOCKED'
export interface CalendarDay { date: string; status: DayStatus; guestName: string | null; mine: boolean }
export interface CalendarResponse { days: CalendarDay[] }
export type BookingStatus = 'PENDING_OTP' | 'CONFIRMED' | 'CANCELLED' | 'COMPLETED'
export interface ActiveBooking { id: number; checkIn: string; checkOut: string; status: BookingStatus }
export interface Me {
  phone: string; name: string; role: 'FRIEND' | 'ADMIN'
  telegramLinked: boolean; greeting: string | null
  activeBooking: ActiveBooking | null
}
export interface WillReplace { id: number; checkIn: string; checkOut: string }
export interface CreateResult { bookingId: number; willReplaceBooking: WillReplace | null }
export interface PastVisit { checkIn: string; checkOut: string; nights: number }
export interface ActiveBookingDetails extends ActiveBooking { comment: string | null }
export interface MyBookings { active: ActiveBookingDetails | null; history: PastVisit[] }
