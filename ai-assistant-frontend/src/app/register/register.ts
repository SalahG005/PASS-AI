import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../auth';
import { PasswordStrength, getPasswordStrength } from '../password-strength';
import { ThemeService } from '../theme';

@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink],
  templateUrl: './register.html',
  styleUrl: './register.css'
})
export class Register implements OnInit {
  fullName = '';
  email = '';
  password = '';
  errorMessage = '';
  loading = false;
  passwordStrength: PasswordStrength = getPasswordStrength('');

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

  onPasswordChange(value: string) {
    this.password = value;
    this.passwordStrength = getPasswordStrength(value);
  }

  onSubmit(event?: Event) {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.loading) {
      return false;
    }

    this.errorMessage = '';

    const fullName = this.fullName.trim();
    const email = this.email.trim();
    const password = this.password;

    if (!fullName || !email || !password) {
      this.errorMessage = 'Please fill in all fields.';
      return false;
    }

    if (password.length < 6) {
      this.errorMessage = 'Password must be at least 6 characters.';
      return false;
    }

    this.loading = true;

    this.auth.register({ fullName, email, password }).subscribe({
      next: (response) => {
        if (!response?.token) {
          this.loading = false;
          this.errorMessage = 'Account created but no token was returned.';
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
        this.errorMessage = typeof err.error === 'string' ? err.error : 'Registration failed';
      }
    });

    return false;
  }
}
