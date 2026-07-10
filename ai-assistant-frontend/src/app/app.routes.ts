import { Routes } from '@angular/router';
import { authGuard } from './auth.guard';
import { ChatHome } from './chat-home/chat-home';
import { Login } from './login/login';
import { MainLayout } from './main-layout/main-layout';
import { Register } from './register/register';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  { path: 'login', component: Login },
  { path: 'register', component: Register },
  {
    path: '',
    component: MainLayout,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'chat' },
      { path: 'chat', component: ChatHome }
    ]
  },
  { path: '**', redirectTo: 'login' }
];