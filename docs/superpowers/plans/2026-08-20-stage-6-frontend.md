# Этап 6: Гостевой фронтенд — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SPA `frontend/`: гость смотрит календарь, входит по номеру, бронирует/переносит/отменяет с OTP из Telegram; незнакомец подаёт заявку; владелец задаёт кастомные приветствия.

**Architecture:** React 19 + TS + Vite, TanStack Query для серверного состояния, Tailwind v4 с токенами палитры «Тёплая бумага», свой компонент календаря, одна страница без роутера. Dev — vite-прокси `/api → :8080`; Docker — nginx-контейнер в профиле `app`. Плюс бэкенд-добавка: `users.greeting` (V5), `greeting` в `/api/me`, `PATCH /api/admin/users/{id}`.

**Tech Stack:** Node 22, Vite 7, React 19, TypeScript, @tanstack/react-query v5, tailwindcss v4 (@tailwindcss/vite), Vitest + React Testing Library + MSW v2 (jsdom); backend — Spring Boot 4.0.7/Maven (паттерны этапа 5).

**Spec:** `docs/specs/2026-08-20-stage-6-frontend-guest-design.md`

## Global Constraints

- Ветка `stage-6-frontend` (спека уже закоммичена). Коммиты — по-русски.
- Интерфейс целиком по-русски. Даты: в API/состоянии — только ISO (`YYYY-MM-DD`), на экране — только `дд/мм/гггг`; все конвертации — в `frontend/src/lib/dates.ts`.
- Палитра (verbatim из спеки): paper `#faf7f0`, card `#f1ece0`, ink `#2b2620`, hanko `#b3402e`, muted `#8a8272`, leaf `#5a6b3b`, leafbg `#eef0e4`. Заголовки — serif (Georgia), текст — системный sans. Mobile-first: одна колонка `max-w-md mx-auto`.
- Фронт-команды: `cd frontend && npm test -- --run` (vitest), `npm run lint`, `npx tsc --noEmit`. Java: `cd backend-api && ./mvnw test -Dtest='Класс'`.
- `fetch` всегда `credentials: 'same-origin'`; никаких токенов в JS — cookie httpOnly.
- Ошибки API — `{code, message}`; фронт ветвится ТОЛЬКО по `code`, тексты для пользователя — свои русские (`message` бэкенда не показываем, он для логов).
- TDD: тест → красный → код → зелёный → коммит.

---

## Сессия 1 — каркас

### Task 1: Vite-каркас, Tailwind, тест-стенд, CI

**Files:**
- Create: `frontend/` (scaffold Vite), `frontend/vite.config.ts`, `frontend/src/index.css`, `frontend/src/App.tsx`, `frontend/src/main.tsx`, `frontend/src/test/setup.ts`, `frontend/src/App.test.tsx`
- Modify: `.github/workflows/ci.yml` (третий джоб)

**Interfaces:**
- Produces: рабочий стенд `npm test`/`lint`/`tsc`; токены Tailwind (`bg-paper`, `text-ink`, `text-hanko`, `bg-card`, `text-muted`, `bg-leafbg`, `text-leaf`, `font-display`); `QueryClientProvider` в `main.tsx`; прокси `/api → http://localhost:8080`.

- [ ] **Step 1: Scaffold и зависимости**

```bash
npm create vite@latest frontend -- --template react-ts
cd frontend
npm i @tanstack/react-query
npm i -D tailwindcss @tailwindcss/vite vitest @testing-library/react @testing-library/user-event @testing-library/jest-dom jsdom msw
```

- [ ] **Step 2: Конфиги**

`frontend/vite.config.ts`:

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // same-origin вместо CORS: cookie ходит сама, бэкенд не знает про фронт
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    globals: true,
  },
})
```

`frontend/src/index.css` (заменить содержимое):

```css
@import "tailwindcss";

@theme {
  --color-paper: #faf7f0;
  --color-card: #f1ece0;
  --color-ink: #2b2620;
  --color-hanko: #b3402e;
  --color-muted: #8a8272;
  --color-leaf: #5a6b3b;
  --color-leafbg: #eef0e4;
  --font-display: Georgia, "Times New Roman", serif;
}

body {
  @apply bg-card text-ink;
}
```

`frontend/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
```

`frontend/src/App.tsx` (заглушка этапа — наполняется задачами 5-11):

```tsx
export default function App() {
  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5">
      <header className="mb-4 flex items-center justify-between">
        <h1 className="font-display text-lg">
          Домик в Японии <span className="text-hanko">◉</span>
        </h1>
      </header>
      <p className="text-sm text-muted">Календарь скоро будет здесь.</p>
    </div>
  )
}
```

`frontend/src/test/setup.ts` (MSW-сервер подключат задачи 2+; пока только jest-dom):

```ts
import '@testing-library/jest-dom/vitest'
```

Удалить из шаблона: `src/App.css`, `src/assets/react.svg`, `public/vite.svg` и их импорты.

- [ ] **Step 3: Смоук-тест**

`frontend/src/App.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import App from './App'

test('рендерит шапку', () => {
  render(<App />)
  expect(screen.getByText(/Домик в Японии/)).toBeInTheDocument()
})
```

Run: `cd frontend && npm test -- --run` → PASS; `npm run lint` и `npx tsc --noEmit` — чисто. (`App.test.tsx` рендерит App без провайдера — пока App не использует хуки запросов, это ок; задача 11 обновит тест.)

- [ ] **Step 4: CI-джоб**

В `.github/workflows/ci.yml` добавить джоб по образцу соседних (`backend`, `bot`):

```yaml
  frontend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: npm ci
      - run: npm run lint
      - run: npx tsc --noEmit
      - run: npm test -- --run
```

- [ ] **Step 5: Commit**

```bash
git add frontend .github/workflows/ci.yml && git commit -m "feat: каркас фронтенда — Vite+React+Tailwind, тест-стенд, CI-джоб"
```

### Task 2: API-клиент, типы, MSW-стенд

**Files:**
- Create: `frontend/src/api/types.ts`, `frontend/src/api/client.ts`, `frontend/src/test/handlers.ts`
- Modify: `frontend/src/test/setup.ts`
- Test: `frontend/src/api/client.test.ts`

**Interfaces:**
- Produces (используют ВСЕ последующие фронт-задачи):

```ts
// types.ts — зеркало DTO бэкенда, дословно
export type DayStatus = 'FREE' | 'BOOKED' | 'BLOCKED'
export interface CalendarDay { date: string; status: DayStatus; guestName: string | null }
export interface CalendarResponse { days: CalendarDay[] }
export type BookingStatus = 'PENDING_OTP' | 'CONFIRMED' | 'CANCELLED'
export interface ActiveBooking { id: number; checkIn: string; checkOut: string; status: BookingStatus }
export interface Me {
  phone: string; name: string; role: 'FRIEND' | 'ADMIN'
  telegramLinked: boolean; greeting: string | null
  activeBooking: ActiveBooking | null
}
export interface WillReplace { id: number; checkIn: string; checkOut: string }
export interface CreateResult { bookingId: number; willReplaceBooking: WillReplace | null }
```

```ts
// client.ts
export class ApiError extends Error { code: string; status: number }
export const api: {
  get<T>(path: string): Promise<T>
  post<T>(path: string, body?: unknown): Promise<T>
  patch<T>(path: string, body?: unknown): Promise<T>
  del<T>(path: string): Promise<T>
}
```

- `frontend/src/test/handlers.ts` — MSW-обработчики с изменяемым in-memory состоянием `mockState` и `resetMockState()`; задачи 5-11 дополняют этот файл своими ручками.

- [ ] **Step 1: Написать падающий тест**

`frontend/src/api/client.test.ts`:

```ts
import { http, HttpResponse } from 'msw'
import { server } from './../test/setup'
import { api, ApiError } from './client'

test('успешный GET парсит JSON', async () => {
  server.use(http.get('/api/ping', () => HttpResponse.json({ ok: true })))
  await expect(api.get('/ping')).resolves.toEqual({ ok: true })
})

test('204 отдаёт undefined', async () => {
  server.use(http.post('/api/ping', () => new HttpResponse(null, { status: 204 })))
  await expect(api.post('/ping', {})).resolves.toBeUndefined()
})

test('ошибка {code,message} становится ApiError', async () => {
  server.use(http.get('/api/ping', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: 'Номер не найден' }, { status: 401 })))
  const err = await api.get('/ping').catch((e) => e)
  expect(err).toBeInstanceOf(ApiError)
  expect(err.code).toBe('UNKNOWN_PHONE')
  expect(err.status).toBe(401)
})

