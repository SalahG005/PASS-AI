import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { RouterLink } from '@angular/router';
import { Auth } from '../auth';

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

  constructor(private auth: Auth, private router: Router) {}

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