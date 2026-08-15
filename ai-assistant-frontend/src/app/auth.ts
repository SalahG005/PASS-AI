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
    if (!token) {
      return null;
    }
    if (!this.isUsableToken(token)) {
      // Keep token in storage — server is source of truth. Clearing here caused
      // requests without Authorization → 401 → forced login redirects.
      return null;
    }
    return token;
  }

  isLoggedIn(): boolean {
    const token = this.getToken();
    if (!token) {
      return false;
    }
    // If we have a JWT shape, treat as logged in; expiry is enforced by the API.
    const parts = token.split('.');
    return parts.length === 3;
  }

  logout() {
    localStorage.removeItem(this.tokenKey);
  }

  /** Email claim from the JWT, if present. */
  getEmail(): string | null {
    const token = this.getToken();
    if (!token) {
      return null;
    }
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }
      const payload = JSON.parse(this.decodeBase64Url(parts[1]));
      const email = payload?.sub || payload?.email;
      return typeof email === 'string' && email.includes('@') ? email.toLowerCase() : null;
    } catch {
      return null;
    }
  }

  private isUsableToken(token: string): boolean {
    const parts = token.split('.');
    if (parts.length !== 3) {
      return false;
    }

    try {
      const payload = JSON.parse(this.decodeBase64Url(parts[1]));
      const exp = payload?.exp;
      if (typeof exp === 'number') {
        // 60s clock skew tolerance
        return exp * 1000 > Date.now() - 60_000;
      }
      // Missing exp → still send; backend validates
      return true;
    } catch {
      return true;
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