test('не-JSON ответ с ошибкой становится ApiError INTERNAL', async () => {
  server.use(http.get('/api/ping', () => new HttpResponse('boom', { status: 502 })))
  const err = await api.get('/ping').catch((e) => e)
  expect(err.code).toBe('INTERNAL_ERROR')
})
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd frontend && npm test -- --run` → FAIL (нет client.ts / server).

- [ ] **Step 3: Реализация**

`frontend/src/api/types.ts` — код из Interfaces выше, дословно.

`frontend/src/api/client.ts`:

```ts
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message)
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch('/api' + path, {
    method,
    credentials: 'same-origin', // httpOnly cookie ходит сама, токенов в JS нет
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  let json: unknown
  try {
    json = text ? JSON.parse(text) : undefined
  } catch {
    json = undefined
  }
  if (!res.ok) {
    const e = json as { code?: string; message?: string } | undefined
    throw new ApiError(e?.code ?? 'INTERNAL_ERROR', e?.message ?? 'Внутренняя ошибка', res.status)
  }
  return json as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
}
```

`frontend/src/test/handlers.ts` (стартовое состояние; ручки добавляются задачами позже):

```ts
import type { CalendarDay, Me } from '../api/types'

export interface MockState {
  me: Me | null
  days: CalendarDay[]
}

export const mockState: MockState = { me: null, days: [] }

export function resetMockState() {
  mockState.me = null
  mockState.days = []
}

export const handlers = [] as import('msw').RequestHandler[]
```

`frontend/src/test/setup.ts` — заменить на:

```ts
import '@testing-library/jest-dom/vitest'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { handlers, resetMockState } from './handlers'

export const server = setupServer(...handlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetMockState()
})
afterAll(() => server.close())
```

- [ ] **Step 4: Прогнать**

Run: `cd frontend && npm test -- --run` → PASS; `npx tsc --noEmit` чисто.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: api-клиент с типизированной ApiError, типы DTO, MSW-стенд"
```

---

## Сессия 2 — кастомные приветствия (бэкенд)

### Task 3: V5 greeting + /api/me + PATCH админа

**Files:**
- Create: `backend-api/src/main/resources/db/migration/V5__users_greeting.sql`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/user/UserAccount.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/user/WhitelistService.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/auth/MeController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/admin/AdminUserController.java`
- Test: дополнить `backend-api/src/test/java/com/batowka/guestbooking/admin/AdminUserTest.java` и `backend-api/src/test/java/com/batowka/guestbooking/auth/MeControllerTest.java`

**Interfaces:**
- Produces: `GET /api/me` → поле `greeting: string|null`; `PATCH /api/admin/users/{id}` `{greeting}` (null стирает) → 204, 404 на неживого. Фронт (Task 7) читает `greeting`.

- [ ] **Step 1: Написать падающие тесты**

В `AdminUserTest`:

```java
    @Test
    void patchSetsAndClearsGreeting() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81311100005', 'Миша') returning id", Long.class);

        mvc.perform(patch("/api/admin/users/" + id).cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("{\"greeting\": \"Мишаня! Футон проветрен\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select greeting from users where id = " + id, String.class))
                .isEqualTo("Мишаня! Футон проветрен");

        mvc.perform(patch("/api/admin/users/" + id).cookie(adminAuth())
                        .contentType(APPLICATION_JSON).content("{\"greeting\": null}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select greeting from users where id = " + id, String.class)).isNull();
    }

    @Test
    void patchDeletedUserGives404() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, deleted_at) values ('+81311100006', 'Бывший', now()) returning id",
                Long.class);
        mvc.perform(patch("/api/admin/users/" + id).cookie(adminAuth())
                        .contentType(APPLICATION_JSON).content("{\"greeting\": \"x\"}"))
                .andExpect(status().isNotFound());
    }
```

(добавить импорт `patch` из MockMvcRequestBuilders.)

В `MeControllerTest`:

```java
    @Test
    void meCarriesGreeting() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, greeting) values ('+81322200001', 'Маша', 'С возвращением!') returning id",
                Long.class);
        mvc.perform(get("/api/me").cookie(auth(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.greeting").value("С возвращением!"));
    }
```

(хелперы auth/jwt — по образцу существующих в классе.)

- [ ] **Step 2: Убедиться, что падают**

Run: `cd backend-api && ./mvnw test -Dtest='AdminUserTest,MeControllerTest'` → FAIL (нет колонки/эндпоинта/поля).

- [ ] **Step 3: Реализация**

`V5__users_greeting.sql`:

```sql
-- Личное приветствие владельца гостю; NULL = фронт покажет «Привет, {имя}!»
ALTER TABLE users ADD COLUMN greeting VARCHAR(300);
```

`UserAccount` — поле:

```java
    @Column(length = 300)
    private String greeting;
```

`WhitelistService` — метод:

```java
    /** null стирает приветствие — фронт вернётся к «Привет, {имя}!». */
    @Transactional
    public void setGreeting(long id, String greeting) {
        UserAccount user = users.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserNotFoundException::new);
        user.setGreeting(greeting);
        users.save(user);
    }
```

`AdminUserController`:

```java
    public record GreetingRequest(@Size(max = 300) String greeting) {
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setGreeting(@PathVariable long id, @Valid @RequestBody GreetingRequest body) {
        whitelist.setGreeting(id, body.greeting());
    }
```

(импорт `PatchMapping`.)

`MeController` — в `MeResponse` добавить `String greeting` (после `telegramLinked`, до `activeBooking` — порядок полей записи должен совпасть с вызовом), в `me()` передавать `user.getGreeting()`.

- [ ] **Step 4: Прогнать**

Run: `cd backend-api && ./mvnw test -Dtest='AdminUserTest,MeControllerTest'` → PASS; затем полный `./mvnw test` → зелёный.

- [ ] **Step 5: Commit**

```bash
git add backend-api && git commit -m "feat: кастомные приветствия — V5, greeting в /api/me, PATCH /api/admin/users/{id}"
```

---

## Сессия 3 — календарь

### Task 4: lib/dates — граница форматов

**Files:**
- Create: `frontend/src/lib/dates.ts`
- Test: `frontend/src/lib/dates.test.ts`

**Interfaces:**
- Produces (используют задачи 5-11):

```ts
export function isoToRu(iso: string): string          // '2026-09-10' → '10/09/2026'
export function monthTitle(isoFirstDay: string): string // '2026-09-01' → 'Сентябрь 2026'
export function nightsBetween(checkIn: string, checkOut: string): number // полуинтервал
export function addMonths(isoFirstDay: string, delta: number): string
export function monthGrid(isoFirstDay: string): (string | null)[] // 7×n, null = пусто, пн-первый
export function todayIso(): string
export function isoRange(fromInclusive: string, toExclusive: string): string[]
```

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/lib/dates.test.ts`:

```ts
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
```

- [ ] **Step 2: Убедиться, что падают** — `npm test -- --run` → FAIL.

- [ ] **Step 3: Реализация**

`frontend/src/lib/dates.ts`:

```ts
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

export function addMonths(isoFirstDay: string, delta: number): string {
  const [y, m] = isoFirstDay.split('-').map(Number)
  const total = y * 12 + (m - 1) + delta
  const ny = Math.floor(total / 12)
  const nm = (total % 12) + 1
  return `${ny}-${String(nm).padStart(2, '0')}-01`
}

export function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
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
```

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: lib/dates — ISO внутри, дд/мм/гггг и русские месяцы на экране"
```

### Task 5: Хуки запросов + календарь read-only

**Files:**
- Create: `frontend/src/api/queries.ts`, `frontend/src/components/Calendar.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/test/handlers.ts`
- Test: `frontend/src/components/Calendar.test.tsx`

**Interfaces:**
- Consumes: `api`, типы (Task 2), `lib/dates` (Task 4).
- Produces:

```ts
// queries.ts
export function useCalendar(fromIso: string, toIso: string) // useQuery<CalendarResponse> ['calendar', from, to]
export function useMe() // useQuery<Me|null> ['me']; ApiError 401 → data null, не ошибка

// Calendar.tsx
export interface Selection { checkIn: string | null; checkOut: string | null }
interface CalendarProps {
  monthStart: string                       // ISO первого дня левого месяца
  days: Map<string, CalendarDay>           // по обоим месяцам
  selection: Selection
  selectable: boolean                      // false до задачи 8 всё равно работает: клики игнорируются
  onShiftMonth: (delta: 1 | -1) => void
  onPick: (dayIso: string) => void         // Calendar отдаёт клик, ЛОГИКА выбора — в App (задача 8)
}
```

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/components/Calendar.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import type { CalendarDay } from '../api/types'
import { Calendar } from './Calendar'

function daysMap(entries: CalendarDay[]): Map<string, CalendarDay> {
  return new Map(entries.map((d) => [d.date, d]))
}

const base = {
  monthStart: '2026-09-01',
  selection: { checkIn: null, checkOut: null },
  selectable: true,
  onShiftMonth: vi.fn(),
}

test('показывает заголовки двух месяцев', () => {
  render(<Calendar {...base} days={daysMap([])} onPick={vi.fn()} />)
  expect(screen.getByText('Сентябрь 2026')).toBeInTheDocument()
  expect(screen.getByText('Октябрь 2026')).toBeInTheDocument()
})

test('занятый день показывает имя и некликабелен', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} days={daysMap([
    { date: '2026-09-10', status: 'BOOKED', guestName: 'Миша' },
  ])} />)
  const day = screen.getByRole('button', { name: /10 сентября.*занято.*Миша/i })
  expect(day).toBeDisabled()
  await userEvent.click(day)
  expect(onPick).not.toHaveBeenCalled()
})

test('заблокированный день некликабелен', () => {
  render(<Calendar {...base} onPick={vi.fn()} days={daysMap([
    { date: '2026-09-23', status: 'BLOCKED', guestName: null },
  ])} />)
  expect(screen.getByRole('button', { name: /23 сентября.*закрыто/i })).toBeDisabled()
})

test('свободный день кликабелен и зовёт onPick с ISO', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} days={daysMap([
    { date: '2026-09-15', status: 'FREE', guestName: null },
  ])} />)
  await userEvent.click(screen.getByRole('button', { name: /15 сентября.*свободно/i }))
  expect(onPick).toHaveBeenCalledWith('2026-09-15')
})

test('выбранный диапазон подсвечен', () => {
  render(<Calendar {...base} onPick={vi.fn()} days={daysMap([])}
    selection={{ checkIn: '2026-09-10', checkOut: '2026-09-13' }} />)
  expect(screen.getByRole('button', { name: /10 сентября/i })).toHaveAttribute('data-selected', 'true')
  expect(screen.getByRole('button', { name: /12 сентября/i })).toHaveAttribute('data-selected', 'true')
})
```

- [ ] **Step 2: Убедиться, что падают** — `npm test -- --run` → FAIL.

- [ ] **Step 3: Реализация**

`frontend/src/api/queries.ts`:

```ts
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
```

`frontend/src/components/Calendar.tsx`:

```tsx
import type { CalendarDay } from '../api/types'
import { addMonths, isoRange, monthGrid, monthTitle } from '../lib/dates'

export interface Selection {
  checkIn: string | null
  checkOut: string | null
}

interface CalendarProps {
  monthStart: string
  days: Map<string, CalendarDay>
  selection: Selection
  selectable: boolean
  onShiftMonth: (delta: 1 | -1) => void
  onPick: (dayIso: string) => void
}

const WEEKDAYS = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']
const MONTH_NAMES_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']

function ariaLabel(iso: string, day: CalendarDay | undefined): string {
  const [, m, d] = iso.split('-')
  const date = `${Number(d)} ${MONTH_NAMES_GEN[Number(m) - 1]}`
  if (day?.status === 'BOOKED') return `${date}, занято${day.guestName ? `, ${day.guestName}` : ''}`
  if (day?.status === 'BLOCKED') return `${date}, закрыто`
  return `${date}, свободно`
}

function Month({ start, days, selection, selectable, onPick }: {
  start: string
} & Pick<CalendarProps, 'days' | 'selection' | 'selectable' | 'onPick'>) {
  const selected = new Set(
    selection.checkIn && selection.checkOut
      ? isoRange(selection.checkIn, selection.checkOut)
      : selection.checkIn ? [selection.checkIn] : [],
  )
  return (
    <div className="mb-4">
      <div className="mb-1 text-center font-display text-sm">{monthTitle(start)}</div>
      <div className="grid grid-cols-7 gap-1 text-center text-[10px] text-muted">
        {WEEKDAYS.map((w) => <div key={w}>{w}</div>)}
      </div>
      <div className="grid grid-cols-7 gap-1 text-center text-sm">
        {monthGrid(start).map((iso, i) => {
          if (!iso) return <div key={i} />
          const day = days.get(iso)
          const status = day?.status ?? 'FREE'
          const disabled = !selectable || status !== 'FREE'
          return (
            <button
              key={iso}
              type="button"
              aria-label={ariaLabel(iso, day)}
              data-selected={selected.has(iso) ? 'true' : undefined}
              disabled={disabled}
              onClick={() => onPick(iso)}
              className={[
                'rounded-lg py-1.5',
                status === 'BOOKED' && 'bg-hanko/80 text-paper',
                status === 'BLOCKED' &&
                  'bg-[repeating-linear-gradient(45deg,#d8d0bf,#d8d0bf_3px,#e6dfd0_3px,#e6dfd0_6px)] text-muted',
                selected.has(iso) && 'bg-ink text-paper',
                status === 'FREE' && !selected.has(iso) && 'hover:bg-card',
              ].filter(Boolean).join(' ')}
            >
              {Number(iso.slice(8))}
            </button>
          )
        })}
      </div>
    </div>
  )
}

export function Calendar(props: CalendarProps) {
  const { monthStart, onShiftMonth } = props
  return (
    <section>
      <div className="mb-2 flex items-center justify-between text-sm text-muted">
        <button type="button" aria-label="Предыдущий месяц" onClick={() => onShiftMonth(-1)}>◀</button>
        <span className="text-xs">выбери даты</span>
        <button type="button" aria-label="Следующий месяц" onClick={() => onShiftMonth(1)}>▶</button>
      </div>
      <Month {...props} start={monthStart} />
      <Month {...props} start={addMonths(monthStart, 1)} />
      <div className="text-xs text-muted">
        <span className="mr-3"><span className="inline-block h-2 w-2 rounded-sm bg-hanko/80" /> занято</span>
        <span><span className="inline-block h-2 w-2 rounded-sm bg-[#d8d0bf]" /> закрыто</span>
      </div>
    </section>
  )
}
```

В `App.tsx` — подключить данные (пока read-only):

```tsx
import { useState } from 'react'
import { useCalendar } from './api/queries'
import { Calendar, type Selection } from './components/Calendar'
import { addMonths, todayIso } from './lib/dates'

export default function App() {
  const [monthStart, setMonthStart] = useState(todayIso().slice(0, 7) + '-01')
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })
  const calendar = useCalendar(monthStart, addMonths(monthStart, 2))
  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5">
      <header className="mb-4 flex items-center justify-between">
        <h1 className="font-display text-lg">
          Домик в Японии <span className="text-hanko">◉</span>
        </h1>
      </header>
      <Calendar
        monthStart={monthStart}
        days={days}
        selection={selection}
        selectable={false}
        onShiftMonth={(d) => setMonthStart((m) => addMonths(m, d))}
        onPick={() => setSelection(selection)}
      />
    </div>
  )
}
```

В `handlers.ts` добавить ручку календаря (и экспортировать в `handlers`):

```ts
import { http, HttpResponse } from 'msw'

export const handlers = [
  http.get('/api/calendar', () => HttpResponse.json({ days: mockState.days })),
]
```

`App.test.tsx` обновить: рендер через провайдер:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import App from './App'

export function renderApp() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><App /></QueryClientProvider>)
}

