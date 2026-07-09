import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { RouterLink } from '@angular/router';
import { Auth } from '../auth';
import { PasswordStrength, getPasswordStrength } from '../password-strength';

@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink],
  templateUrl: './register.html',
  styleUrl: './register.css'
})
export class Register {

  fullName = '';
  email = '';
  password = '';
  errorMessage = '';
  passwordStrength: PasswordStrength = getPasswordStrength('');

  constructor(private auth: Auth, private router: Router) {}

  onPasswordChange(value: string) {
    this.password = value;
    this.passwordStrength = getPasswordStrength(value);
  }

  onSubmit() {
    this.errorMessage = '';

    this.auth.register({
      fullName: this.fullName,
      email: this.email,
      password: this.password
    }).subscribe({
      next: (response) => {
        this.auth.saveToken(response.token);
        this.router.navigate(['/chat']);
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = typeof err.error === 'string' ? err.error : 'Registration failed';
      }
    });
  }
}