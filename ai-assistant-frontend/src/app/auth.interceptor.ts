import { inject } from '@angular/core';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth';
import { WorkspaceSession } from './workspace-session';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const session = inject(WorkspaceSession);
  // Prefer a usable token; fall back to raw stored token so a flaky client-side
  // exp parse does not strip Authorization and cause a cascade of 401 → login.
  const token = auth.getValidToken() ?? auth.getToken();

  const headers: Record<string, string> = {
    'X-Workspace-Id': session.id
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  return next(req.clone({ setHeaders: headers })).pipe(
    catchError((err: unknown) => {
      // Do NOT auto-logout / redirect here — that bounced users to login on every
      // agent 401. chat-home / guards handle session messaging instead.
      if (err instanceof HttpErrorResponse && err.status === 401 && !req.url.includes('/api/auth/')) {
        console.warn('[auth] 401 on', req.method, req.url);
      }
      return throwError(() => err);
    })
  );
};
