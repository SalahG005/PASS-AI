import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../auth';
import { ThemeService } from '../theme';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink],
  templateUrl: './login.html',
  styleUrl: './login.css'
})
export class Login implements OnInit {
  email = '';
  password = '';
  errorMessage = '';
  loading = false;

  constructor(
    private auth: Auth,
    private router: Router,
    public theme: ThemeService
  ) {}

  ngOnInit() {
    if (this.auth.isLoggedIn()) {
      this.router.navigateByUrl('/chat', { replaceUrl: true });
    }
  }

  onSubmit(event?: Event) {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.loading) {
      return false;
    }

    this.errorMessage = '';

    const email = this.email.trim();
    const password = this.password;

    if (!email || !password) {
      this.errorMessage = 'Please enter email and password.';
      return false;
    }

    this.loading = true;

    this.auth.login({ email, password }).subscribe({
      next: (response) => {
        if (!response?.token) {
          this.loading = false;
          this.errorMessage = 'Login succeeded but no token was returned.';
          return;
        }
        this.auth.saveToken(response.token);
        this.loading = false;
        void this.router.navigateByUrl('/chat', { replaceUrl: true });
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        if (err.status === 0) {
          this.errorMessage = 'Cannot reach API. Make sure backend is running on port 8081.';
          return;
        }
        if (err.status === 401) {
          this.errorMessage = 'Invalid email or password.';
          return;
        }
        this.errorMessage = typeof err.error === 'string' ? err.error : 'Login failed. Please try again.';
      }
    });

    return false;
  }
}