test('рендерит шапку и календарь', async () => {
  renderApp()
  expect(screen.getByText(/Домик в Японии/)).toBeInTheDocument()
  expect(await screen.findByText(/выбери даты/)).toBeInTheDocument()
})
```

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS; `npx tsc --noEmit` чисто.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: календарь read-only — два месяца, статусы, легенда, стрелки"
```

---

## Сессия 4 — вход и профиль

### Task 6: Вход + форма заявки

**Files:**
- Create: `frontend/src/components/LoginCard.tsx`
- Modify: `frontend/src/api/queries.ts` (мутации), `frontend/src/test/handlers.ts`, `frontend/src/App.tsx`
- Test: `frontend/src/components/LoginCard.test.tsx`

**Interfaces:**
- Consumes: `api`, `ApiError`, `useMe`.
- Produces: в `queries.ts` — `useLogin()`, `useLogout()`, `useAccessRequest()` (`useMutation`; login/logout инвалидируют `['me']`); компонент `<LoginCard />` (сам ходит в мутации, пропсов нет).

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/components/LoginCard.test.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../test/setup'
import { mockState } from '../test/handlers'
import { LoginCard } from './LoginCard'

function renderCard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><LoginCard /></QueryClientProvider>)
}

test('успешный вход дёргает POST /api/auth/login', async () => {
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990001122')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  // mockState.me выставляет MSW-ручка логина; ре-рендер профиля проверяет App-тест задачи 11
  expect(mockState.me?.phone).toBe('+79990001122')
})

test('UNKNOWN_PHONE раскрывает форму заявки', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })))
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990009999')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText(/нет в списке гостей/)).toBeInTheDocument()

  await userEvent.type(screen.getByPlaceholderText(/зовут/), 'Незнакомец')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/Заявка отправлена/)).toBeInTheDocument()
})

test('ALREADY_MEMBER в заявке показывает подсказку', async () => {
  server.use(
    http.post('/api/auth/login', () =>
      HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })),
    http.post('/api/access-requests', () =>
      HttpResponse.json({ code: 'ALREADY_MEMBER', message: '' }, { status: 409 })),
  )
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990008888')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await userEvent.type(await screen.findByPlaceholderText(/зовут/), 'Свой')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/уже в списке — просто войди/)).toBeInTheDocument()
})

test('RATE_LIMITED показывает «подожди минуту»', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'RATE_LIMITED', message: '' }, { status: 429 })))
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990007777')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText(/подожди минуту/)).toBeInTheDocument()
})
```

- [ ] **Step 2: Убедиться, что падают** — `npm test -- --run` → FAIL.

- [ ] **Step 3: Реализация**

В `queries.ts` добавить:

```ts
import { useMutation, useQueryClient } from '@tanstack/react-query'

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
```

`frontend/src/components/LoginCard.tsx`:

```tsx
import { useState } from 'react'
import { ApiError } from '../api/client'
import { useAccessRequest, useLogin } from '../api/queries'

