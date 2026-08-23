import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Auth } from '../auth';
import { PasswordStrength, getPasswordStrength } from '../password-strength';
import { ThemeService } from '../theme';
import { ToastService } from '../toast.service';

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
  emailError = '';
  emailTouched = false;
  loading = false;
  passwordStrength: PasswordStrength = getPasswordStrength('');
  private readonly allowedDomain = '@pass-consulting.com';
  private readonly emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  constructor(
    private auth: Auth,
    private router: Router,
    public theme: ThemeService,
    private toast: ToastService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    if (this.auth.isLoggedIn()) {
      this.router.navigateByUrl('/chat', { replaceUrl: true });
    }
  }

  get canSubmit(): boolean {
    return !!this.fullName.trim()
      && this.passwordStrength.score === 5;
  }

  onFullNameChange(value: string) {
    this.fullName = value;
    this.errorMessage = '';
  }

  onEmailChange(value: string) {
    this.email = value;
    this.errorMessage = '';
    this.validateEmail(false);
  }

  onEmailBlur() {
    this.emailTouched = true;
    this.validateEmail(true);
  }

  onPasswordChange(value: string) {
    this.password = value;
    this.errorMessage = '';
    this.passwordStrength = getPasswordStrength(value);
  }

  private isEmailValid(): boolean {
    return this.validateEmail(false);
  }

  private validateEmail(showTouched = true): boolean {
    const value = this.email.trim();
    if (showTouched) {
      this.emailTouched = true;
    }

    if (!value) {
      this.emailError = this.emailTouched ? 'Email is required.' : '';
      return false;
    }

    if (!this.emailPattern.test(value)) {
      this.emailError = this.emailTouched ? 'Enter a valid email address.' : '';
      return false;
    }

    if (!value.toLowerCase().endsWith(this.allowedDomain)) {
      this.emailError = this.emailTouched ? 'Only @pass-consulting.com email addresses are allowed.' : '';
      return false;
    }

    this.emailError = '';
    return true;
  }

  onSubmit(event?: Event) {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.loading) {
      return false;
    }

    this.errorMessage = '';
    this.emailTouched = true;

    const fullName = this.fullName.trim();
    const email = this.email.trim();
    const password = this.password;

    if (!fullName || !email || !password) {
      this.errorMessage = 'Please fill in all fields.';
      return false;
    }

    if (!this.validateEmail(true)) {
      this.errorMessage = this.emailError;
      return false;
    }

    if (this.passwordStrength.score !== 5) {
      this.errorMessage = 'Password must meet all strength requirements below.';
      return false;
    }

    this.loading = true;

    this.auth.register({ fullName, email, password }).pipe(finalize(() => {
      this.loading = false;
      this.cdr.detectChanges();
    })).subscribe({
      next: (response) => {
        if (!response?.token) {
          this.errorMessage = 'Account created but no token was returned.';
          this.toast.error(this.errorMessage);
          return;
        }
        this.auth.saveToken(response.token);
        this.toast.success('Account created successfully.');
        void this.router.navigateByUrl('/chat', { replaceUrl: true });
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.errorMessage = this.getReadableErrorMessage(err, 'Registration failed. Please try again.');
        this.toast.error(this.errorMessage);
      }
    });

    return false;
  }

  private getReadableErrorMessage(err: HttpErrorResponse, fallback: string): string {
    const backendMessage = this.extractBackendMessage(err.error);

    if (backendMessage) {
      return backendMessage;
    }

    if (err.status === 400) {
      return 'This email is already registered.';
    }

    if (err.status === 0) {
      return 'Something went wrong, please try again.';
    }

    return fallback;
  }

  private extractBackendMessage(error: unknown): string {
    if (typeof error === 'string' && error.trim()) {
      return error.trim();
    }

    if (error && typeof error === 'object') {
      const message = (error as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim()) {
        return message.trim();
      }

      const details = (error as { error?: unknown }).error;
      if (typeof details === 'string' && details.trim()) {
        return details.trim();
      }

      if (details && typeof details === 'object') {
        const nestedMessage = (details as { message?: unknown }).message;
        if (typeof nestedMessage === 'string' && nestedMessage.trim()) {
          return nestedMessage.trim();
        }
      }
    }

    return '';
  }
}
