import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './theme';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  constructor(private theme: ThemeService) {
    // Ensures theme is applied on app boot (reads localStorage).
    void this.theme.theme();
  }
}