export function LoginCard() {
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [message, setMessage] = useState('')
  const login = useLogin()
  const request = useAccessRequest()

  const loginCode = login.error instanceof ApiError ? login.error.code : null
  const requestCode = request.error instanceof ApiError ? request.error.code : null
  const showRequestForm = loginCode === 'UNKNOWN_PHONE'

  return (
    <div className="mb-4">
      <div className="rounded-2xl bg-card p-4">
        <div className="mb-2 text-sm">Вход для своих</div>
        <input
          className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-sm"
          placeholder="+7 ___ ___ __ __"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
        />
        <button
          type="button"
          className="mt-2 w-full rounded-xl bg-ink py-2 text-sm text-paper disabled:opacity-50"
          disabled={login.isPending || !phone.trim()}
          onClick={() => login.mutate(phone.trim())}
        >
          Войти
        </button>
        {loginCode === 'RATE_LIMITED' && (
          <p className="mt-2 text-xs text-hanko">Слишком часто — подожди минуту.</p>
        )}
        {loginCode === 'VALIDATION_ERROR' && (
          <p className="mt-2 text-xs text-hanko">Это не похоже на номер телефона.</p>
        )}
      </div>

      {showRequestForm && (
        <div className="mt-2 rounded-2xl border border-hanko/40 bg-hanko/5 p-4">
          {request.isSuccess ? (
            <p className="text-sm">Заявка отправлена — владелец свяжется с тобой.</p>
          ) : (
            <>
              <p className="mb-1 text-sm text-hanko">Этого номера нет в списке гостей</p>
              <p className="mb-2 text-xs text-muted">Оставь заявку — владелец добавит тебя.</p>
              <input
                className="mb-1.5 w-full rounded-lg border border-muted/40 bg-paper p-2 text-xs"
                placeholder="Как тебя зовут"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
              <input
                className="w-full rounded-lg border border-muted/40 bg-paper p-2 text-xs"
                placeholder="Откуда ты меня знаешь :)"
                value={message}
                onChange={(e) => setMessage(e.target.value)}
              />
              <button
                type="button"
                className="mt-2 w-full rounded-lg bg-hanko py-2 text-xs text-paper disabled:opacity-50"
                disabled={request.isPending || !name.trim()}
                onClick={() => request.mutate({ phone: phone.trim(), name: name.trim(), message: message.trim() || undefined })}
              >
                Отправить заявку
              </button>
              {requestCode === 'ALREADY_MEMBER' && (
                <p className="mt-2 text-xs">Этот номер уже в списке — просто войди.</p>
              )}
              {requestCode === 'RATE_LIMITED' && (
                <p className="mt-2 text-xs text-hanko">Слишком часто — подожди минуту.</p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
```

В `handlers.ts` добавить ручки (в массив `handlers`):

```ts
http.post('/api/auth/login', async ({ request }) => {
  const { phone } = (await request.json()) as { phone: string }
  mockState.me = {
    phone, name: 'Маша', role: 'FRIEND', telegramLinked: true,
    greeting: null, activeBooking: null,
  }
  return new HttpResponse(null, { status: 204 })
}),
http.post('/api/auth/logout', () => {
  mockState.me = null
  return new HttpResponse(null, { status: 204 })
}),
http.get('/api/me', () =>
  mockState.me
    ? HttpResponse.json(mockState.me)
    : HttpResponse.json({ code: 'UNAUTHORIZED', message: 'Требуется вход' }, { status: 401 })),
http.post('/api/access-requests', () => new HttpResponse(null, { status: 201 })),
```

В `App.tsx` — показать `LoginCard`, когда `useMe()` вернул null (и спрятать, когда есть me; полная сборка — задача 7 и 11):

```tsx
const me = useMe()
// в JSX перед <Calendar …>:
{me.data == null && !me.isLoading && <LoginCard />}
```

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: вход по номеру и форма заявки при «номер не найден»"
```

### Task 7: Профиль с приветствием + плашка Telegram

**Files:**
- Create: `frontend/src/components/ProfileCard.tsx`, `frontend/src/components/TelegramHint.tsx`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/src/components/ProfileCard.test.tsx`

**Interfaces:**
- Consumes: `Me`, `isoToRu`, `useLogout`.
- Produces:

```tsx
interface ProfileCardProps {
  me: Me
  onReschedule: () => void   // App включает режим переноса (задача 11)
  onCancel: () => void       // App открывает подтверждение отмены (задача 11)
  onEnterCode: () => void    // App открывает OtpModal для PENDING_OTP (задача 11)
  onCancelPending: () => void
}
export function ProfileCard(props: ProfileCardProps)
export function TelegramHint() // жёлтая плашка со ссылкой на бота
```

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/components/ProfileCard.test.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { vi } from 'vitest'
import type { Me } from '../api/types'
import { ProfileCard } from './ProfileCard'

const base: Me = {
  phone: '+79990001122', name: 'Аня', role: 'FRIEND',
  telegramLinked: true, greeting: null, activeBooking: null,
}
const noop = { onReschedule: vi.fn(), onCancel: vi.fn(), onEnterCode: vi.fn(), onCancelPending: vi.fn() }

function renderCard(me: Me) {
  const qc = new QueryClient()
  return render(<QueryClientProvider client={qc}><ProfileCard me={me} {...noop} /></QueryClientProvider>)
}

test('фолбэк «Привет, Имя!» без кастомного приветствия', () => {
  renderCard(base)
  expect(screen.getByText('Привет, Аня!')).toBeInTheDocument()
  expect(screen.getByText(/выбери даты в календаре/)).toBeInTheDocument()
})

test('кастомное приветствие вместо фолбэка', () => {
  renderCard({ ...base, greeting: 'Мишаня! Футон проветрен' })
  expect(screen.getByText('Мишаня! Футон проветрен')).toBeInTheDocument()
  expect(screen.queryByText(/Привет,/)).not.toBeInTheDocument()
})

test('CONFIRMED-бронь: даты дд/мм/гггг, бейдж, кнопки', () => {
  renderCard({ ...base, activeBooking: { id: 5, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  expect(screen.getByText(/10\/09\/2026/)).toBeInTheDocument()
  expect(screen.getByText('подтверждена')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Перенести' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Отменить' })).toBeInTheDocument()
})

test('PENDING_OTP-бронь: бейдж «ждёт код» и свои кнопки', () => {
  renderCard({ ...base, activeBooking: { id: 6, checkIn: '2026-10-01', checkOut: '2026-10-03', status: 'PENDING_OTP' } })
  expect(screen.getByText('ждёт код')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Ввести код' })).toBeInTheDocument()
})
```

- [ ] **Step 2: Убедиться, что падают** — FAIL.

- [ ] **Step 3: Реализация**

`frontend/src/components/TelegramHint.tsx`:

```tsx
export function TelegramHint() {
  return (
    <div className="mb-3 rounded-2xl border border-[#dbc47f] bg-[#fdf3d7] p-3 text-xs text-[#6b5a1f]">
      ✈ Telegram не привязан — напиши боту и поделись контактом, иначе код подтверждения не придёт.
    </div>
  )
}
```

`frontend/src/components/ProfileCard.tsx`:

```tsx
import type { Me } from '../api/types'
import { useLogout } from '../api/queries'
import { isoToRu } from '../lib/dates'
import { TelegramHint } from './TelegramHint'

interface ProfileCardProps {
  me: Me
  onReschedule: () => void
  onCancel: () => void
  onEnterCode: () => void
  onCancelPending: () => void
}

export function ProfileCard({ me, onReschedule, onCancel, onEnterCode, onCancelPending }: ProfileCardProps) {
  const logout = useLogout()
  const b = me.activeBooking
  return (
    <div className="mb-4">
      <div className="mb-1 flex items-center justify-between text-xs text-muted">
        <span>{me.phone}</span>
        <button type="button" onClick={() => logout.mutate()}>выйти</button>
      </div>
      <p className="mb-2 font-display text-base leading-snug">
        {me.greeting ?? `Привет, ${me.name}!`}
      </p>
      {!me.telegramLinked && <TelegramHint />}
      {b == null && (
        <div className="rounded-2xl bg-card p-3 text-xs text-muted">
          Брони пока нет — выбери даты в календаре ниже.
        </div>
      )}
      {b?.status === 'CONFIRMED' && (
        <div className="rounded-2xl border border-leaf/40 bg-leafbg p-3 text-sm">
          Твоя бронь: <b>{isoToRu(b.checkIn)} → {isoToRu(b.checkOut)}</b>{' '}
          <span className="rounded-md bg-leaf px-1.5 py-0.5 text-[10px] text-paper">подтверждена</span>
          <div className="mt-2 flex gap-2 text-xs">
            <button type="button" onClick={onReschedule}
              className="flex-1 rounded-lg border border-ink py-1.5">Перенести</button>
            <button type="button" onClick={onCancel}
              className="flex-1 rounded-lg border border-hanko py-1.5 text-hanko">Отменить</button>
          </div>
        </div>
      )}
      {b?.status === 'PENDING_OTP' && (
        <div className="rounded-2xl border border-[#dbc47f] bg-[#fdf3d7] p-3 text-sm">
          Бронь {isoToRu(b.checkIn)} → {isoToRu(b.checkOut)}{' '}
          <span className="rounded-md bg-[#b99b3e] px-1.5 py-0.5 text-[10px] text-paper">ждёт код</span>
          <div className="mt-2 flex gap-2 text-xs">
            <button type="button" onClick={onEnterCode}
              className="flex-1 rounded-lg border border-ink py-1.5">Ввести код</button>
            <button type="button" onClick={onCancelPending}
              className="flex-1 rounded-lg border border-hanko py-1.5 text-hanko">Отменить</button>
          </div>
        </div>
      )}
    </div>
  )
}
```

В `App.tsx`: когда `me.data` есть — рендерить `ProfileCard` (обработчики пока no-op `() => {}`; задача 11 подключит настоящие), иначе `LoginCard`.

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: профиль — кастомное приветствие, состояния брони, плашка Telegram"
```

---

## Сессия 5 — бронирование

### Task 8: Логика выбора диапазона

**Files:**
- Create: `frontend/src/lib/selection.ts`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/src/lib/selection.test.ts`

**Interfaces:**
- Consumes: `CalendarDay`, `isoRange`.
- Produces:

```ts
// Чистая функция — вся логика выбора тестируется без DOM
export function pickDay(
  selection: Selection,
  dayIso: string,
  days: Map<string, CalendarDay>,
): Selection
```

Правила (из спеки §3): клик по свободному дню без выбора → `checkIn`; клик позже `checkIn`, когда все дни `[checkIn, день)` свободны → `checkOut` (сам день выезда МОЖЕТ быть занят/заблокирован — полуинтервал; но Calendar дизейблит не-FREE кнопки, поэтому App в режиме «есть checkIn» передаёт Calendar'у `checkoutCandidates` — см. Step 3); клик раньше/равный `checkIn` или через препятствие → новый `checkIn`; клик при полном выборе → новый `checkIn`.

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/lib/selection.test.ts`:

```ts
import type { CalendarDay } from '../api/types'
import { pickDay } from './selection'

const days = (busy: string[]): Map<string, CalendarDay> =>
  new Map(busy.map((d) => [d, { date: d, status: 'BOOKED' as const, guestName: null }]))

const none = { checkIn: null, checkOut: null }

test('первый клик — заезд', () => {
  expect(pickDay(none, '2026-09-10', days([]))).toEqual({ checkIn: '2026-09-10', checkOut: null })
})

test('второй клик позже — выезд', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-13', days([])))
    .toEqual({ checkIn: '2026-09-10', checkOut: '2026-09-13' })
})

test('выезд в день чужого заезда разрешён (полуинтервал)', () => {
  expect(pickDay({ checkIn: '2026-09-10', checkOut: null }, '2026-09-13', days(['2026-09-13'])))
    .toEqual({ checkIn: '2026-09-10', checkOut: '2026-09-13' })
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
```

- [ ] **Step 2: Убедиться, что падают** — FAIL.

- [ ] **Step 3: Реализация**

`frontend/src/lib/selection.ts`:

```ts
import type { CalendarDay } from '../api/types'
import type { Selection } from '../components/Calendar'
import { isoRange } from './dates'

export function pickDay(
  selection: Selection,
  dayIso: string,
  days: Map<string, CalendarDay>,
): Selection {
  const { checkIn, checkOut } = selection
  if (!checkIn || checkOut) return { checkIn: dayIso, checkOut: null }
  if (dayIso <= checkIn) return { checkIn: dayIso, checkOut: null }
  // все НОЧИ [checkIn, dayIso) свободны; сам день выезда может быть занят — полуинтервал
  const blocked = isoRange(checkIn, dayIso)
    .some((d) => (days.get(d)?.status ?? 'FREE') !== 'FREE')
  return blocked ? { checkIn: dayIso, checkOut: null } : { checkIn, checkOut: dayIso }
}
```

В `Calendar.tsx` — поддержать «кандидатов на выезд»: новый проп `checkoutCandidates?: Set<string>` — кнопка НЕ дизейблится, если `checkoutCandidates.has(iso)`, даже когда статус не FREE:

```tsx
const disabled = !selectable || (status !== 'FREE' && !props.checkoutCandidates?.has(iso))
```

В `App.tsx`: `selectable={me.data != null}`, `onPick={(d) => setSelection((s) => pickDay(s, d, days))}`; `checkoutCandidates` вычислять при наличии `checkIn` без `checkOut`: ближайший не-FREE день после checkIn включительно — только ОН кандидат (выезд в день чужого заезда), т.е.:

```tsx
const checkoutCandidates = (() => {
  if (!selection.checkIn || selection.checkOut) return undefined
  for (const iso of isoRange(selection.checkIn, addMonths(monthStart, 2))) {
    if (iso > selection.checkIn && (days.get(iso)?.status ?? 'FREE') !== 'FREE')
      return new Set([iso])
  }
  return undefined
})()
```

Дополнить `Calendar.test.tsx`:

```tsx
test('день из checkoutCandidates кликабелен несмотря на статус', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} checkoutCandidates={new Set(['2026-09-13'])}
    days={daysMap([{ date: '2026-09-13', status: 'BOOKED', guestName: 'Петя' }])} />)
  await userEvent.click(screen.getByRole('button', { name: /13 сентября/i }))
  expect(onPick).toHaveBeenCalledWith('2026-09-13')
})
```

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: выбор диапазона — полуинтервал, препятствия, кандидаты на выезд"
```

### Task 9: Шторка брони + мутация create

**Files:**
- Create: `frontend/src/components/BookingSheet.tsx`
- Modify: `frontend/src/api/queries.ts`, `frontend/src/test/handlers.ts`, `frontend/src/App.tsx`
- Test: `frontend/src/components/BookingSheet.test.tsx`

**Interfaces:**
- Consumes: `Selection`, `nightsBetween`, `isoToRu`, `CreateResult`, `Me`.
- Produces:

```ts
// queries.ts
export function useCreateBooking() // useMutation<CreateResult, ApiError, {checkIn, checkOut, comment?}>
```

```tsx
interface BookingSheetProps {
  selection: { checkIn: string; checkOut: string }  // оба выбраны
  willReplace: ActiveBooking | null                  // активная CONFIRMED из me — предупреждение
  onSubmit: (comment: string) => void                // App зовёт мутацию
  onDismiss: () => void
  pending: boolean
  errorCode: string | null                           // DATES_TAKEN | OVERLAPS_OWN_BOOKING | TELEGRAM_NOT_LINKED | null
}
export function BookingSheet(props: BookingSheetProps)
```

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/components/BookingSheet.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { BookingSheet } from './BookingSheet'

const base = {
  selection: { checkIn: '2026-09-10', checkOut: '2026-09-13' },
  willReplace: null, onSubmit: vi.fn(), onDismiss: vi.fn(),
  pending: false, errorCode: null as string | null,
}

test('показывает даты дд/мм/гггг и ночи', () => {
  render(<BookingSheet {...base} />)
  expect(screen.getByText(/10\/09\/2026/)).toBeInTheDocument()
  expect(screen.getByText(/3 ночи/)).toBeInTheDocument()
})

test('отправляет комментарий', async () => {
  const onSubmit = vi.fn()
  render(<BookingSheet {...base} onSubmit={onSubmit} />)
  await userEvent.type(screen.getByPlaceholderText(/Комментарий/), 'приеду с женой')
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))
  expect(onSubmit).toHaveBeenCalledWith('приеду с женой')
})

test('предупреждает о замене активной брони', () => {
  render(<BookingSheet {...base}
    willReplace={{ id: 4, checkIn: '2026-08-01', checkOut: '2026-08-05', status: 'CONFIRMED' }} />)
  expect(screen.getByText(/заменит твою бронь 01\/08\/2026/)).toBeInTheDocument()
})

test('DATES_TAKEN показывает «даты только что заняли»', () => {
  render(<BookingSheet {...base} errorCode="DATES_TAKEN" />)
  expect(screen.getByText(/только что заняли/)).toBeInTheDocument()
})

test('TELEGRAM_NOT_LINKED ведёт к боту', () => {
  render(<BookingSheet {...base} errorCode="TELEGRAM_NOT_LINKED" />)
  expect(screen.getByText(/привяжи Telegram/)).toBeInTheDocument()
})
```

- [ ] **Step 2: Убедиться, что падают** — FAIL.

- [ ] **Step 3: Реализация**

В `queries.ts`:

```ts
import type { CreateResult } from './types'

export function useCreateBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { checkIn: string; checkOut: string; comment?: string }) =>
      api.post<CreateResult>('/bookings', body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
    },
  })
}
```

`frontend/src/components/BookingSheet.tsx`:

```tsx
import { useState } from 'react'
import type { ActiveBooking } from '../api/types'
import { isoToRu, nightsBetween } from '../lib/dates'

const ERROR_TEXTS: Record<string, string> = {
  DATES_TAKEN: 'Эти даты только что заняли — выбери другие.',
  OVERLAPS_OWN_BOOKING: 'Эти даты пересекаются с твоей текущей бронью.',
  TELEGRAM_NOT_LINKED: 'Сначала привяжи Telegram — напиши боту и поделись контактом.',
}

function nightsWord(n: number): string {
  const mod10 = n % 10, mod100 = n % 100
  if (mod10 === 1 && mod100 !== 11) return `${n} ночь`
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${n} ночи`
  return `${n} ночей`
}

interface BookingSheetProps {
  selection: { checkIn: string; checkOut: string }
  willReplace: ActiveBooking | null
  onSubmit: (comment: string) => void
  onDismiss: () => void
  pending: boolean
  errorCode: string | null
}

export function BookingSheet({ selection, willReplace, onSubmit, onDismiss, pending, errorCode }: BookingSheetProps) {
  const [comment, setComment] = useState('')
  return (
    <div className="fixed inset-x-0 bottom-0 z-10 mx-auto max-w-md rounded-t-3xl bg-card p-4 shadow-[0_-6px_18px_rgba(0,0,0,0.10)]">
      <button type="button" aria-label="Свернуть" onClick={onDismiss}
        className="mx-auto mb-2 block h-1 w-9 rounded bg-muted/50" />
      <p className="mb-2 text-sm">
        Заезд <b>{isoToRu(selection.checkIn)}</b> → выезд <b>{isoToRu(selection.checkOut)}</b>{' '}
        · {nightsWord(nightsBetween(selection.checkIn, selection.checkOut))}
      </p>
      {willReplace && (
        <p className="mb-2 rounded-lg bg-hanko/10 p-2 text-xs text-hanko">
          Подтверждение заменит твою бронь {isoToRu(willReplace.checkIn)} → {isoToRu(willReplace.checkOut)}.
        </p>
      )}
      <input
        className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-sm"
        placeholder="Комментарий (необязательно)"
        maxLength={500}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
      <button
        type="button"
        className="mt-2 w-full rounded-xl bg-ink py-2.5 text-sm text-paper disabled:opacity-50"
        disabled={pending}
        onClick={() => onSubmit(comment.trim())}
      >
        Забронировать
      </button>
      {errorCode && (
        <p className="mt-2 text-xs text-hanko">{ERROR_TEXTS[errorCode] ?? 'Что-то пошло не так — попробуй ещё раз.'}</p>
      )}
      <p className="mt-1.5 text-center text-xs text-muted">код подтверждения придёт в Telegram</p>
    </div>
  )
}
```

В `handlers.ts` — ручка создания:

```ts
http.post('/api/bookings', async ({ request }) => {
  const body = (await request.json()) as { checkIn: string; checkOut: string }
  if (mockState.me) {
    mockState.me.activeBooking = { id: 100, checkIn: body.checkIn, checkOut: body.checkOut, status: 'PENDING_OTP' }
  }
  return HttpResponse.json({ bookingId: 100, willReplaceBooking: null }, { status: 201 })
}),
```

В `App.tsx`: когда оба конца выбраны и гость залогинен — показать `BookingSheet`; `onSubmit` → `createBooking.mutate(...)`, по `onSuccess` — открыть OTP-модалку (появится в задаче 10; до неё — просто сбросить selection); `willReplace` = `me.data?.activeBooking?.status === 'CONFIRMED' ? me.data.activeBooking : null`; `errorCode` из `createBooking.error instanceof ApiError ? .code : null`; `onDismiss` сбрасывает selection и `createBooking.reset()`.

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: шторка брони — ночи, предупреждение о замене, ошибки по кодам"
```

### Task 10: OTP-модалка

**Files:**
- Create: `frontend/src/components/OtpModal.tsx`
- Modify: `frontend/src/api/queries.ts`, `frontend/src/test/handlers.ts`
- Test: `frontend/src/components/OtpModal.test.tsx`

**Interfaces:**
- Consumes: `api`, `ApiError`.
- Produces:

```ts
// queries.ts
export function useConfirmBooking() // ({bookingId, code}) → POST /bookings/{id}/confirm; onSuccess: invalidate me+calendar
export function useResendCode()    // (bookingId) → POST /bookings/{id}/resend-code
export function useCancelPending() // () → DELETE /bookings/pending; invalidate me+calendar
```

```tsx
interface OtpModalProps {
  bookingId: number
  subtitle: string            // «заезд 10/09/2026 → выезд 13/09/2026» или «отмена брони»
  showCancelPending: boolean  // true только в create-флоу
  onDone: () => void          // успех подтверждения
  onClose: () => void         // закрыть/отменено
}
export function OtpModal(props: OtpModalProps)
```

Поведение: 6 ячеек (единый скрытый input + визуальные ячейки — проще и надёжнее фокуса по ячейкам), «Подтвердить» активна при 6 цифрах; resend-кнопка с локальным таймером 60с, стартующим при открытии и после каждого resend; `RESEND_TOO_SOON` тоже перезапускает таймер. Ошибки: `INVALID_CODE` «Неверный код», `CODE_EXPIRED` «Код сгорел — отправь новый», `NO_ACTIVE_CODE` «Кода нет — отправь новый», `BOOKING_EXPIRED` «Бронь истекла — начни заново» (+ кнопка закрыть).

- [ ] **Step 1: Написать падающие тесты**

`frontend/src/components/OtpModal.test.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { vi } from 'vitest'
import { server } from '../test/setup'
import { OtpModal } from './OtpModal'

function renderModal(props: Partial<React.ComponentProps<typeof OtpModal>> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onDone = vi.fn(), onClose = vi.fn()
  render(
    <QueryClientProvider client={qc}>
      <OtpModal bookingId={100} subtitle="заезд 10/09/2026 → выезд 13/09/2026"
        showCancelPending onDone={onDone} onClose={onClose} {...props} />
    </QueryClientProvider>,
  )
  return { onDone, onClose }
}

test('успешное подтверждение зовёт onDone', async () => {
  const { onDone } = renderModal()
  await userEvent.type(screen.getByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  expect(onDone).toHaveBeenCalled()
})

test('INVALID_CODE показывает «Неверный код»', async () => {
  server.use(http.post('/api/bookings/100/confirm', () =>
    HttpResponse.json({ code: 'INVALID_CODE', message: '' }, { status: 400 })))
  renderModal()
  await userEvent.type(screen.getByLabelText('Код из Telegram'), '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  expect(await screen.findByText('Неверный код')).toBeInTheDocument()
})

test('CODE_EXPIRED предлагает новый код', async () => {
  server.use(http.post('/api/bookings/100/confirm', () =>
    HttpResponse.json({ code: 'CODE_EXPIRED', message: '' }, { status: 400 })))
  renderModal()
  await userEvent.type(screen.getByLabelText('Код из Telegram'), '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  expect(await screen.findByText(/Код сгорел/)).toBeInTheDocument()
})

test('resend заблокирован таймером сразу после открытия', () => {
  renderModal()
  expect(screen.getByRole('button', { name: /Отправить новый/ })).toBeDisabled()
})

test('«Отменить бронь» дёргает DELETE /pending и закрывает', async () => {
  let deleted = false
  server.use(http.delete('/api/bookings/pending', () => {
    deleted = true
    return new HttpResponse(null, { status: 204 })
  }))
  const { onClose } = renderModal()
  await userEvent.click(screen.getByRole('button', { name: 'Отменить бронь' }))
  expect(deleted).toBe(true)
  expect(onClose).toHaveBeenCalled()
})

test('без showCancelPending кнопки отмены нет', () => {
  renderModal({ showCancelPending: false })
  expect(screen.queryByRole('button', { name: 'Отменить бронь' })).not.toBeInTheDocument()
})
```

- [ ] **Step 2: Убедиться, что падают** — FAIL.

- [ ] **Step 3: Реализация**

В `queries.ts`:

```ts
export function useConfirmBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ bookingId, code }: { bookingId: number; code: string }) =>
      api.post<void>(`/bookings/${bookingId}/confirm`, { code }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
    },
  })
}

