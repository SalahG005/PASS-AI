import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../auth';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink],
  templateUrl: './login.html',
  styleUrl: './login.css'
})
export class Login {
  email = '';
  password = '';
  errorMessage = '';

  constructor(private auth: Auth, private router: Router) {}

  onSubmit() {
    this.errorMessage = '';

    this.auth.login({
      email: this.email,
      password: this.password
    }).subscribe({
      next: (response) => {
        this.auth.saveToken(response.token);
        this.router.navigate(['/chat']);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.errorMessage = 'Invalid email or password.';
          return;
        }

        this.errorMessage = typeof err.error === 'string' ? err.error : 'Login failed. Please try again.';
      }
    });
  }
}
