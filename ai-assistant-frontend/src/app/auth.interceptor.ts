import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { Auth } from './auth';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const token = auth.getToken();

  if (!token) {
    return next(req);
  }

  const requestWithToken = req.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`
    }
  });

  return next(requestWithToken);
};