export function useResendCode() {
  return useMutation({
    mutationFn: (bookingId: number) => api.post<void>(`/bookings/${bookingId}/resend-code`),
  })
}

export function useCancelPending() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.del<void>('/bookings/pending'),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
    },
  })
}
```

`frontend/src/components/OtpModal.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { useCancelPending, useConfirmBooking, useResendCode } from '../api/queries'

const ERROR_TEXTS: Record<string, string> = {
  INVALID_CODE: 'Неверный код',
  CODE_EXPIRED: 'Код сгорел — отправь новый',
  NO_ACTIVE_CODE: 'Кода нет — отправь новый',
  BOOKING_EXPIRED: 'Бронь истекла — начни заново',
  RESEND_TOO_SOON: 'Новый код — не чаще раза в минуту',
}

interface OtpModalProps {
  bookingId: number
  subtitle: string
  showCancelPending: boolean
  onDone: () => void
  onClose: () => void
}

export function OtpModal({ bookingId, subtitle, showCancelPending, onDone, onClose }: OtpModalProps) {
  const [code, setCode] = useState('')
  const [cooldown, setCooldown] = useState(60)
  const confirm = useConfirmBooking()
  const resend = useResendCode()
  const cancelPending = useCancelPending()

  useEffect(() => {
    if (cooldown <= 0) return
    const t = setInterval(() => setCooldown((c) => c - 1), 1000)
    return () => clearInterval(t)
  }, [cooldown > 0])

  const errorCode =
    (confirm.error instanceof ApiError && confirm.error.code) ||
    (resend.error instanceof ApiError && resend.error.code) || null

  return (
    <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
      <div className="w-full max-w-xs rounded-2xl bg-paper p-4 shadow-xl">
        <p className="text-center text-sm">Код из Telegram</p>
        <p className="mb-3 text-center text-xs text-muted">{subtitle}</p>
        <input
          aria-label="Код из Telegram"
          inputMode="numeric"
          autoFocus
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          className="mb-3 w-full rounded-xl border border-muted/40 bg-paper p-2 text-center font-mono text-2xl tracking-[0.4em]"
        />
        <button
          type="button"
          className="w-full rounded-xl bg-ink py-2 text-sm text-paper disabled:opacity-50"
          disabled={code.length !== 6 || confirm.isPending}
          onClick={() => confirm.mutate({ bookingId, code }, { onSuccess: onDone })}
        >
          Подтвердить
        </button>
        {errorCode && (
          <p className="mt-2 text-center text-xs text-hanko">
            {ERROR_TEXTS[errorCode] ?? 'Не получилось — попробуй ещё раз'}
          </p>
        )}
        <div className="mt-3 flex items-center justify-between text-xs">
          <button
            type="button"
            className="text-muted disabled:opacity-50"
            disabled={cooldown > 0 || resend.isPending}
            onClick={() =>
              resend.mutate(bookingId, {
                onSettled: () => { setCooldown(60); setCode('') },
              })
            }
          >
            Отправить новый{cooldown > 0 ? ` (0:${String(cooldown).padStart(2, '0')})` : ''}
          </button>
          {showCancelPending ? (
            <button type="button" className="text-hanko"
              onClick={() => cancelPending.mutate(undefined, { onSettled: onClose })}>
              Отменить бронь
            </button>
          ) : (
            <button type="button" className="text-muted" onClick={onClose}>Закрыть</button>
          )}
        </div>
      </div>
    </div>
  )
}
```

В `handlers.ts` — ручки confirm/pending:

```ts
http.post('/api/bookings/:id/confirm', () => {
  if (mockState.me?.activeBooking) mockState.me.activeBooking.status = 'CONFIRMED'
  return new HttpResponse(null, { status: 204 })
}),
http.post('/api/bookings/:id/resend-code', () => new HttpResponse(null, { status: 204 })),
http.delete('/api/bookings/pending', () => {
  if (mockState.me) mockState.me.activeBooking = null
  return new HttpResponse(null, { status: 204 })
}),
```

- [ ] **Step 4: Прогнать** — `npm test -- --run` → PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: OTP-модалка — подтверждение, resend с таймером, отмена pending"
```

