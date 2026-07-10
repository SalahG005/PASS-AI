import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { Auth } from './auth';
import { WorkspaceSession } from './workspace-session';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const session = inject(WorkspaceSession);
  const token = auth.getValidToken();

  const headers: Record<string, string> = {
    'X-Workspace-Id': session.id
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  return next(req.clone({ setHeaders: headers }));
};
