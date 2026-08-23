import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Auth } from '../auth';
import { ThemeService } from '../theme';
import { ToastService } from '../toast.service';

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
    private route: ActivatedRoute,
    public theme: ThemeService,
    private toast: ToastService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    if (this.auth.isLoggedIn()) {
      this.router.navigateByUrl('/chat', { replaceUrl: true });
      return;
    }
    if (this.route.snapshot.queryParamMap.get('reason') === 'session') {
      this.errorMessage = 'Session expired — please log in again.';
    }
  }

  onEmailChange(value: string) {
    this.email = value;
    this.errorMessage = '';
  }

  onPasswordChange(value: string) {
    this.password = value;
    this.errorMessage = '';
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

    this.auth.login({ email, password }).pipe(finalize(() => {
      this.loading = false;
      this.cdr.detectChanges();
    })).subscribe({
      next: (response) => {
        if (!response?.token) {
          this.errorMessage = 'Login succeeded but no token was returned.';
          this.toast.error(this.errorMessage);
          return;
        }
        this.auth.saveToken(response.token);
        void this.router.navigateByUrl('/chat', { replaceUrl: true });
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.errorMessage = this.getReadableErrorMessage(err, 'Invalid email or password.');
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

    if (err.status === 401) {
      return 'Invalid email or password.';
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