---

## Сессия 6 — сборка флоу: создание, перенос, отмена

### Task 11: App-оркестрация

**Files:**
- Modify: `frontend/src/api/queries.ts` (`useRescheduleBooking`, `useCancelBooking`), `frontend/src/App.tsx`, `frontend/src/test/handlers.ts`
- Test: `frontend/src/App.flow.test.tsx`

**Interfaces:**
- Consumes: всё из задач 5-10.
- Produces:

```ts
export function useRescheduleBooking() // ({bookingId, checkIn, checkOut}) → PATCH /bookings/{id}
export function useCancelBooking()    // (bookingId) → DELETE /bookings/{id}
```

App-состояние (одно поле-автомат вместо россыпи булевых):

```ts
type Flow =
  | { kind: 'idle' }
  | { kind: 'selecting-reschedule' }             // режим переноса: календарь выбирает новые даты
  | { kind: 'otp'; bookingId: number; subtitle: string; cancelable: boolean }
  | { kind: 'confirm-cancel' }                   // диалог «точно отменить?»
```

Поведение:
- Выбраны оба конца + `flow.kind === 'idle'` → BookingSheet → submit → `useCreateBooking` → success: `flow = otp(bookingId, 'заезд … → выезд …', cancelable: true)`, selection сброшен.
- Выбраны оба конца + `kind === 'selecting-reschedule'` → BookingSheet (без комментария не заморачиваемся — комментарий игнорируется бэкендом при переносе, поле скрывать не обязательно; кнопка «Перенести») → `useRescheduleBooking` → success: `flow = otp(id, 'перенос на …', cancelable: false)`.
- ProfileCard: `onReschedule` → `kind='selecting-reschedule'` (подсказка над календарём «выбери новые даты»); `onCancel` → `kind='confirm-cancel'` → диалог с «Да, отменить» → `useCancelBooking` → success: `flow = otp(id, 'отмена брони', cancelable: false)`; `onEnterCode` → `flow = otp(activeBooking.id, 'заезд …', cancelable: true)`; `onCancelPending` → `useCancelPending().mutate()`.
- `OtpModal.onDone/onClose` → `flow = idle`.
- `TELEGRAM_NOT_LINKED` из мутаций показывается в BookingSheet (уже сделано) и как `TelegramHint` в профиле.

