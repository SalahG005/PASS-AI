import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class Auth {
  // Relative URL → Angular proxy forwards to http://localhost:8081 (avoids CORS)
  private baseUrl = '/api/auth';
  private readonly tokenKey = 'token';

  constructor(private http: HttpClient) {}

  register(data: { fullName: string; email: string; password: string }) {
    return this.http.post<{ token: string }>(`${this.baseUrl}/register`, data);
  }

  login(data: { email: string; password: string }) {
    return this.http.post<{ token: string }>(`${this.baseUrl}/login`, data);
  }

  saveToken(token: string) {
    if (this.isUsableToken(token)) {
      localStorage.setItem(this.tokenKey, token);
      return;
    }
    localStorage.removeItem(this.tokenKey);
  }

  getToken(): string | null {
    return localStorage.getItem(this.tokenKey);
  }

  getValidToken(): string | null {
    const token = this.getToken();
    if (!token || !this.isUsableToken(token)) {
      this.logout();
      return null;
    }
    return token;
  }

  isLoggedIn(): boolean {
    return !!this.getValidToken();
  }

  logout() {
    localStorage.removeItem(this.tokenKey);
  }

  private isUsableToken(token: string): boolean {
    const parts = token.split('.');
    if (parts.length !== 3) {
      return false;
    }

    try {
      const payload = JSON.parse(this.decodeBase64Url(parts[1]));
      if (typeof payload?.exp !== 'number') {
        return false;
      }
      return payload.exp * 1000 > Date.now();
    } catch {
      return false;
    }
  }

  private decodeBase64Url(value: string): string {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
    return decodeURIComponent(
      atob(padded)
        .split('')
        .map((char) => `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join('')
    );
  }
}
