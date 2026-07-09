import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class Auth {

  private baseUrl = 'http://localhost:8081/api/auth';

  constructor(private http: HttpClient) {}

  register(data: { fullName: string; email: string; password: string }) {
    return this.http.post<{ token: string }>(`${this.baseUrl}/register`, data);
  }

  login(data: { email: string; password: string }) {
    return this.http.post<{ token: string }>(`${this.baseUrl}/login`, data);
  }

  saveToken(token: string) {
    localStorage.setItem('token', token);
  }

  getToken(): string | null {
    return localStorage.getItem('token');
  }

  isLoggedIn(): boolean {
    return !!this.getToken();
  }

  logout() {
    localStorage.removeItem('token');
  }
}