- [ ] **Step 1: Написать падающие флоу-тесты**

`frontend/src/App.flow.test.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { mockState } from './test/handlers'
import App from './App'

function renderApp() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><App /></QueryClientProvider>)
}

function seedFreeSeptember() {
  // календарь мока отдаёт только НЕ-свободные дни; свободные фронт достраивает сам
  mockState.days = []
}

function loginAs(me: Partial<typeof mockState.me>) {
  mockState.me = {
    phone: '+79990001122', name: 'Маша', role: 'FRIEND', telegramLinked: true,
    greeting: null, activeBooking: null, ...me,
  } as NonNullable<typeof mockState.me>
}

test('полный create-флоу: выбор дат → шторка → OTP → подтверждена', async () => {
  seedFreeSeptember()
  loginAs({})
  renderApp()
  await screen.findByText(/Привет, Маша!/)

  await userEvent.click(await screen.findByRole('button', { name: /10 .*свободно/i }))
  await userEvent.click(screen.getByRole('button', { name: /13 .*свободно/i }))
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))

  await userEvent.type(await screen.findByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))

  expect(await screen.findByText('подтверждена')).toBeInTheDocument()
})

test('отмена CONFIRMED: диалог → OTP без «Отменить бронь»', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()
  await userEvent.click(await screen.findByRole('button', { name: 'Отменить' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Да, отменить' }))
  await screen.findByLabelText('Код из Telegram')
  expect(screen.queryByRole('button', { name: 'Отменить бронь' })).not.toBeInTheDocument()
})

test('перенос: режим выбора новых дат с подсказкой', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  expect(await screen.findByText(/выбери новые даты/)).toBeInTheDocument()
})
```

Примечание: тесты кликают дни текущего+следующего месяца — в `handlers.ts` ручка календаря уже отдаёт пусто (всё свободно), поэтому найти кнопку `/10 .*свободно/` можно всегда: реальный месяц зависит от `todayIso()`, поэтому в тестах ищем ЛЮБУЮ кнопку с числом 10: `getByRole('button', { name: /^10 /… }` не сработает из-за aria-формата «10 сентября, свободно» — берём `getAllByRole('button', { name: /свободно/ })` и фильтруем по тексту «10»/«13» первого месяца:

```tsx
async function clickFreeDay(dayNum: string) {
  const buttons = await screen.findAllByRole('button', { name: /свободно/ })
  const target = buttons.find((b) => b.textContent === dayNum)!
  await userEvent.click(target)
}
```

(использовать `clickFreeDay('10')` / `clickFreeDay('13')` вместо прямых `getByRole` в первом тесте.)

- [ ] **Step 2: Убедиться, что падают** — FAIL.

- [ ] **Step 3: Реализация**

В `queries.ts`:

```ts
export function useRescheduleBooking() {
  return useMutation({
    mutationFn: ({ bookingId, checkIn, checkOut }: { bookingId: number; checkIn: string; checkOut: string }) =>
      api.patch<void>(`/bookings/${bookingId}`, { checkIn, checkOut }),
  })
}

export function useCancelBooking() {
  return useMutation({
    mutationFn: (bookingId: number) => api.del<void>(`/bookings/${bookingId}`),
  })
}
```

В `handlers.ts`:

```ts
http.patch('/api/bookings/:id', () => new HttpResponse(null, { status: 204 })),
http.delete('/api/bookings/:id', () => new HttpResponse(null, { status: 204 })),
```

`App.tsx` — финальная сборка (полный файл):

