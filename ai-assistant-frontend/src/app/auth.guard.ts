import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

export const authGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);

  if (auth.getValidToken()) {
    return true;
  }

  if (auth.getToken()) {
    auth.logout();
  }

  return router.createUrlTree(['/login']);
};
