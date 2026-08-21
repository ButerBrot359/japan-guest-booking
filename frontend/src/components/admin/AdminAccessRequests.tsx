import { useState } from 'react'
import { useAccessRequests, useApproveRequest, useRejectRequest } from '../../api/queries'
import type { AccessRequestStatus } from '../../api/types'

const RU_STATUS: Record<AccessRequestStatus, string> = {
  PENDING: 'ожидает', APPROVED: 'одобрена', REJECTED: 'отклонена',
}

export function AdminAccessRequests() {
  const [view, setView] = useState<'pending' | 'history'>('pending')
  // История: переключатель статуса Одобренные/Отклонённые (один запрос за раз)
  const [histStatus, setHistStatus] = useState<AccessRequestStatus>('APPROVED')
  const status: AccessRequestStatus = view === 'pending' ? 'PENDING' : histStatus
  const list = useAccessRequests(status)
  const approve = useApproveRequest()
  const reject = useRejectRequest()

  const subTab = (active: boolean) =>
    `rounded-lg px-3 py-1 text-xs ${active ? 'bg-card font-semibold' : 'text-muted'}`

  return (
    <div>
      <div className="mb-3 flex gap-2">
        <button type="button" className={subTab(view === 'pending')} onClick={() => setView('pending')}>Ожидающие</button>
        <button type="button" className={subTab(view === 'history')} onClick={() => setView('history')}>История</button>
      </div>

      {view === 'history' && (
        <div className="mb-3 flex gap-2 text-xs">
          <button type="button" className={subTab(histStatus === 'APPROVED')} onClick={() => setHistStatus('APPROVED')}>Одобренные</button>
          <button type="button" className={subTab(histStatus === 'REJECTED')} onClick={() => setHistStatus('REJECTED')}>Отклонённые</button>
        </div>
      )}

      {(list.data ?? []).length === 0 && <p className="text-sm text-muted">Пусто.</p>}

      <div className="space-y-2">
        {(list.data ?? []).map((r) => (
          <div key={r.id} className="flex items-center justify-between rounded-xl bg-card p-3 text-sm">
            <div>
              <div className="font-semibold">{r.name} · {r.phone}</div>
              {r.message && <div className="text-xs text-muted">{r.message}</div>}
              {view === 'pending' && (
                <div className="text-xs text-muted">{new Date(r.createdAt).toLocaleDateString('ru-RU')}</div>
              )}
              {view === 'history' && (
                <div className="text-xs text-muted">
                  {RU_STATUS[r.status]}
                  {r.resolvedAt && ` · ${new Date(r.resolvedAt).toLocaleDateString('ru-RU')}`}
                </div>
              )}
            </div>
            {view === 'pending' && (
              <div className="flex gap-2 text-xs">
                <button type="button" className="rounded-lg border border-leaf px-3 py-1.5 text-leaf"
                  disabled={approve.isPending} onClick={() => approve.mutate(r.id)}>Добавить</button>
                <button type="button" className="rounded-lg border border-hanko px-3 py-1.5 text-hanko"
                  disabled={reject.isPending} onClick={() => reject.mutate(r.id)}>Отклонить</button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