```tsx
import { useState } from 'react'
import { ApiError } from './api/client'
import {
  useCalendar, useCancelBooking, useCancelPending, useCreateBooking,
  useMe, useRescheduleBooking,
} from './api/queries'
import { BookingSheet } from './components/BookingSheet'
import { Calendar, type Selection } from './components/Calendar'
import { LoginCard } from './components/LoginCard'
import { OtpModal } from './components/OtpModal'
import { ProfileCard } from './components/ProfileCard'
import { addMonths, isoRange, isoToRu, todayIso } from './lib/dates'
import { pickDay } from './lib/selection'

type Flow =
  | { kind: 'idle' }
  | { kind: 'selecting-reschedule' }
  | { kind: 'otp'; bookingId: number; subtitle: string; cancelable: boolean }
  | { kind: 'confirm-cancel' }

export default function App() {
  const [monthStart, setMonthStart] = useState(todayIso().slice(0, 7) + '-01')
  const [selection, setSelection] = useState<Selection>({ checkIn: null, checkOut: null })
  const [flow, setFlow] = useState<Flow>({ kind: 'idle' })

  const me = useMe()
  const calendar = useCalendar(monthStart, addMonths(monthStart, 2))
  const create = useCreateBooking()
  const reschedule = useRescheduleBooking()
  const cancel = useCancelBooking()
  const cancelPending = useCancelPending()

  const days = new Map((calendar.data?.days ?? []).map((d) => [d.date, d]))
  const active = me.data?.activeBooking ?? null
  const bothPicked = selection.checkIn != null && selection.checkOut != null

  const checkoutCandidates = (() => {
    if (!selection.checkIn || selection.checkOut) return undefined
    for (const iso of isoRange(selection.checkIn, addMonths(monthStart, 2))) {
      if (iso > selection.checkIn && (days.get(iso)?.status ?? 'FREE') !== 'FREE') return new Set([iso])
    }
    return undefined
  })()

  const resetSelection = () => setSelection({ checkIn: null, checkOut: null })

  const submitBooking = (comment: string) => {
    if (!selection.checkIn || !selection.checkOut) return
    if (flow.kind === 'selecting-reschedule' && active) {
      reschedule.mutate(
        { bookingId: active.id, checkIn: selection.checkIn, checkOut: selection.checkOut },
        {
          onSuccess: () => {
            resetSelection()
            setFlow({ kind: 'otp', bookingId: active.id, subtitle: `перенос на ${isoToRu(selection.checkIn!)} → ${isoToRu(selection.checkOut!)}`, cancelable: false })
          },
        },
      )
    } else {
      create.mutate(
        { checkIn: selection.checkIn, checkOut: selection.checkOut, comment: comment || undefined },
        {
          onSuccess: (r) => {
            resetSelection()
            setFlow({ kind: 'otp', bookingId: r.bookingId, subtitle: `заезд ${isoToRu(selection.checkIn!)} → выезд ${isoToRu(selection.checkOut!)}`, cancelable: true })
          },
        },
      )
    }
  }

  const sheetError = (flow.kind === 'selecting-reschedule' ? reschedule.error : create.error)
  const sheetErrorCode = sheetError instanceof ApiError ? sheetError.code : null

  return (
    <div className="mx-auto max-w-md min-h-screen bg-paper px-4 py-5 pb-40">
      <header className="mb-4">
        <h1 className="font-display text-lg">
          Домик в Японии <span className="text-hanko">◉</span>
        </h1>
      </header>

      {me.data == null && !me.isLoading && <LoginCard />}
      {me.data != null && (
        <ProfileCard
          me={me.data}
          onReschedule={() => { resetSelection(); setFlow({ kind: 'selecting-reschedule' }) }}
          onCancel={() => setFlow({ kind: 'confirm-cancel' })}
          onEnterCode={() => active && setFlow({ kind: 'otp', bookingId: active.id, subtitle: `заезд ${isoToRu(active.checkIn)} → выезд ${isoToRu(active.checkOut)}`, cancelable: true })}
          onCancelPending={() => cancelPending.mutate()}
        />
      )}

      {flow.kind === 'selecting-reschedule' && (
        <p className="mb-2 rounded-lg bg-card p-2 text-xs text-muted">
          Перенос: выбери новые даты в календаре.{' '}
          <button type="button" className="text-hanko" onClick={() => setFlow({ kind: 'idle' })}>Передумал</button>
        </p>
      )}

      <Calendar
        monthStart={monthStart}
        days={days}
        selection={selection}
        selectable={me.data != null}
        checkoutCandidates={checkoutCandidates}
        onShiftMonth={(d) => setMonthStart((m) => addMonths(m, d))}
        onPick={(iso) => setSelection((s) => pickDay(s, iso, days))}
      />

      {bothPicked && me.data != null && (flow.kind === 'idle' || flow.kind === 'selecting-reschedule') && (
        <BookingSheet
          selection={{ checkIn: selection.checkIn!, checkOut: selection.checkOut! }}
          willReplace={flow.kind === 'idle' && active?.status === 'CONFIRMED' ? active : null}
          onSubmit={submitBooking}
          onDismiss={() => { resetSelection(); create.reset(); reschedule.reset() }}
          pending={create.isPending || reschedule.isPending}
          errorCode={sheetErrorCode}
        />
      )}

      {flow.kind === 'confirm-cancel' && active && (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-ink/25 p-6">
          <div className="w-full max-w-xs rounded-2xl bg-paper p-4 text-sm shadow-xl">
            <p className="mb-3">Отменить бронь {isoToRu(active.checkIn)} → {isoToRu(active.checkOut)}?</p>
            <div className="flex gap-2 text-xs">
              <button type="button" className="flex-1 rounded-lg border border-ink py-2"
                onClick={() => setFlow({ kind: 'idle' })}>Оставить</button>
              <button type="button" className="flex-1 rounded-lg bg-hanko py-2 text-paper"
                onClick={() => cancel.mutate(active.id, {
                  onSuccess: () => setFlow({ kind: 'otp', bookingId: active.id, subtitle: 'отмена брони', cancelable: false }),
                })}>
                Да, отменить
              </button>
            </div>
          </div>
        </div>
      )}

      {flow.kind === 'otp' && (
        <OtpModal
          bookingId={flow.bookingId}
          subtitle={flow.subtitle}
          showCancelPending={flow.cancelable}
          onDone={() => setFlow({ kind: 'idle' })}
          onClose={() => setFlow({ kind: 'idle' })}
        />
      )}
    </div>
  )
}
```

(`Calendar` получает новый опциональный проп `checkoutCandidates?: Set<string>` — добавлен в задаче 8.)

- [ ] **Step 4: Прогнать всё** — `npm test -- --run` → PASS (все файлы), `npm run lint`, `npx tsc --noEmit` — чисто.

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: сборка гостевого флоу — создание, перенос, отмена через единый автомат"
```

---

## Сессия 7 — Docker, учебный файл, финал

### Task 12: nginx-контейнер в профиль app

**Files:**
- Create: `frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/.dockerignore`
- Modify: `docker-compose.dev.yml`, `README.md`

**Interfaces:**
- Produces: `docker compose -f docker-compose.dev.yml --profile app up --build` поднимает сайт на `http://localhost:3000` с проксированием `/api` на backend. Этап 8 переиспользует Dockerfile/nginx.conf.

- [ ] **Step 1: Файлы**

`frontend/Dockerfile`:

```dockerfile
# Multi-stage: сборка Vite, раздача — nginx (статика + прокси /api).
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
```

`frontend/nginx.conf`:

```nginx
server {
    listen 80;

    root /usr/share/nginx/html;
    index index.html;

    # SPA: любые пути отдают index.html (роутер появится в этапе 7)
    location / {
        try_files $uri /index.html;
    }

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

`frontend/.dockerignore`:

```
node_modules
dist
```

В `docker-compose.dev.yml` — сервис после `bot`:

```yaml
  frontend:
    build: ./frontend
    profiles: ["app"]
    ports:
      - "3000:80"
    depends_on:
      - backend
```

В README — обновить блок полного стека: «сайт — http://localhost:3000, API напрямую — :8080».

- [ ] **Step 2: Проверить вживую**

Run: `docker compose -f docker-compose.dev.yml --profile app up --build -d`, затем `curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/` → 200 и `curl -s "http://localhost:3000/api/calendar?from=2026-09-01&to=2026-09-07" | head -c 80` → JSON.

- [ ] **Step 3: Commit**

```bash
git add frontend docker-compose.dev.yml README.md && git commit -m "feat: frontend-контейнер — nginx со статикой и прокси /api, профиль app"
```

### Task 13: Учебный файл, полный прогон, ревью, PR

**Files:**
- Create: `docs/learning/06-frontend-integration.md`
- Modify: `docs/learning/README.md`

- [ ] **Step 1: Учебный файл** — краткий (владелец знает фронт), тутор-формат с якорями на реальный код. Разделы: (1) httpOnly cookie на фронте — почему в JS нет токена, как выглядит «я залогинен» (`useMe` и 401→null), выход как POST (якоря: `client.ts`, `queries.ts:useMe`); (2) same-origin через прокси vs CORS — vite proxy и nginx `/api/` (якоря: `vite.config.ts`, `nginx.conf`); (3) граница форматов дат — ISO на проводе, дд/мм/гггг в рендере, `lib/dates.ts` как единственная таможня (+ полуинтервал в `pickDay`); (4) MSW как контрактный мок — почему тесты ходят «по сети» (якоря: `test/handlers.ts`, `test/setup.ts`). Строка в `docs/learning/README.md` — диапазон `00…06`.

- [ ] **Step 2: Полный прогон**

Run: `cd frontend && npm run lint && npx tsc --noEmit && npm test -- --run && cd ../backend-api && ./mvnw test` → всё зелёное.

- [ ] **Step 3: Commit**

```bash
git add docs/learning && git commit -m "docs: разбор этапа 6 — cookie, прокси, граница дат, MSW (тутор-формат)"
```

- [ ] **Step 4: Финальное ревью** — скилл `superpowers:requesting-code-review` на диф `main...stage-6-frontend`; блокеры чинить, переносимое — в память проекта.

- [ ] **Step 5: PR**

```bash
git push -u origin stage-6-frontend
gh pr create --title "Этап 6: гостевой фронтенд — календарь, бронирование с OTP, заявки" --body "..."
```

(тело — резюме по секциям спеки + ссылка на неё; мерж — по решению владельца после зелёного CI.)

---

## Самопроверка плана (выполнена)

- Покрытие спеки: §2 → Tasks 1, 2, 4; §3 (стиль/календарь/шторка/OTP/вход/профиль) → Tasks 1, 5, 6, 7, 8, 9, 10, 11; §4 (приветствия) → Tasks 3, 7; §5 (dev-прокси, docker, CI) → Tasks 1, 12; §6 (тесты) → в каждой фронт-задаче + флоу-тесты Task 11; §7-8 → Task 13.
- Плейсхолдеров нет; каждый код-шаг содержит код.
- Сквозные типы: `Selection` (T5) ← `pickDay` (T8) ← App (T11); `checkoutCandidates` объявлен в T8, используется в T11; `useCreateBooking`→`CreateResult` (T9) ← App (T11); `OtpModalProps` (T10) ← App (T11); `greeting` в Me (T2/T3) ← ProfileCard (T7). Ручки MSW накапливаются в одном `handlers.ts` (T2 → T5 → T6 → T9 → T10 → T11).
- Известный нюанс для исполнителей: тесты Task 5/11 зависят от текущего месяца (`todayIso`) — хелпер `clickFreeDay` ищет кнопку по числу, а не по месяцу; если тест окажется хрупким на границе месяца, зафиксировать время через `vi.setSystemTime(new Date('2026-09-01'))` в setup конкретного теста.
